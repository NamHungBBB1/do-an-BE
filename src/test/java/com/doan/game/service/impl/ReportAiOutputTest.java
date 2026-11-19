package com.doan.game.service.impl;

import com.doan.game.enums.AiProviderKind;
import com.doan.game.service.AiTextClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kiểm đầu ra AI (khung báo cáo AI mục 4 ý 3, BR-51) + phân loại lỗi nhà cung cấp (13.4). Hàm thuần, không Spring. */
class ReportAiOutputTest {

    static final String PKG = "{\"frame\":{\"chapters\":[{\"code\":\"CH01\",\"goal\":\"Giữ tối thiểu 1.800.000đ\","
            + "\"number\":1,\"title\":\"Tiền đầu tiên\"}],\"concepts\":[{\"id\":\"D01.02\"},{\"id\":\"D01.03\"},"
            + "{\"id\":\"D01.04\"}],\"decisions\":[{\"options\":[],\"scene\":\"CH01_SC02_CHOICE\"}]},\"kind\":\"CHAPTER\","
            + "\"result\":{\"concepts\":[{\"band\":\"GOOD\",\"correct\":5,\"id\":\"D01.02\",\"total\":6},"
            + "{\"band\":\"PRACTICE\",\"correct\":1,\"id\":\"D01.04\",\"total\":4}],\"noData\":[\"D01.03\"]}}";

    private static String out(String overview, String doingWell, String needsPractice, String decisionText, String tips) {
        return "{\"overview\":\"" + overview + "\",\"decisions\":[{\"scene\":\"CH01_SC02_CHOICE\",\"text\":\"" + decisionText
                + "\"}],\"doingWell\":[{\"concept\":\"" + doingWell + "\",\"text\":\"Bé làm tốt " + doingWell + ".\"}],"
                + "\"needsPractice\":[{\"concept\":\"" + needsPractice + "\",\"text\":\"Cần luyện thêm.\"}],\"tips\":" + tips
                + "}";
    }

    private static final String GOOD = out("Bé làm đúng 5/6 câu, mục tiêu giữ 1.800.000 đồng.", "D01.02", "D01.04",
            "Bé có bước cân nhắc.", "[]");

    @Test
    void acceptsFaithfulOutputAndComposesSections() {
        assertThat(ReportAiOutput.check(PKG, GOOD)).isNull();
        assertThat(ReportAiOutput.check(PKG, out("Ổn.", "D01.02", "D01.03", "Có cân nhắc.", "[]"))).isNull();
        String text = ReportAiOutput.compose(GOOD);
        assertThat(text).startsWith("Bé làm đúng 5/6 câu").contains("Quyết định trong truyện\n- Bé có bước cân nhắc.")
                .contains("Bé làm tốt\n- Bé làm tốt D01.02.").contains("Cần luyện thêm\n- Cần luyện thêm.")
                .doesNotContain("Gợi ý cho gia đình");
        assertThat(ReportAiOutput.compose("không phải json")).isEqualTo("không phải json");
    }

    @Test
    void rejectsByRule() {
        assertThat(ReportAiOutput.check(PKG, "{}")).startsWith("SHAPE");
        assertThat(ReportAiOutput.check(PKG, out("Đúng 83% số câu.", "D01.02", "D01.04", "Có.", "[]")))
                .isEqualTo("NUMBER_NOT_IN_PACKAGE: 83");
        assertThat(ReportAiOutput.check(PKG, out("Giữ 1.900.000 đồng.", "D01.02", "D01.04", "Có.", "[]")))
                .isEqualTo("NUMBER_NOT_IN_PACKAGE: 1.900.000");
        assertThat(ReportAiOutput.check(PKG, out("Bé làm đúng bảy câu.", "D01.02", "D01.04", "Có.", "[]")))
                .startsWith("NUMBER_NOT_IN_PACKAGE: bảy câu");
        assertThat(ReportAiOutput.check(PKG, out("Hai triệu đồng.", "D01.02", "D01.04", "Có.", "[]")))
                .isEqualTo("NUMBER_WORD: triệu");
        assertThat(ReportAiOutput.check(PKG, out("Ổn.", "D01.04", "D01.04", "Có.", "[]")))
                .isEqualTo("DOING_WELL_BAND: D01.04");
        assertThat(ReportAiOutput.check(PKG, out("Ổn.", "D01.02", "D01.02", "Có.", "[]")))
                .isEqualTo("NEEDS_PRACTICE_BAND: D01.02");
        assertThat(ReportAiOutput.check(PKG, out("Ổn.", "D01.02", "D01.04", "Có.",
                "[{\"concept\":\"D01.02\",\"keyPoint\":0,\"text\":\"Nói chuyện.\"}]"))).startsWith("TIPS_NOT_FROM_KEY_POINTS");
        assertThat(ReportAiOutput.check(PKG, out("Bé hơi Lười.", "D01.02", "D01.04", "Có.", "[]")))
                .isEqualTo("FORBIDDEN_WORD: lười");
        assertThat(ReportAiOutput.check(PKG, out("Ổn.", "D01.02", "D01.04", "Bé chọn sai phương án.", "[]")))
                .isEqualTo("CHOICE_JUDGED: sai");
        assertThat(ReportAiOutput.check(PKG, out("chữ ".repeat(200), "D01.02", "D01.04", "Có.", "[]")))
                .startsWith("TOO_LONG");
        // "sai" chỉ cấm khi nói về lựa chọn truyện; câu mini-game sai vẫn được nhắc ở mục khác.
        assertThat(ReportAiOutput.check(PKG, "{\"overview\":\"Ổn.\",\"decisions\":[],\"doingWell\":[],\"needsPractice\":"
                + "[{\"concept\":\"D01.04\",\"text\":\"Bé làm đúng 1/4 câu, còn chọn sai ở mini-game.\"}],\"tips\":[]}"))
                .isNull();
    }

    @Test
    void numberNormalisation() {
        assertThat(ReportAiOutput.norm("1.800.000")).isEqualTo("1800000");
        assertThat(ReportAiOutput.norm("1,800,000")).isEqualTo("1800000");
        assertThat(ReportAiOutput.norm("01")).isEqualTo("1");
        assertThat(ReportAiOutput.numbers("{\"x\":1800000,\"c\":\"D01.02\"}")).contains("1800000", "1.02", "1", "2");
    }

    private static AiTextClient.RawReply reply(int status, String body) {
        return new AiTextClient.RawReply(status, body, 5);
    }

    @Test
    void classifiesGeminiErrors() {
        String key = "AIzaSyTESTKEY1234567890";
        AiCallClassifier.Result minute = AiCallClassifier.classify(AiProviderKind.GEMINI, reply(429,
                "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"quota of project 123456789012\","
                        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{"
                        + "\"quotaMetric\":\"generativelanguage.googleapis.com/generate_content_free_tier_requests\","
                        + "\"quotaId\":\"GenerateRequestsPerMinutePerProjectPerModel-FreeTier\"}]},"
                        + "{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"36.5s\"}]}}"), key);
        assertThat(minute.outcome()).isEqualTo("QUOTA_MINUTE");
        assertThat(minute.retryDelaySeconds()).isEqualTo(37);
        assertThat(minute.message()).doesNotContain("123456789012").contains("######");
        assertThat(minute.quotaMetric()).contains("PerMinute");

        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(429, "{\"error\":{\"code\":429,\"details\":[{"
                + "\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":"
                + "\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}"), key).outcome()).isEqualTo("QUOTA_DAY");
        AiCallClassifier.Result badKey = AiCallClassifier.classify(AiProviderKind.GEMINI, reply(400,
                "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"API key not valid: " + key + "\","
                        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":"
                        + "\"API_KEY_INVALID\"}]}}"), key);
        assertThat(badKey.outcome()).isEqualTo("AUTH");
        assertThat(badKey.message()).doesNotContain(key);
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(400,
                "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad schema\"}}"), key).outcome())
                .isEqualTo("BAD_REQUEST");
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(400,
                "{\"error\":{\"code\":400,\"status\":\"FAILED_PRECONDITION\",\"message\":\"location\"}}"), key).outcome())
                .isEqualTo("PRECONDITION");
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(403, "{}"), key).outcome()).isEqualTo("AUTH");
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(404, "{}"), key).outcome()).isEqualTo("NOT_FOUND");
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(503, "oops"), key).outcome()).isEqualTo("SERVER");
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(504, ""), key).outcome()).isEqualTo("TIMEOUT");
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(0, "HttpTimeoutException"), key).outcome())
                .isEqualTo("TIMEOUT");
        AiCallClassifier.Result safety = AiCallClassifier.classify(AiProviderKind.GEMINI, reply(200,
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}],\"usageMetadata\":{\"promptTokenCount\":10}}"), key);
        assertThat(safety.outcome()).isEqualTo("SAFETY");
        assertThat(safety.tokensIn()).isEqualTo(10);
        assertThat(AiCallClassifier.classify(AiProviderKind.GEMINI, reply(200,
                "{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}"), key).outcome()).isEqualTo("SAFETY");
    }

    @Test
    void classifiesOpenRouter() {
        String key = "sk-or-v1-abcdef1234567890";
        assertThat(AiCallClassifier.classify(AiProviderKind.OPENROUTER, reply(402,
                "{\"error\":{\"code\":402,\"message\":\"Insufficient credits\"}}"), key).outcome()).isEqualTo("NO_CREDIT");
        assertThat(AiCallClassifier.classify(AiProviderKind.OPENROUTER, reply(502, "{}"), key).outcome())
                .isEqualTo("SERVER");
        assertThat(AiCallClassifier.classify(AiProviderKind.OPENROUTER, reply(200,
                "{\"error\":{\"code\":503,\"message\":\"upstream down\"}}"), key).outcome()).isEqualTo("SERVER");
        AiCallClassifier.Result ok = AiCallClassifier.classify(AiProviderKind.OPENROUTER, reply(200,
                "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}"), key);
        assertThat(ok.outcome()).isEqualTo("OK");
        assertThat(ok.text()).isEqualTo("{}");
        assertThat(ok.tokensOut()).isEqualTo(3);
        assertThat(AiCallClassifier.sanitize("key " + key + " and AIzaSyOTHERKEY0000000000", key))
                .isEqualTo("key *** and ***");
    }
}
