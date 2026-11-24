package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.AiCallLog;
import com.doan.game.entity.AiJob;
import com.doan.game.entity.AiKey;
import com.doan.game.entity.DailySummary;
import com.doan.game.enums.AiProviderKind;
import com.doan.game.enums.Role;
import com.doan.game.enums.SummarySource;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.AiCallLogRepository;
import com.doan.game.repository.AiJobRepository;
import com.doan.game.repository.AiKeyRepository;
import com.doan.game.repository.DailySummaryRepository;
import com.doan.game.service.AiJobWriter;
import com.doan.game.service.AiKeyProbe;
import com.doan.game.service.AiTextClient;
import com.doan.game.service.impl.AiJobQueue;
import com.doan.game.service.impl.ReportAiWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Đợt D — gọi AI cho bài viết người lớn (khung báo cáo AI 13.3, 13.4). Lớp HTTP tới nhà cung cấp là giả (AiTextClient.send
 * trả thân phản hồi Gemini / OpenRouter dựng sẵn) — không bao giờ gọi mạng thật. Khoá nhập qua API admin (probe giả).
 *
 * H2 dùng chung với lớp khác: mỗi ca xoá sạch khoá (như AiKeyFlowTest), alias có hậu tố riêng; nhật ký tra theo id việc /
 * alias của ca, không đếm toàn bảng. Việc gọi thẳng writer có status FAILED (hàng chờ không bao giờ lấy); việc chạy qua hàng
 * chờ có createdAt = 1970 nên đứng đầu lô và được "xả" tới khi xong.
 */
@SpringBootTest(properties = {"app.ai.gemini-model=gemini-test", "app.ai.gemini-fallback-model=gemini-test-lite"})
@AutoConfigureMockMvc
class ReportAiWriterFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Random RNG = new Random();

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired AiKeyRepository keyRepo;
    @Autowired AiJobRepository jobRepo;
    @Autowired AiCallLogRepository logRepo;
    @Autowired DailySummaryRepository summaryRepo;
    @Autowired AiJobQueue queue;
    @Autowired ReportAiWriter writer;
    @MockitoBean AiKeyProbe probe;
    @MockitoBean AiTextClient ai;

    /** (provider, secret, model) → phản hồi giả; gói không mang dấu của ca thì 503 (việc của lớp khác trong cùng lô). */
    private record Call(AiProviderKind provider, String secret, String model, String user) {}

    private final List<Call> calls = new ArrayList<>();
    private String marker;
    private String suffix;

    @BeforeEach
    void clean() {
        keyRepo.deleteAll();
        calls.clear();
        marker = letters();
        suffix = letters().substring(4);
        when(probe.probe(any(), any())).thenReturn(new AiKeyProbe.Result(AiKeyProbe.Outcome.OK, "200"));
        when(ai.model(AiProviderKind.OPENROUTER)).thenReturn("or-test");
    }

    private void respond(Function<Call, AiTextClient.RawReply> fn) {
        when(ai.send(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Call c = new Call(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(4));
            if (c.user() == null || !c.user().contains(marker)) {
                return new AiTextClient.RawReply(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}", 1);
            }
            calls.add(c);
            return fn.apply(c);
        });
    }

    // ------------------------------------------------------------------ 1. luồng chính qua hàng chờ
    @Test
    void happyPathThroughQueue() throws Exception {
        String k = addKey("GEMINI", "aiw-ok", 1, null, null);
        respond(c -> ok(goodOutput(), 1200, 150));
        AiJob job = job("QUEUED", Instant.now().minusSeconds(3600));
        DailySummary row = summary(job);

        drain(job.getId());

        AiJob done = jobRepo.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo("DONE");
        assertThat(done.getKeyAlias()).isEqualTo(k);
        assertThat(done.getCurrentKeyAlias()).isEqualTo(k);
        assertThat(done.getProvider()).isEqualTo("GEMINI");
        assertThat(JSON.readTree(done.getOutput()).path("overview").asText()).startsWith("Bé đã hoàn thành");
        DailySummary after = summaryRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getSource()).isEqualTo(SummarySource.LLM);
        assertThat(after.getTier()).isEqualTo(1);
        assertThat(after.getProvider()).isEqualTo("GEMINI");
        assertThat(after.getModel()).isEqualTo("gemini-test");
        assertThat(after.getPromptVersion()).isEqualTo("report-v3");
        // D.1: mã chưa đủ dữ liệu không nằm trong mục nào của AI — BE nối dòng bằng tên khái niệm.
        assertThat(after.getSummary()).startsWith("Bé đã hoàn thành").contains("Bé làm tốt\n- ")
                .endsWith("Chưa đủ bài để nhận xét: Chi phí cơ hội.").doesNotContain("D01.");
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.model()).isEqualTo("gemini-test");
            assertThat(c.secret()).isEqualTo(secret(k));
        });

        List<AiCallLog> logs = logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId());
        assertThat(logs).singleElement().satisfies(l -> {
            assertThat(l.getOutcome()).isEqualTo("OK");
            assertThat(l.getTokensIn()).isEqualTo(1200);
            assertThat(l.getTokensOut()).isEqualTo(150);
            assertThat(l.getHttpStatus()).isEqualTo(200);
            assertThat(l.getKeyAlias()).isEqualTo(k);
            assertThat(l.getModel()).isEqualTo("gemini-test");
            assertThat(l.getKind()).isEqualTo("REPORT_SUMMARY");
            assertThat(l.getAttempt()).isEqualTo(1);
            assertThat(l.getHumanText()).contains("thành công");
        });
        assertNoSecretOrPackage(logs, k);
    }

    // ------------------------------------------------------------------ 2. hết lượt theo phút → xoay khoá
    @Test
    void perMinuteQuotaCoolsKeyByRetryDelayAndRotates() {
        String k1 = addKey("GEMINI", "aiw-min1", 1, null, null);
        String k2 = addKey("GEMINI", "aiw-min2", 2, null, null);
        respond(c -> c.secret().equals(secret(k1)) ? quota("PerMinute", "37s") : ok(goodOutput(), 10, 5));
        AiJob job = job("FAILED", Instant.now());
        List<String> used = new ArrayList<>();
        Instant before = Instant.now();

        AiJobWriter.Output out = writer.write(job, used::add);

        assertThat(out.keyAlias()).isEqualTo(k2);
        assertThat(out.tier()).isEqualTo(1);
        assertThat(used).containsExactly(k1, k1, k2);   // model chính rồi model dự phòng trên k1, sau đó k2
        AiKey cooled = key(k1);
        assertThat(cooled.isEnabled()).isTrue();
        assertThat(cooled.getCoolDownUntil()).isAfter(before.plusSeconds(30)).isBefore(Instant.now().plusSeconds(40));
        assertThat(key(k2).getCoolDownUntil()).isNull();
        List<AiCallLog> logs = logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId());
        assertThat(logs).extracting(AiCallLog::getOutcome).containsExactly("QUOTA_MINUTE", "QUOTA_MINUTE", "OK");
        assertThat(logs).extracting(AiCallLog::getModel).containsExactly("gemini-test", "gemini-test-lite", "gemini-test");
        assertThat(logs.get(1).getRetryDelaySeconds()).isEqualTo(37);
        assertThat(logs.get(1).getQuotaMetric()).contains("PerMinute");
        assertThat(logs.get(1).getHumanText()).contains("nghỉ 37 giây");
        assertNoSecretOrPackage(logs, k1);
    }

    // ------------------------------------------------------------------ 3. hết lượt ngày → nghỉ cả project
    @Test
    void perDayQuotaCoolsEveryKeyOfTheProjectUntilPacificMidnight() {
        String a1 = addKey("GEMINI", "aiw-day1", 1, null, "proj-a");
        String a2 = addKey("GEMINI", "aiw-day2", 2, null, "proj-a");
        String b1 = addKey("GEMINI", "aiw-day3", 3, null, "proj-b");
        respond(c -> c.secret().equals(secret(a1)) ? quota("PerDay", null) : ok(goodOutput(), 10, 5));
        AiJob job = job("FAILED", Instant.now());

        AiJobWriter.Output out = writer.write(job, alias -> { });

        assertThat(out.keyAlias()).isEqualTo(b1);
        assertThat(calls).extracting(c -> c.secret()).doesNotContain(secret(a2));
        ZoneId pt = ZoneId.of("America/Los_Angeles");
        for (String alias : List.of(a1, a2)) {
            Instant until = key(alias).getCoolDownUntil();
            assertThat(until).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(25 * 3600));
            assertThat(until.atZone(pt).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(key(alias).getLastError()).startsWith("QUOTA_DAY:");
        }
        assertThat(key(b1).getCoolDownUntil()).isNull();
        assertThat(logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId()).get(1).getHumanText())
                .contains("mọi khoá của project proj-a");
    }

    // ------------------------------------------------------------------ 4. khoá sai → tắt khoá
    @Test
    void unauthorizedKeyIsDisabledWithHumanText() throws Exception {
        String bad = addKey("GEMINI", "aiw-auth1", 1, null, null);
        String good = addKey("GEMINI", "aiw-auth2", 2, null, null);
        respond(c -> c.secret().equals(secret(bad))
                ? new AiTextClient.RawReply(401, "{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\",\"message\":"
                + "\"key " + secret(bad) + " rejected for project 987654321098\"}}", 3)
                : ok(goodOutput(), 10, 5));
        AiJob job = job("FAILED", Instant.now());

        assertThat(writer.write(job, alias -> { }).keyAlias()).isEqualTo(good);

        AiKey off = key(bad);
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.getLastError()).startsWith("AUTH:").contains("TẮT");
        List<AiCallLog> logs = logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId());
        assertThat(logs.get(0).getOutcome()).isEqualTo("AUTH");
        assertThat(logs.get(0).getHttpStatus()).isEqualTo(401);
        assertThat(logs.get(0).getProviderStatus()).isEqualTo("UNAUTHENTICATED");
        assertThat(logs.get(0).getProviderMessage()).contains("***").contains("######").doesNotContain("987654321098");
        assertThat(logs.get(0).getHumanText()).contains("TẮT khoá");
        assertNoSecretOrPackage(logs, bad);

        JsonNode health = health();
        assertThat(health.path("needsHuman").asBoolean()).isTrue();
        assertThat(health.path("healthyKeys").asInt()).isEqualTo(1);
        assertThat(health.path("warning").asText()).contains("Chỉ còn 1 khoá");
    }

    // ------------------------------------------------------------------ 5. lỗi tạm phía nhà cung cấp
    @Test
    void serverErrorDoesNotPenalizeKey() {
        String k1 = addKey("GEMINI", "aiw-srv1", 1, null, null);
        addKey("GEMINI", "aiw-srv2", 2, null, null);
        respond(c -> new AiTextClient.RawReply(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}", 2));
        AiJob job = job("FAILED", Instant.now());

        assertThatThrownBy(() -> writer.write(job, alias -> { }))
                .isInstanceOfSatisfying(AiTextClient.AiCallException.class, e -> {
                    assertThat(e.quota()).isFalse();
                    assertThat(e.fatal()).isFalse();
                    assertThat(e.code()).isEqualTo("SERVER");
                });
        assertThat(calls).hasSize(1);   // lỗi tạm của nhà cung cấp: không thử thêm khoá cùng nhà cung cấp
        assertThat(key(k1).isEnabled()).isTrue();
        assertThat(key(k1).getCoolDownUntil()).isNull();
        assertThat(logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId()).get(0).getHumanText())
                .contains("Không tính lỗi cho khoá");
    }

    // ------------------------------------------------------------------ 6. đầu ra bị BE loại
    @Test
    void rejectedOutputIsRetriedOnceThenFails() {
        String k1 = addKey("GEMINI", "aiw-rej1", 1, null, null);
        String k2 = addKey("GEMINI", "aiw-rej2", 2, null, null);
        String badNumber = goodOutput().replace("5/6 câu", "83% câu");
        respond(c -> ok(c.secret().equals(secret(k1)) ? badNumber : goodOutput(), 10, 5));
        AiJob job = job("FAILED", Instant.now());

        assertThat(writer.write(job, alias -> { }).keyAlias()).isEqualTo(k2);
        AiCallLog rejected = logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId()).get(0);
        assertThat(rejected.getOutcome()).isEqualTo("REJECTED_OUTPUT");
        assertThat(rejected.getRejectedRule()).isEqualTo("NUMBER_NOT_IN_PACKAGE: 83");
        assertThat(rejected.getHumanText()).contains("NUMBER_NOT_IN_PACKAGE");

        // Bị loại hai lần (chỉ một khoá → thử lại trên chính nó) → FAILED ngay, không chờ hàng chờ thử lại.
        keyRepo.deleteAll();
        addKey("GEMINI", "aiw-rej3", 1, null, null);
        respond(c -> ok(badNumber, 10, 5));
        AiJob second = job("FAILED", Instant.now());
        assertThatThrownBy(() -> writer.write(second, alias -> { }))
                .isInstanceOfSatisfying(AiTextClient.AiCallException.class, e -> {
                    assertThat(e.fatal()).isTrue();
                    assertThat(e.code()).isEqualTo("REJECTED_OUTPUT");
                });
        assertThat(logRepo.findByAiJobIdOrderByCreatedAtAsc(second.getId())).extracting(AiCallLog::getOutcome)
                .containsExactly("REJECTED_OUTPUT", "REJECTED_OUTPUT");
    }

    // ------------------------------------------------------------------ 7. bộ lọc an toàn
    @Test
    void safetyBlockFailsJobAndKeepsRuleText() {
        addKey("GEMINI", "aiw-safe", 1, null, null);
        respond(c -> new AiTextClient.RawReply(200, "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}", 4));
        AiJob job = job("QUEUED", Instant.now().minusSeconds(3600));
        DailySummary row = summary(job);

        drain(job.getId());

        AiJob failed = jobRepo.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getLastErrorCode()).isEqualTo("SAFETY");
        assertThat(failed.getAttempts()).isZero();
        DailySummary after = summaryRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getSource()).isEqualTo(SummarySource.RULE);
        assertThat(after.getSummary()).isEqualTo("Bản theo luật " + marker);
        assertThat(logRepo.findByAiJobIdOrderByCreatedAtAsc(job.getId())).singleElement()
                .satisfies(l -> assertThat(l.getOutcome()).isEqualTo("SAFETY"));
    }

    // ------------------------------------------------------------------ 8. khoá dự phòng demo
    @Test
    void demoReserveUsedOnlyWhenNoGeneralKey() throws Exception {
        String reserve = addKey("GEMINI", "aiw-demo", 1, "DEMO_RESERVE", null);
        String general = addKey("OPENROUTER", "aiw-gen", 9, null, null);
        respond(c -> c.provider() == AiProviderKind.OPENROUTER ? orOk(goodOutput()) : ok(goodOutput(), 10, 5));

        AiJobWriter.Output out = writer.write(job("FAILED", Instant.now()), alias -> { });
        assertThat(out.keyAlias()).isEqualTo(general);   // khoá thường trước, kể cả khi khoá demo ưu tiên cao hơn
        assertThat(out.tier()).isEqualTo(2);
        assertThat(out.provider()).isEqualTo("OPENROUTER");

        String id = keyRepo.findAll().stream().filter(k -> k.getAlias().equals(general)).findFirst().orElseThrow()
                .getId().toString();
        mvc.perform(post("/api/admin/ai-keys/" + id + "/disable").with(admin())).andExpect(status().isOk());
        assertThat(writer.write(job("FAILED", Instant.now()), alias -> { }).keyAlias()).isEqualTo(reserve);
    }

    // ------------------------------------------------------------------ 9. không còn khoá
    @Test
    void noEnabledKeyFailsFastToRuleText() {
        AiJob job = job("FAILED", Instant.now());
        assertThatThrownBy(() -> writer.write(job, alias -> { }))
                .isInstanceOfSatisfying(AiTextClient.AiCallException.class, e -> {
                    assertThat(e.fatal()).isTrue();
                    assertThat(e.code()).isEqualTo("NO_KEY");
                });
    }

    // ------------------------------------------------------------------ 10. sức khoẻ, nhật ký, PATCH, quyền
    @Test
    void healthLogsPatchAndAdminOnly() throws Exception {
        String k1 = addKey("GEMINI", "aiw-h1", 1, null, "proj-h");
        String k2 = addKey("GEMINI", "aiw-h2", 2, null, "proj-h");
        int[] n = {0};
        respond(c -> n[0]++ == 0 ? new AiTextClient.RawReply(500, "{}", 1) : ok(goodOutput(), 100, 20));
        AiJob job = job("FAILED", Instant.now());
        assertThatThrownBy(() -> writer.write(job, alias -> { })).isInstanceOf(AiTextClient.AiCallException.class);
        writer.write(job, alias -> { });
        writer.write(job, alias -> { });

        JsonNode health = health();
        assertThat(health.path("healthyKeys").asInt()).isEqualTo(2);
        assertThat(health.path("warning").isNull()).isTrue();
        assertThat(health.path("needsHuman").asBoolean()).isFalse();
        JsonNode h1 = keyOf(health, k1);
        assertThat(h1.path("callsToday").asLong()).isEqualTo(3);
        assertThat(h1.path("okToday").asLong()).isEqualTo(2);
        assertThat(h1.path("outcomesToday").path("SERVER").asLong()).isEqualTo(1);
        assertThat(h1.path("tokensInToday").asLong()).isEqualTo(200);
        assertThat(h1.path("tokensOutToday").asLong()).isEqualTo(40);
        assertThat(h1.path("lastOkAt").isNull()).isFalse();
        assertThat(h1.path("project").asText()).isEqualTo("proj-h");
        assertThat(h1.path("purpose").asText()).isEqualTo("GENERAL");
        assertThat(keyOf(health, k2).path("callsToday").asLong()).isZero();

        String page = mvc.perform(get("/api/admin/ai-call-logs").param("keyAlias", k1).param("size", "500")
                        .with(admin())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode items = JSON.readTree(page).path("result").path("items");
        assertThat(items).hasSize(3);
        assertThat(JSON.readTree(page).path("result").path("size").asInt()).isEqualTo(100);
        assertThat(items.get(2).path("outcome").asText()).isEqualTo("SERVER");   // mới nhất trước
        assertThat(page).doesNotContain(secret(k1)).doesNotContain(marker);
        String okOnly = mvc.perform(get("/api/admin/ai-call-logs").param("keyAlias", k1).param("outcome", "OK")
                .with(admin())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(okOnly).path("result").path("items")).hasSize(2);

        // PATCH trường quản lý; giá trị lạ → 9001.
        String id = keyRepo.findAll().stream().filter(k -> k.getAlias().equals(k2)).findFirst().orElseThrow()
                .getId().toString();
        mvc.perform(patch("/api/admin/ai-keys/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"demo_reserve\",\"tierLabel\":\"PAID\",\"ownerNote\":\"nhóm\","
                                + "\"rpmLimit\":10,\"rpdLimit\":250,\"priority\":7}"))
                .andExpect(status().isOk());
        AiKey patched = key(k2);
        assertThat(patched.getPurpose()).isEqualTo("DEMO_RESERVE");
        assertThat(patched.getTierLabel()).isEqualTo("PAID");
        assertThat(patched.getRpmLimit()).isEqualTo(10);
        assertThat(patched.getRpdLimit()).isEqualTo(250);
        assertThat(patched.getPriority()).isEqualTo(7);
        assertThat(patched.getProject()).isEqualTo("proj-h");
        mvc.perform(patch("/api/admin/ai-keys/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"VIP\"}")).andExpect(status().isBadRequest());

        // rpmLimit: k1 đã gọi 3 lần trong phút này → chạm giới hạn 3 thì bị bỏ qua.
        mvc.perform(patch("/api/admin/ai-keys/" + keyRepo.findAll().stream().filter(k -> k.getAlias().equals(k1))
                        .findFirst().orElseThrow().getId()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rpmLimit\":3}")).andExpect(status().isOk());
        assertThat(writer.write(job, alias -> { }).keyAlias()).isEqualTo(k2);

        RequestPostProcessor parent = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_PARENT"));
        mvc.perform(get("/api/admin/ai-keys/health").with(parent)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ai-call-logs").with(parent)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ dựng dữ liệu

    private String addKey(String provider, String aliasPrefix, int priority, String purpose, String project) {
        String alias = aliasPrefix + "-" + suffix;
        try {
            ObjectNode body = JSON.createObjectNode().put("provider", provider).put("alias", alias)
                    .put("key", secret(alias)).put("priority", priority);
            if (purpose != null) {
                body.put("purpose", purpose);
            }
            if (project != null) {
                body.put("project", project);
            }
            mvc.perform(post("/api/admin/ai-keys").with(admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString())).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return alias;
    }

    private static String secret(String alias) {
        return "AIzaSyFAKE-" + alias + "-secret";
    }

    private AiKey key(String alias) {
        return keyRepo.findAll().stream().filter(k -> k.getAlias().equals(alias)).findFirst().orElseThrow();
    }

    private AiJob job(String status, Instant createdAt) {
        AiJob job = new AiJob();
        job.setKind("REPORT_SUMMARY");
        job.setInput(pkg());
        job.setInputHash("aiw-" + UUID.randomUUID());
        job.setStatus(status);
        job.setAttempts(0);
        job.setTier(1);
        job.setCreatedAt(createdAt);
        return jobRepo.save(job);
    }

    private DailySummary summary(AiJob job) {
        DailySummary s = new DailySummary();
        s.setKind("CHAPTER");
        s.setChapter(1);
        s.setAttemptIndex(1);
        s.setSummary("Bản theo luật " + marker);
        s.setSource(SummarySource.RULE);
        s.setTier(0);
        s.setPackageHash(job.getInputHash());
        s.setCreatedAt(Instant.now());
        s.setAiJob(job);
        return summaryRepo.save(s);
    }

    /** Gói giống ReportPackage.canonicalJson (CHAPTER), dấu của ca nằm ở tên chương (chỉ chữ cái). */
    private String pkg() {
        return "{\"frame\":{\"bands\":{\"FAMILIAR\":\"50–69%\",\"GOOD\":\"≥ 70%\",\"NO_DATA\":\"dưới 3 câu\","
                + "\"PRACTICE\":\"< 50%\"},\"chapters\":[{\"code\":\"CH01\",\"goal\":\"Giữ tối thiểu 300.000đ để đóng phí "
                + "lớp vẽ vào ngày 28\",\"number\":1,\"title\":\"Tiền đầu tiên " + marker + "\"}],\"concepts\":[{\"id\":"
                + "\"D01.02\"},{\"id\":\"D01.03\"}],\"decisions\":[{\"options\":[{\"deliberate\":false,\"id\":\"A\","
                + "\"label\":\"Mua ngay\"},{\"deliberate\":true,\"id\":\"B\",\"label\":\"Chờ một tuần\"}],\"scene\":"
                + "\"CH01_SC02_CHOICE\"}],\"statsNote\":\"chỉ số gameplay\"},\"kind\":\"CHAPTER\",\"result\":{\"concepts\":"
                + "[{\"band\":\"GOOD\",\"correct\":5,\"game\":{\"correct\":5,\"total\":6},\"id\":\"D01.02\",\"postTest\":"
                + "{\"correct\":0,\"total\":0},\"total\":6}],\"decisionSummary\":{\"decisions\":1,\"deliberate\":1,"
                + "\"labelled\":1},\"decisions\":[{\"chosen\":\"B\",\"deliberate\":true,\"label\":\"Chờ một tuần\","
                + "\"scene\":\"CH01_SC02_CHOICE\"}],\"miniGames\":{\"correct\":5,\"total\":6},\"noData\":[\"D01.03\"],"
                + "\"wrongItems\":[]}}";
    }

    private static String goodOutput() {
        return "{\"overview\":\"Bé đã hoàn thành chương đầu và làm đúng 5/6 câu về nhu cầu và mong muốn.\","
                + "\"decisions\":[{\"scene\":\"CH01_SC02_CHOICE\",\"text\":\"Bé chọn chờ một tuần, có bước cân nhắc "
                + "trước khi mua.\"}],\"doingWell\":[{\"concept\":\"D01.02\",\"text\":\"Bé phân biệt tốt cần và muốn.\"}],"
                + "\"needsPractice\":[],\"tips\":[]}";
    }

    private static AiTextClient.RawReply ok(String text, int in, int out) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode cand = body.putArray("candidates").addObject();
        cand.putObject("content").put("role", "model").putArray("parts").addObject().put("text", text);
        cand.put("finishReason", "STOP");
        body.putObject("usageMetadata").put("promptTokenCount", in).put("candidatesTokenCount", out);
        return new AiTextClient.RawReply(200, body.toString(), 42);
    }

    private static AiTextClient.RawReply orOk(String text) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("choices").addObject().put("finish_reason", "stop").putObject("message").put("content", text);
        body.putObject("usage").put("prompt_tokens", 11).put("completion_tokens", 7);
        return new AiTextClient.RawReply(200, body.toString(), 42);
    }

    /** Gemini 429 RESOURCE_EXHAUSTED với QuotaFailure (PerMinute / PerDay) và RetryInfo tuỳ chọn. */
    private static AiTextClient.RawReply quota(String per, String retryDelay) {
        String retry = retryDelay == null ? "" : ",{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\","
                + "\"retryDelay\":\"" + retryDelay + "\"}";
        return new AiTextClient.RawReply(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":"
                + "\"You exceeded your current quota.\",\"details\":[{\"@type\":"
                + "\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaMetric\":"
                + "\"generativelanguage.googleapis.com/generate_content_free_tier_requests\",\"quotaId\":"
                + "\"GenerateRequests" + per + "PerProjectPerModel-FreeTier\"}]}" + retry + "]}}", 8);
    }

    private void drain(UUID jobId) {
        for (int i = 0; i < 50; i++) {
            AiJob j = jobRepo.findById(jobId).orElseThrow();
            if ("DONE".equals(j.getStatus()) || "FAILED".equals(j.getStatus())) {
                return;
            }
            if ("WAITING_RETRY".equals(j.getStatus())) {
                j.setNextAttemptAt(Instant.now());
                jobRepo.save(j);
            }
            queue.runOnce(writer);
        }
        assertThat(jobRepo.findById(jobId).orElseThrow().getStatus()).isIn("DONE", "FAILED");
    }

    private void assertNoSecretOrPackage(List<AiCallLog> logs, String alias) {
        for (AiCallLog l : logs) {
            String all = String.join("|", String.valueOf(l.getProviderMessage()), String.valueOf(l.getHumanText()),
                    String.valueOf(l.getQuotaMetric()), String.valueOf(l.getRejectedRule()),
                    String.valueOf(l.getProviderStatus()));
            assertThat(all).doesNotContain(secret(alias)).doesNotContain("AIzaSy").doesNotContain(marker)
                    .doesNotContain("D01.02").doesNotContain("Chờ một tuần");
        }
    }

    private JsonNode health() throws Exception {
        String body = mvc.perform(get("/api/admin/ai-keys/health").with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).path("result");
    }

    private static JsonNode keyOf(JsonNode health, String alias) {
        for (JsonNode k : health.path("keys")) {
            if (alias.equals(k.path("alias").asText())) {
                return k;
            }
        }
        throw new AssertionError("không thấy khoá " + alias);
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("aiw-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin Aiw");
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole role = new AccountRole();
        role.setAccount(a);
        role.setRole(Role.ADMIN);
        role.setGrantedAt(Instant.now());
        accountRoleRepo.save(role);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    private static String letters() {
        StringBuilder b = new StringBuilder("aiwm");
        for (int i = 0; i < 8; i++) {
            b.append((char) ('a' + RNG.nextInt(26)));
        }
        return b.toString();
    }
}
