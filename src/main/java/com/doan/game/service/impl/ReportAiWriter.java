package com.doan.game.service.impl;

import com.doan.game.entity.AiCallLog;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.AiKey;
import com.doan.game.enums.AiProviderKind;
import com.doan.game.repository.AiCallLogRepository;
import com.doan.game.repository.AiKeyRepository;
import com.doan.game.service.AiJobWriter;
import com.doan.game.service.AiTextClient;
import com.doan.game.service.AiTextClient.AiCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tầng 1–2 của hàng chờ AI cho REPORT_SUMMARY (đợt D; khung báo cáo AI 13.3, 13.4; spec kho ảnh + giọng 4.7b).
 *
 * Khoá: đang bật, không nghỉ, chưa chạm rpmLimit (60 s) / rpdLimit (ngày Gemini, theo project); Gemini trước (tầng 1) rồi
 * OpenRouter (tầng 2), trong mỗi nhà cung cấp priority nhỏ trước. Khoá DEMO_RESERVE chỉ dùng khi app.ai.demo-mode=true (khi đó
 * dùng TRƯỚC) hoặc sau khi mọi khoá GENERAL đã hết / hỏng. Gemini: model chính, hết lượt hoặc 404 thì thử model dự phòng trên
 * cùng khoá. Mỗi lần gọi HTTP ghi một dòng AiCallLog (không khoá, không gói).
 *
 * Xử lý theo loại lỗi (bảng 13.4): hết lượt phút → khoá nghỉ retryDelay; hết lượt ngày → mọi khoá cùng project nghỉ tới nửa đêm
 * Pacific; 401 / 403 / 400 khoá sai / FAILED_PRECONDITION / OpenRouter 402 → TẮT khoá + log AI-ALERT; 400 INVALID_ARGUMENT → lỗi
 * của BE, dừng việc, khoá giữ nguyên; 5xx / timeout → không phạt khoá, bỏ qua các khoá còn lại cùng nhà cung cấp; SAFETY →
 * FAILED ngay (bản theo luật). Đầu ra bị BE loại → thử lại một lần ở khoá kế (hoặc cùng khoá nếu chỉ còn nó); loại lần hai →
 * FAILED. TODO tầng 3–4 (phiếu Discord cho OC / Claude, POST /api/studio/ai/jobs/{id}/result): thay FAILED bằng WAITING_AGENT.
 */
@Slf4j
@Component
public class ReportAiWriter implements AiJobWriter {

    static final String GENERAL = "GENERAL";
    static final String DEMO_RESERVE = "DEMO_RESERVE";
    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiKeyRepository keyRepo;
    private final AiKeyServiceImpl keys;
    private final AiTextClient ai;
    private final AiCallLogRepository logRepo;
    private final Clock clock;
    private final String geminiModel;
    private final String geminiFallbackModel;
    private final boolean demoMode;

    public ReportAiWriter(AiKeyRepository keyRepo, AiKeyServiceImpl keys, AiTextClient ai, AiCallLogRepository logRepo,
                          Clock clock,
                          @Value("${app.ai.gemini-model:gemini-2.5-flash}") String geminiModel,
                          @Value("${app.ai.gemini-fallback-model:gemini-2.5-flash-lite}") String geminiFallbackModel,
                          @Value("${app.ai.demo-mode:false}") boolean demoMode) {
        this.keyRepo = keyRepo;
        this.keys = keys;
        this.ai = ai;
        this.logRepo = logRepo;
        this.clock = clock;
        this.geminiModel = geminiModel;
        this.geminiFallbackModel = geminiFallbackModel;
        this.demoMode = demoMode;
    }

    @Override
    public Output write(AiJob job, Progress progress) {
        if (!AiJobQueue.REPORT_SUMMARY.equals(job.getKind())) {
            throw new AiCallException("chưa có tầng AI cho loại việc " + job.getKind(), false, "UNSUPPORTED_KIND", true);
        }
        JsonNode pkg = readPackage(job.getInput());
        String system = ReportAiOutput.systemPrompt(pkg);
        JsonNode schema = ReportAiOutput.responseSchema();
        int attempt = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;

        List<AiKey> candidates = usableKeys();
        if (candidates.isEmpty()) {
            boolean cooling = keyRepo.findAll().stream().anyMatch(AiKey::isEnabled);
            if (cooling) {
                throw new AiCallException("mọi khoá AI đang nghỉ hoặc đã chạm giới hạn", true, "QUOTA", false);
            }
            log.warn("AI-ALERT không còn khoá AI nào đang bật — báo cáo dùng bản viết theo luật");
            throw new AiCallException("không có khoá AI nào đang bật", false, "NO_KEY", true);
        }

        boolean quotaOnly = true;
        String lastCode = null;
        String lastText = null;
        boolean rejectedOnce = false;
        AiProviderKind skipProvider = null;
        Set<UUID> cooled = new HashSet<>();   // khoá vừa cho nghỉ trong lượt này (hết lượt ngày → cả project)
        for (int i = 0; i < candidates.size(); i++) {
            AiKey key = candidates.get(i);
            if (key.getProvider() == skipProvider || cooled.contains(key.getId())) {
                continue;
            }
            String secret = keys.decrypt(key.getSecretEnc());
            List<String> models = models(key.getProvider());
            for (int m = 0; m < models.size(); m++) {
                String model = models.get(m);
                boolean hasFallback = m + 1 < models.size();
                progress.usingKey(key.getAlias());
                AiTextClient.RawReply reply = ai.send(key.getProvider(), secret, model, system, job.getInput(), schema);
                AiCallClassifier.Result r = AiCallClassifier.classify(key.getProvider(), reply, secret);
                String outcome = r.outcome();
                String rule = null;
                if (AiCallClassifier.OK.equals(outcome)) {
                    rule = r.text() == null ? "SHAPE: không có nội dung" : ReportAiOutput.check(job.getInput(), r.text());
                    if (rule == null) {
                        record(job, attempt, key, model, reply, r, outcome, null,
                                AiCallClassifier.humanText(outcome, key.getAlias(), model, r, null));
                        return new Output(ReportAiOutput.compose(job.getInput(), r.text()), tier(key), key.getProvider().name(),
                                key.getAlias(), model, ReportAiOutput.PROMPT_VERSION, r.text());
                    }
                    outcome = AiCallClassifier.REJECTED_OUTPUT;
                }
                String extra = rule;
                if (AiCallClassifier.QUOTA_DAY.equals(outcome)) {
                    extra = blank(key.getProject()) ? "khoá này" : "mọi khoá của project " + key.getProject();
                }
                String human = AiCallClassifier.humanText(outcome, key.getAlias(), model, r, extra);
                boolean tryFallback = hasFallback && (AiCallClassifier.QUOTA_MINUTE.equals(outcome)
                        || AiCallClassifier.QUOTA_DAY.equals(outcome) || AiCallClassifier.NOT_FOUND.equals(outcome));
                if (tryFallback) {
                    human = (AiCallClassifier.NOT_FOUND.equals(outcome) ? "Không có model " + model
                            : "Model " + model + " hết lượt") + " trên khoá " + key.getAlias() + ". Hệ thống thử model dự "
                            + "phòng " + models.get(m + 1) + " trên cùng khoá, chưa cho khoá nghỉ."
                            + (AiCallClassifier.NOT_FOUND.equals(outcome) ? " Báo dev đổi app.ai.gemini-model." : "");
                }
                record(job, attempt, key, model, reply, r, outcome, rule, human);
                lastCode = outcome;
                lastText = key.getAlias() + ": " + human;

                switch (outcome) {
                    case AiCallClassifier.QUOTA_MINUTE, AiCallClassifier.QUOTA_DAY -> {
                        if (hasFallback) {
                            continue;   // thử model dự phòng trên cùng khoá trước khi cho khoá nghỉ
                        }
                        cooled.addAll(coolDown(key, outcome, r, human));
                    }
                    case AiCallClassifier.NOT_FOUND -> {
                        quotaOnly = false;
                        if (hasFallback) {
                            continue;
                        }
                    }
                    case AiCallClassifier.AUTH, AiCallClassifier.PRECONDITION, AiCallClassifier.NO_CREDIT -> {
                        quotaOnly = false;
                        disable(key, outcome, human);
                    }
                    case AiCallClassifier.BAD_REQUEST ->
                            throw new AiCallException(lastText, false, outcome, false);
                    case AiCallClassifier.SAFETY ->
                            throw new AiCallException(lastText, false, outcome, true);
                    case AiCallClassifier.SERVER, AiCallClassifier.TIMEOUT -> {
                        quotaOnly = false;
                        skipProvider = key.getProvider();
                    }
                    case AiCallClassifier.REJECTED_OUTPUT -> {
                        quotaOnly = false;
                        if (rejectedOnce) {
                            // TODO tầng 3–4: leo lên OC / Claude thay vì FAILED.
                            throw new AiCallException(lastText, false, outcome, true);
                        }
                        rejectedOnce = true;
                        if (i == candidates.size() - 1) {
                            candidates = new ArrayList<>(candidates);
                            candidates.add(key);   // chỉ còn khoá này: thử lại một lần trên chính nó
                        }
                    }
                    default -> quotaOnly = false;
                }
                break;
            }
        }
        if (quotaOnly) {
            throw new AiCallException(lastText == null ? "mọi khoá AI hết lượt" : lastText, true, "QUOTA", false);
        }
        // TODO tầng 3–4: hết tầng 1–2 thì phiếu Discord (WAITING_AGENT); nay hàng chờ thử lại rồi FAILED → bản theo luật.
        throw new AiCallException(lastText, false, lastCode, false);
    }

    private JsonNode readPackage(String input) {
        try {
            JsonNode n = input == null ? null : JSON.readTree(input);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException();
            }
            return n;
        } catch (Exception e) {
            throw new AiCallException("gói input hỏng", false, "BAD_INPUT", true);
        }
    }

    private List<String> models(AiProviderKind provider) {
        if (provider != AiProviderKind.GEMINI) {
            return List.of(ai.model(provider));
        }
        return blank(geminiFallbackModel) || geminiFallbackModel.equals(geminiModel)
                ? List.of(geminiModel) : List.of(geminiModel, geminiFallbackModel);
    }

    private static int tier(AiKey key) {
        return key.getProvider() == AiProviderKind.GEMINI ? 1 : 2;
    }

    /** Khoá dùng được theo thứ tự thử (xem chú thích lớp). */
    List<AiKey> usableKeys() {
        Instant now = Instant.now(clock);
        List<AiKey> all = keyRepo.findAll();
        Comparator<AiKey> order = Comparator.comparing((AiKey k) -> k.getProvider() == AiProviderKind.GEMINI ? 0 : 1)
                .thenComparing(k -> k.getPriority() == null ? Integer.MAX_VALUE : k.getPriority())
                .thenComparing(AiKey::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        List<AiKey> ok = all.stream()
                .filter(k -> k.isEnabled() && (k.getCoolDownUntil() == null || !k.getCoolDownUntil().isAfter(now)))
                .filter(k -> withinLimits(k, all, now))
                .sorted(order).toList();
        List<AiKey> general = ok.stream().filter(k -> !DEMO_RESERVE.equals(k.getPurpose())).toList();
        List<AiKey> reserve = ok.stream().filter(k -> DEMO_RESERVE.equals(k.getPurpose())).toList();
        List<AiKey> out = new ArrayList<>(demoMode ? reserve : general);
        out.addAll(demoMode ? general : reserve);
        return out;
    }

    /** rpmLimit: số lần gọi của khoá trong 60 s; rpdLimit: số lần gọi trong ngày Gemini của mọi khoá cùng project. */
    private boolean withinLimits(AiKey k, List<AiKey> all, Instant now) {
        if (k.getRpmLimit() != null && k.getRpmLimit() > 0 && logRepo.countByKeyAliasInAndCreatedAtGreaterThanEqual(
                List.of(k.getAlias()), now.minusSeconds(60)) >= k.getRpmLimit()) {
            return false;
        }
        if (k.getRpdLimit() != null && k.getRpdLimit() > 0) {
            List<String> aliases = blank(k.getProject()) ? List.of(k.getAlias()) : all.stream()
                    .filter(o -> o.getProvider() == k.getProvider() && k.getProject().equalsIgnoreCase(o.getProject()))
                    .map(AiKey::getAlias).toList();
            return logRepo.countByKeyAliasInAndCreatedAtGreaterThanEqual(aliases, quotaDayStart(now)) < k.getRpdLimit();
        }
        return true;
    }

    static Instant quotaDayStart(Instant now) {
        return LocalDate.ofInstant(now, QUOTA_ZONE).atStartOfDay(QUOTA_ZONE).toInstant();
    }

    static Instant nextQuotaReset(Instant now) {
        return LocalDate.ofInstant(now, QUOTA_ZONE).plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
    }

    /** Cho khoá nghỉ; trả id các khoá đã cho nghỉ. */
    private List<UUID> coolDown(AiKey key, String outcome, AiCallClassifier.Result r, String human) {
        Instant now = Instant.now(clock);
        if (AiCallClassifier.QUOTA_DAY.equals(outcome)) {
            Instant until = nextQuotaReset(now);
            List<AiKey> same = blank(key.getProject()) ? List.of(key) : keyRepo.findAll().stream()
                    .filter(o -> o.getProvider() == key.getProvider() && key.getProject().equalsIgnoreCase(o.getProject()))
                    .toList();
            for (AiKey k : same) {
                AiKey fresh = keyRepo.findById(k.getId()).orElse(k);
                fresh.setCoolDownUntil(until);
                fresh.setLastError(clip(outcome + ": " + human));
                keyRepo.save(fresh);
            }
            return same.stream().map(AiKey::getId).toList();
        }
        int secs = r.retryDelaySeconds() == null ? AiCallClassifier.DEFAULT_RETRY_SECONDS : r.retryDelaySeconds();
        AiKey fresh = keyRepo.findById(key.getId()).orElse(key);
        fresh.setCoolDownUntil(now.plus(Duration.ofSeconds(Math.max(1, secs))));
        fresh.setLastError(clip(outcome + ": " + human));
        keyRepo.save(fresh);
        return List.of(key.getId());
    }

    private void disable(AiKey key, String outcome, String human) {
        AiKey fresh = keyRepo.findById(key.getId()).orElse(key);
        fresh.setEnabled(false);
        fresh.setLastError(clip(outcome + ": " + human));
        keyRepo.save(fresh);
        log.warn("AI-ALERT {} — khoá {} ({}) đã bị tắt: {}", outcome, key.getAlias(), key.getProvider(), human);
    }

    private void record(AiJob job, int attempt, AiKey key, String model, AiTextClient.RawReply reply,
                        AiCallClassifier.Result r, String outcome, String rule, String human) {
        AiCallLog l = new AiCallLog();
        l.setCreatedAt(Instant.now(clock));
        l.setAiJobId(job.getId());
        l.setAttempt(attempt);
        l.setKind(job.getKind());
        l.setKeyAlias(key.getAlias());
        l.setProvider(key.getProvider().name());
        l.setModel(model);
        l.setHttpStatus(r.httpStatus());
        l.setProviderStatus(r.providerStatus());
        l.setProviderMessage(r.message());
        l.setQuotaMetric(r.quotaMetric());
        l.setRetryDelaySeconds(r.retryDelaySeconds());
        l.setLatencyMs((int) Math.min(Integer.MAX_VALUE, reply.latencyMs()));
        l.setTokensIn(r.tokensIn());
        l.setTokensOut(r.tokensOut());
        l.setOutcome(outcome);
        l.setRejectedRule(rule == null ? null : clip(rule, 160));
        l.setHumanText(clip(human, 500));
        try {
            logRepo.save(l);
        } catch (RuntimeException e) {
            log.warn("khong ghi duoc AiCallLog: {}", e.toString());   // nhật ký hỏng không được làm hỏng việc
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s) {
        return clip(s, 255);
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
