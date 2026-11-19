package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lớp 3 của gói báo cáo người lớn (khung báo cáo AI mục 4): yêu cầu viết gửi AI, schema đầu ra, BE kiểm đầu ra trước khi lưu
 * (BR-51) và ghép bài hiện cho người lớn. Hàm thuần — test gọi thẳng.
 *
 * Đầu ra AI: {overview, decisions[{scene,text}], doingWell[{concept,text}], needsPractice[{concept,text}],
 * tips[{concept,keyPoint,text}]}. Bị loại khi: sai hình; mã doingWell không ở mức GOOD; mã needsPractice không ở mức thấp
 * (PRACTICE / FAMILIAR / NO_DATA / noData); cảnh không có trong gói; tips không dựa vào adultKeyPoints; con số (kể cả số viết
 * bằng chữ) không có trong gói; quá số chữ; có từ cấm.
 */
final class ReportAiOutput {

    static final String PROMPT_VERSION = "report-v1";
    static final int MAX_WORDS_CHAPTER = 180;
    static final int MAX_WORDS_SUMMARY = 250;

    /**
     * Từ cấm (khung mục 4 ý 3, khung v3 mục 7–8): dán nhãn tính cách / hoàn cảnh của bé hay gia đình, khuyên sản phẩm tài
     * chính thật. So theo nguyên từ (không khớp giữa chữ), không phân biệt hoa thường.
     */
    static final List<String> FORBIDDEN = List.of(
            "kém", "lười", "lười biếng", "dốt", "ngu", "ngốc", "chậm hiểu", "hư hỏng", "bất cẩn", "cẩu thả",
            "vô trách nhiệm", "tiêu hoang", "hoang phí", "phung phí", "keo kiệt", "bủn xỉn", "tham lam",
            "ham chơi", "nghèo", "nhà nghèo", "khó khăn về tiền",
            "cổ phiếu", "chứng khoán", "trái phiếu", "tiền ảo", "tiền điện tử", "bitcoin", "crypto", "thẻ tín dụng",
            "vay tiêu dùng", "vay nóng", "quỹ đầu tư");
    /** Lựa chọn trong truyện không có đúng sai (BR-45) — chỉ cấm trong mục decisions và overview. */
    static final List<String> FORBIDDEN_FOR_CHOICES = List.of("sai", "sai lầm", "nhầm", "dại", "dở", "tệ", "đúng đắn");
    /** Số lớn viết bằng chữ: số tiền phải chép chữ số từ gói. */
    static final List<String> NUMBER_WORDS = List.of("mười", "mươi", "trăm", "nghìn", "ngàn", "triệu");
    /** Chữ số đếm đứng trước danh từ đếm được ("năm câu") → đổi ra số rồi kiểm có trong gói. */
    private static final Map<String, String> DIGIT_WORDS = Map.of("một", "1", "hai", "2", "ba", "3", "bốn", "4", "tư",
            "4", "năm", "5", "sáu", "6", "bảy", "7", "tám", "8", "chín", "9");
    private static final Pattern COUNTED = Pattern.compile(
            "(?<!\\p{L})(một|hai|ba|bốn|tư|năm|sáu|bảy|tám|chín)\\s+(câu|lần|chương|lựa chọn|phương án|bài|mã|quyết định|"
                    + "khái niệm|mini-game|trò chơi)(?!\\p{L})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+");
    private static final Set<String> LOW_BANDS = Set.of("PRACTICE", "FAMILIAR", "NO_DATA");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportAiOutput() {
    }

    // ------------------------------------------------------------------ gửi đi

    static int maxWords(JsonNode pkg) {
        return ReportPackage.CHAPTER.equals(pkg.path("kind").asText()) ? MAX_WORDS_CHAPTER : MAX_WORDS_SUMMARY;
    }

    /** Lớp 3 (systemInstruction). Người đọc PARENT; gói đi riêng ở tin người dùng. */
    static String systemPrompt(JsonNode pkg) {
        return """
                Bạn viết bản tóm tắt kết quả học tài chính của MỘT bé cho phụ huynh (người đọc: PARENT), bằng tiếng Việt, \
                giọng ấm áp, cụ thể, tôn trọng bé. Dữ liệu duy nhất được dùng là gói JSON ở tin nhắn người dùng; không suy \
                đoán điều gì ngoài gói.
                Trả về JSON đúng schema, 5 mục:
                - overview: 2–3 câu tổng quan.
                - decisions: mỗi phần tử {scene, text} cho một quyết định có trong frame.decisions (scene giữ nguyên mã \
                cảnh). Chỉ nói bé "có" hoặc "chưa có" bước cân nhắc (trường deliberate); không có nhãn thì không nhận xét. \
                Lựa chọn trong truyện không có đúng sai.
                - doingWell: {concept, text}, CHỈ mã có band = GOOD trong result.concepts.
                - needsPractice: {concept, text}, CHỈ mã có band PRACTICE, FAMILIAR hoặc NO_DATA, hoặc nằm trong \
                result.noData (khi đó nói là chưa đủ dữ liệu để nhận xét).
                - tips: {concept, keyPoint, text}, CHỈ dựa vào frame.concepts[].adultKeyPoints (keyPoint là chỉ số ý, đếm \
                từ 0). Gói không có adultKeyPoints thì tips là mảng rỗng.
                Luật bắt buộc:
                1. Mọi con số phải có nguyên văn trong gói, viết bằng chữ số đúng như trong gói (ví dụ 5/6, 300.000đ). \
                Không tự tính phần trăm hay con số mới. Không viết số bằng chữ.
                2. Tổng độ dài mọi mục không quá %d chữ (đếm theo khoảng trắng).
                3. Không dán nhãn tính cách hay hoàn cảnh của bé hoặc gia đình (ví dụ lười, kém, tiêu hoang, nghèo).
                4. Không gọi lựa chọn trong truyện là sai hay đúng.
                5. Không khuyên dùng sản phẩm tài chính thật (cổ phiếu, tiền ảo, thẻ tín dụng, quỹ đầu tư, vay).
                6. %s
                7. Mã khái niệm (ví dụ D01.02) và mã cảnh giữ nguyên trong trường concept / scene; trong câu văn có thể \
                nhắc mã.
                """.formatted(maxWords(pkg), ReportPackage.STATS_NOTE);
    }

    /** Schema đầu ra cho Gemini (OpenAPI subset). */
    static JsonNode responseSchema() {
        return read("""
                {"type":"OBJECT","required":["overview","decisions","doingWell","needsPractice","tips"],"properties":{
                 "overview":{"type":"STRING"},
                 "decisions":{"type":"ARRAY","items":{"type":"OBJECT","required":["scene","text"],
                   "properties":{"scene":{"type":"STRING"},"text":{"type":"STRING"}}}},
                 "doingWell":{"type":"ARRAY","items":{"type":"OBJECT","required":["concept","text"],
                   "properties":{"concept":{"type":"STRING"},"text":{"type":"STRING"}}}},
                 "needsPractice":{"type":"ARRAY","items":{"type":"OBJECT","required":["concept","text"],
                   "properties":{"concept":{"type":"STRING"},"text":{"type":"STRING"}}}},
                 "tips":{"type":"ARRAY","items":{"type":"OBJECT","required":["concept","keyPoint","text"],
                   "properties":{"concept":{"type":"STRING"},"keyPoint":{"type":"INTEGER"},"text":{"type":"STRING"}}}}}}
                """);
    }

    // ------------------------------------------------------------------ kiểm đầu ra

    /** Luật bị vi phạm (mã + chi tiết ngắn) hoặc null khi đạt. pkgText = gói nguyên văn (AiJob.input). */
    static String check(String pkgText, String raw) {
        JsonNode pkg = read(pkgText);
        JsonNode out = read(raw);
        if (out == null || !out.isObject() || !out.path("overview").isTextual() || out.path("overview").asText().isBlank()) {
            return "SHAPE: không phải JSON có overview";
        }
        for (String sec : List.of("decisions", "doingWell", "needsPractice", "tips")) {
            JsonNode a = out.path(sec);
            if (!a.isMissingNode() && !a.isNull() && !a.isArray()) {
                return "SHAPE: " + sec + " không phải mảng";
            }
            for (JsonNode it : a) {
                if (!it.path("text").isTextual()) {
                    return "SHAPE: " + sec + " thiếu text";
                }
            }
        }
        if (pkg == null) {
            return "SHAPE: gói hỏng";
        }
        Map<String, String> bands = new java.util.HashMap<>();
        pkg.path("result").path("concepts").forEach(c -> bands.put(c.path("id").asText(), c.path("band").asText()));
        Set<String> noData = new HashSet<>();
        pkg.path("result").path("noData").forEach(n -> noData.add(n.asText()));
        for (JsonNode it : out.path("doingWell")) {
            String code = it.path("concept").asText();
            if (!"GOOD".equals(bands.get(code))) {
                return "DOING_WELL_BAND: " + code;
            }
        }
        for (JsonNode it : out.path("needsPractice")) {
            String code = it.path("concept").asText();
            if (!noData.contains(code) && !LOW_BANDS.contains(bands.get(code))) {
                return "NEEDS_PRACTICE_BAND: " + code;
            }
        }
        Set<String> scenes = new HashSet<>();
        pkg.path("frame").path("decisions").forEach(d -> scenes.add(d.path("scene").asText()));
        for (JsonNode it : out.path("decisions")) {
            if (!scenes.contains(it.path("scene").asText())) {
                return "UNKNOWN_SCENE: " + it.path("scene").asText();
            }
        }
        Map<String, Integer> keyPoints = new java.util.HashMap<>();
        pkg.path("frame").path("concepts").forEach(c -> keyPoints.put(c.path("id").asText(),
                c.path("adultKeyPoints").size()));
        for (JsonNode it : out.path("tips")) {
            int idx = it.path("keyPoint").asInt(-1);
            if (idx < 0 || idx >= keyPoints.getOrDefault(it.path("concept").asText(), 0)) {
                return "TIPS_NOT_FROM_KEY_POINTS: " + it.path("concept").asText();
            }
        }

        String all = nfc(String.join("\n", texts(out, List.of("overview", "decisions", "doingWell", "needsPractice",
                "tips"))));
        Set<String> allowed = numbers(nfc(pkgText));
        Matcher m = NUMBER.matcher(all);
        while (m.find()) {
            if (!allowed.contains(norm(m.group()))) {
                return "NUMBER_NOT_IN_PACKAGE: " + m.group();
            }
        }
        // ponytail: chỉ bắt chữ số đơn trước danh từ đếm ("năm câu") và từ chỉ số lớn; "mười hai câu" bắt được "hai câu".
        Matcher w = COUNTED.matcher(all);
        while (w.find()) {
            if (!allowed.contains(DIGIT_WORDS.get(w.group(1).toLowerCase(Locale.ROOT)))) {
                return "NUMBER_NOT_IN_PACKAGE: " + w.group();
            }
        }
        String hit = firstWord(all, NUMBER_WORDS);
        if (hit != null) {
            return "NUMBER_WORD: " + hit;
        }
        int words = all.isBlank() ? 0 : all.trim().split("\\s+").length;
        if (words > maxWords(pkg)) {
            return "TOO_LONG: " + words + " > " + maxWords(pkg);
        }
        hit = firstWord(all, FORBIDDEN);
        if (hit != null) {
            return "FORBIDDEN_WORD: " + hit;
        }
        hit = firstWord(nfc(String.join("\n", texts(out, List.of("overview", "decisions")))), FORBIDDEN_FOR_CHOICES);
        if (hit != null) {
            return "CHOICE_JUDGED: " + hit;
        }
        return null;
    }

    /** Bài hiện cho người lớn: tổng quan + các mục có tiêu đề; mục rỗng thì bỏ. Đầu ra không phải JSON → trả nguyên văn. */
    static String compose(String raw) {
        JsonNode out = read(raw);
        if (out == null || !out.isObject() || !out.path("overview").isTextual()) {
            return raw;
        }
        List<String> parts = new ArrayList<>();
        parts.add(out.path("overview").asText().trim());
        section(parts, out, "decisions", "Quyết định trong truyện");
        section(parts, out, "doingWell", "Bé làm tốt");
        section(parts, out, "needsPractice", "Cần luyện thêm");
        section(parts, out, "tips", "Gợi ý cho gia đình");
        return String.join("\n\n", parts);
    }

    private static void section(List<String> parts, JsonNode out, String key, String title) {
        List<String> lines = new ArrayList<>();
        out.path(key).forEach(it -> lines.add("- " + it.path("text").asText().trim()));
        if (!lines.isEmpty()) {
            parts.add(title + "\n" + String.join("\n", lines));
        }
    }

    private static List<String> texts(JsonNode out, List<String> sections) {
        List<String> t = new ArrayList<>();
        for (String s : sections) {
            JsonNode n = out.path(s);
            if (n.isTextual()) {
                t.add(n.asText());
            }
            n.forEach(it -> t.add(it.path("text").asText("")));
        }
        return t;
    }

    /** Mọi con số trong gói, đã chuẩn hoá (gói nguyên văn: số JSON, số trong chữ phương án / mục tiêu, mã như D01.02). */
    static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(norm(m.group()));
            for (String part : m.group().split("[.,]")) {
                out.add(norm(part));   // "1.02" trong gói cũng cho phép 1 và 2 đứng riêng
            }
        }
        return out;
    }

    /** 1.800.000 / 1,800,000 → 1800000; 0,5 → 0.5; bỏ số 0 đứng đầu phần nguyên (01 → 1). */
    static String norm(String t) {
        String s = THOUSANDS.matcher(t).matches() ? t.replaceAll("[.,]", "") : t.replace(',', '.');
        int dot = s.indexOf('.');
        String head = dot < 0 ? s : s.substring(0, dot);
        head = head.replaceFirst("^0+(?=\\d)", "");
        return dot < 0 ? head : head + s.substring(dot);
    }

    private static String firstWord(String text, List<String> words) {
        for (String w : words) {
            Pattern p = Pattern.compile("(?<!\\p{L})" + Pattern.quote(w) + "(?!\\p{L})",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (p.matcher(text).find()) {
                return w;
            }
        }
        return null;
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC);
    }

    private static JsonNode read(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
