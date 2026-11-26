package com.doan.game.service.impl;

import com.doan.game.enums.AiProviderKind;
import com.doan.game.service.AiTextClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc phản hồi của nhà cung cấp AI thành một kết quả có loại (khung báo cáo AI 13.4, Hưng chốt 19/11). Hàm thuần, không gọi
 * mạng, không chạm DB — test đưa thẳng thân lỗi Gemini / OpenRouter vào.
 *
 * Gemini: lỗi dạng {error:{code,status,message,details:[QuotaFailure{violations[quotaMetric,quotaId]}, RetryInfo{retryDelay},
 * ErrorInfo{reason}]}}. Đối chiếu tài liệu Google (ai.google.dev/gemini-api/docs/troubleshooting): khoá sai trả 400
 * INVALID_ARGUMENT kèm ErrorInfo.reason API_KEY_INVALID (không phải 401) → xếp AUTH; quota theo ngày nhận ra qua quotaId /
 * quotaMetric có "PerDay". OpenRouter: {error:{code,message,metadata}}; 402 = hết credit.
 */
final class AiCallClassifier {

    static final String OK = "OK";
    static final String QUOTA_MINUTE = "QUOTA_MINUTE";
    static final String QUOTA_DAY = "QUOTA_DAY";
    static final String AUTH = "AUTH";
    static final String BAD_REQUEST = "BAD_REQUEST";
    static final String PRECONDITION = "PRECONDITION";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String SERVER = "SERVER";
    static final String TIMEOUT = "TIMEOUT";
    static final String SAFETY = "SAFETY";
    static final String REJECTED_OUTPUT = "REJECTED_OUTPUT";
    static final String NO_CREDIT = "NO_CREDIT";

    /** Lỗi cần người làm (báo #chung khi có webhook; nay chỉ log AI-ALERT + màn sức khoẻ). */
    static final Set<String> NEEDS_HUMAN = Set.of(AUTH, NO_CREDIT, PRECONDITION);
    /** Lỗi tắt khoá. */
    static final Set<String> DISABLES_KEY = Set.of(AUTH, NO_CREDIT, PRECONDITION);

    static final int DEFAULT_RETRY_SECONDS = 60;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SAFETY_REASONS = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII",
            "RECITATION", "IMAGE_SAFETY");
    private static final Pattern DELAY = Pattern.compile("(\\d+(?:\\.\\d+)?)s");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern KEY_LIKE = Pattern.compile("AIza[0-9A-Za-z_\\-]{10,}|AQ\\.[0-9A-Za-z_.\\-]{10,}|sk-or-[0-9A-Za-z_\\-]{6,}|"
            + "sk-[0-9A-Za-z_\\-]{16,}");

    private AiCallClassifier() {
    }

    /** Kết quả một lần gọi. text chỉ có khi OK. */
    record Result(String outcome, int httpStatus, String providerStatus, String message, String quotaMetric,
                  Integer retryDelaySeconds, Integer tokensIn, Integer tokensOut, String text) {
    }

    static Result classify(AiProviderKind provider, AiTextClient.RawReply reply, String key) {
        int status = reply.status();
        if (status == 0) {
            return new Result(TIMEOUT, 0, null, sanitize(reply.body(), key), null, null, null, null, null);
        }
        JsonNode body = parse(reply.body());
        JsonNode err = body.path("error");
        if (status == 200 && !err.isObject()) {
            return provider == AiProviderKind.GEMINI ? geminiOk(body) : openRouterOk(body);
        }
        if (status == 200) {
            // OpenRouter đôi khi trả lỗi của nhà cung cấp gốc trong thân 200.
            status = err.path("code").asInt(502);
        }
        String providerStatus = err.path("status").isTextual() ? err.path("status").asText()
                : err.path("code").isMissingNode() ? null : err.path("code").asText();
        String message = sanitize(err.path("message").asText(reply.body() == null ? "" : reply.body()), key);
        String quotaMetric = null;
        String quotaId = null;
        Integer retry = null;
        String reason = null;
        for (JsonNode d : err.path("details")) {
            String type = d.path("@type").asText("");
            if (type.endsWith("QuotaFailure")) {
                JsonNode v = d.path("violations").path(0);
                quotaMetric = v.path("quotaMetric").asText(null);
                quotaId = v.path("quotaId").asText(null);
            } else if (type.endsWith("RetryInfo")) {
                Matcher m = DELAY.matcher(d.path("retryDelay").asText(""));
                if (m.matches()) {
                    retry = (int) Math.ceil(Double.parseDouble(m.group(1)));
                }
            } else if (type.endsWith("ErrorInfo")) {
                reason = d.path("reason").asText(null);
            }
        }
        String metricText = quotaMetric == null ? null
                : sanitize(quotaId == null ? quotaMetric : quotaMetric + " / " + quotaId, key);
        String outcome = outcomeOf(provider, status, providerStatus, reason, quotaId, quotaMetric, message);
        if ((QUOTA_MINUTE.equals(outcome)) && retry == null) {
            retry = DEFAULT_RETRY_SECONDS;
        }
        return new Result(outcome, status, providerStatus, message, metricText, retry, null, null, null);
    }

    private static String outcomeOf(AiProviderKind provider, int status, String providerStatus, String reason,
                                    String quotaId, String quotaMetric, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (status == 429) {
            String q = ((quotaId == null ? "" : quotaId) + " " + (quotaMetric == null ? "" : quotaMetric)
                    + " " + lower).toLowerCase(Locale.ROOT);
            return q.contains("perday") || q.contains("per_day") || q.contains("per-day") || q.contains("per day")
                    ? QUOTA_DAY : QUOTA_MINUTE;
        }
        if (status == 402) {
            return provider == AiProviderKind.OPENROUTER ? NO_CREDIT : PRECONDITION;
        }
        if (status == 401 || status == 403) {
            return AUTH;
        }
        if (status == 400) {
            if ("API_KEY_INVALID".equals(reason) || lower.contains("api key")) {
                return AUTH;
            }
            return "FAILED_PRECONDITION".equals(providerStatus) ? PRECONDITION : BAD_REQUEST;
        }
        if (status == 404) {
            return NOT_FOUND;
        }
        if (status == 408 || status == 504) {
            return TIMEOUT;
        }
        return SERVER;
    }

    private static Result geminiOk(JsonNode body) {
        Integer in = intOrNull(body.path("usageMetadata").path("promptTokenCount"));
        Integer out = intOrNull(body.path("usageMetadata").path("candidatesTokenCount"));
        String block = body.path("promptFeedback").path("blockReason").asText(null);
        JsonNode cand = body.path("candidates").path(0);
        String finish = cand.path("finishReason").asText(null);
        if (block != null || (finish != null && SAFETY_REASONS.contains(finish))) {
            return new Result(SAFETY, 200, block != null ? block : finish, "bị bộ lọc an toàn chặn", null, null, in, out,
                    null);
        }
        JsonNode text = cand.path("content").path("parts").path(0).path("text");
        return new Result(OK, 200, finish, null, null, null, in, out, text.isTextual() ? text.asText() : null);
    }

    private static Result openRouterOk(JsonNode body) {
        Integer in = intOrNull(body.path("usage").path("prompt_tokens"));
        Integer out = intOrNull(body.path("usage").path("completion_tokens"));
        JsonNode choice = body.path("choices").path(0);
        String finish = choice.path("finish_reason").asText(null);
        if ("content_filter".equals(finish)) {
            return new Result(SAFETY, 200, finish, "bị bộ lọc an toàn chặn", null, null, in, out, null);
        }
        JsonNode text = choice.path("message").path("content");
        return new Result(OK, 200, finish, null, null, null, in, out, text.isTextual() ? text.asText() : null);
    }

    private static Integer intOrNull(JsonNode n) {
        return n.isNumber() ? n.asInt() : null;
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode n = body == null ? null : JSON.readTree(body);
            return n == null ? JSON.createObjectNode() : n;
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    /** Lọc khoá (nguyên văn + mọi chuỗi trông như khoá), che dãy số ≥ 6 chữ số (số project), cắt 500 ký tự. */
    static String sanitize(String s, String key) {
        if (s == null) {
            return null;
        }
        String out = s;
        if (key != null && key.length() >= 6) {
            out = out.replace(key, "***");
        }
        out = KEY_LIKE.matcher(out).replaceAll("***");
        out = LONG_DIGITS.matcher(out).replaceAll("######");
        return out.length() <= 500 ? out : out.substring(0, 500);
    }

    /** Câu diễn giải tiếng Việt: chuyện gì xảy ra · hệ thống đã làm gì · người phải làm gì (bảng 13.4). */
    static String humanText(String outcome, String alias, String model, Result r, String extra) {
        String k = "Khoá " + alias;
        return switch (outcome) {
            case OK -> k + " trả bài thành công (model " + model + ").";
            case QUOTA_MINUTE -> k + " hết lượt theo phút" + metric(r) + ". Hệ thống cho khoá nghỉ "
                    + (r.retryDelaySeconds() == null ? DEFAULT_RETRY_SECONDS : r.retryDelaySeconds())
                    + " giây và chuyển sang khoá khác. Không cần làm gì.";
            case QUOTA_DAY -> k + " hết lượt trong ngày" + metric(r) + ". Hệ thống cho " + extra
                    + " nghỉ tới giờ reset của Gemini (nửa đêm giờ Thái Bình Dương). Nếu ngày nào cũng hết, thêm khoá ở "
                    + "project khác.";
            case AUTH -> k + " bị nhà cung cấp từ chối (khoá sai, bị thu hồi hoặc bị chặn). Hệ thống đã TẮT khoá. Người "
                    + "quản trị kiểm tra hoặc thay khoá rồi bật lại.";
            case PRECONDITION -> k + " không đủ điều kiện gọi (ví dụ vùng không được hỗ trợ, project chưa bật dịch vụ). "
                    + "Hệ thống đã TẮT khoá. Người quản trị kiểm tra project rồi bật lại.";
            case NO_CREDIT -> k + " (OpenRouter) hết credit. Hệ thống đã TẮT khoá. Nạp thêm credit rồi bật lại.";
            case BAD_REQUEST -> "Nhà cung cấp báo yêu cầu sai định dạng (lỗi của BE, không phải của " + k.toLowerCase(
                    Locale.ROOT) + "). Khoá giữ nguyên, việc thử lại sau. Báo dev kiểm tra.";
            case NOT_FOUND -> "Không có model " + model + " với " + k.toLowerCase(Locale.ROOT)
                    + ". Hệ thống thử model dự phòng. Báo dev đổi app.ai.gemini-model.";
            case SERVER -> "Nhà cung cấp lỗi tạm (HTTP " + r.httpStatus() + ") khi dùng " + k.toLowerCase(Locale.ROOT)
                    + ". Không tính lỗi cho khoá; việc thử lại sau. Không cần làm gì.";
            case TIMEOUT -> "Quá thời gian chờ khi gọi bằng " + k.toLowerCase(Locale.ROOT)
                    + ". Tính một lần thử; việc thử lại sau. Chỉ cần xem nếu lặp lại nhiều.";
            case SAFETY -> "Bộ lọc an toàn của nhà cung cấp chặn bài (" + r.providerStatus() + "). Hệ thống dùng bản viết "
                    + "theo luật. Báo dev xem lại prompt / gói.";
            case REJECTED_OUTPUT -> "Bài AI bị BE loại (luật " + extra + "). Hệ thống thử lại một lần ở khoá kế; vẫn bị "
                    + "loại thì dùng bản viết theo luật. Theo dõi tỉ lệ loại theo promptVersion.";
            default -> outcome;
        };
    }

    private static String metric(Result r) {
        return r.quotaMetric() == null ? "" : " (" + r.quotaMetric() + ")";
    }
}
