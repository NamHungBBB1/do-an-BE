package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.ChoiceEvent;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.InspectEvent;
import com.doan.game.entity.MiniGameResult;
import com.doan.game.entity.PickEvent;
import com.doan.game.entity.ReflectionAnswer;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.OutgoingMail;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/play/chapter với lô {@code schema: 2} (build/api-docs/play-v2.md, giai đoạn A) qua HTTP thật (MockMvc + H2, token SLOT
 * thật). Lô bản 1 vẫn do PlayFlowTest giữ; ở đây chỉ kiểm thêm chỗ hai đường khác nhau (trường lạ, trần body).
 *
 * Bản riêng: CH01 có một nhóm quyết định trải hai cảnh (G1) + phương án deferLedger, một mã phương án nằm ở hai cảnh, hai cảnh thẻ,
 * hai cảnh phản tư (một có khai inputs), một mini-game khuôn v3; CH02 bật metadata.preserveFirstChoiceStats.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class PlayV2FlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);

    private static String version;

    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired RunStateRepository runStateRepo;
    @Autowired AuthFlowTest.MailCatcher mailCatcher;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
    }

    // ------------------------------------------------------------------ 1. nhóm trải hai cảnh, có hiệu lực, lần chọn đầu
    @Test
    void groupAcrossTwoScenesKeepsEveryPickAndCountsEffectiveChoice() throws Exception {
        String child = newChild("v2hop");
        String body = batch(1, 1, """
                "picks":[
                  {"seq":3,"sceneId":"c1","optionId":"A","at":"2026-10-02T03:00:03Z"},
                  {"seq":2,"sceneId":"c1","optionId":"INFO"},
                  {"seq":4,"sceneId":"c1_revise","optionId":"C_REV","at":"2026-10-02T03:00:04Z"},
                  {"seq":7,"sceneId":"c2_receipt","optionId":"SHARED"}],
                "inspections":[{"seq":1,"sceneId":"i1","inspectId":"card1"}],
                "miniGames":[
                  {"seq":6,"gameId":"SHOP_V3","itemId":"phone","chosen":"B","at":"2026-10-02T03:00:06Z"},
                  {"seq":6,"gameId":"SHOP_V3","itemId":"inspected","chosen":[]}],
                "reflections":[]""");

        send(child, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.attemptIndex").value(1))
                .andExpect(jsonPath("$.result.chaptersDone[0]").value(1))
                .andExpect(jsonPath("$.result.continueChapter").value(2))
                .andExpect(jsonPath("$.result.newAchievements").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("saving"))));
        UUID slot = slotOf(child);

        List<PickEvent> picks = rows(PickEvent.class, slot);
        picks.sort(Comparator.comparing(PickEvent::getSeq));
        assertThat(picks).extracting(PickEvent::getSeq).containsExactly(2, 3, 4, 7);
        assertThat(picks).extracting(PickEvent::getGroupId).containsExactly("G1", "G1", "G1", "c2_receipt");
        assertThat(picks).extracting(PickEvent::isDecision).containsExactly(false, true, true, true);
        assertThat(picks).extracting(PickEvent::isEffective).containsExactly(false, false, true, true);
        assertThat(picks).extracting(PickEvent::isFirstDecision).containsExactly(false, true, false, true);
        assertThat(picks.get(1).getAt()).isEqualTo(Instant.parse("2026-10-02T03:00:03Z"));
        assertThat(picks.get(0).getAt()).isNull();
        assertThat(picks).allSatisfy(p -> assertThat(p.getBuildVersion()).isEqualTo(version));

        // ChoiceEvent: đúng một dòng mỗi nhóm = lựa chọn có hiệu lực.
        List<ChoiceEvent> choices = rows(ChoiceEvent.class, slot);
        assertThat(choices).extracting(ChoiceEvent::getSceneId, ChoiceEvent::getChoiceId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("c1_revise", "C_REV"),
                        org.assertj.core.groups.Tuple.tuple("c2_receipt", "SHARED"));
        assertThat(choices).filteredOn(c -> c.getSceneId().equals("c1_revise")).singleElement()
                .satisfies(c -> {
                    assertThat(c.getEffects()).contains("\"SAVING\":20");
                    assertThat(c.getChosenAt()).isEqualTo(Instant.parse("2026-10-02T03:00:04Z"));
                });

        // Chỉ số: 50 + C_REV (+20); A (+10) và INFO deferLedger (+40) không cộng. GOAL 50 + SHARED (+5).
        var run = runStateRepo.findBySlot_Id(slot).orElseThrow();
        assertThat(run.getSaving()).isEqualTo(70);
        assertThat(run.getGoal()).isEqualTo(55);

        assertThat(rows(InspectEvent.class, slot)).singleElement()
                .satisfies(i -> assertThat(i.getInspectId()).isEqualTo("card1"));

        // Mini-game chấm mọi câu; seq của ô nhập đầu tiên câu đọc; câu không ô nào gửi → seq null.
        List<MiniGameResult> minis = rows(MiniGameResult.class, slot);
        assertThat(minis).extracting(MiniGameResult::getItemId).containsExactlyInAnyOrder("budget", "evidence", "Q_RATE");
        assertThat(minis).anySatisfy(m -> {
            assertThat(m.getItemId()).isEqualTo("budget");
            assertThat(m.isCorrect()).isTrue();   // phone B
            assertThat(m.getSeq()).isEqualTo(6);
            assertThat(m.getPlayedAt()).isEqualTo(Instant.parse("2026-10-02T03:00:06Z"));
        }).anySatisfy(m -> {
            assertThat(m.getItemId()).isEqualTo("Q_RATE");
            assertThat(m.getSeq()).isNull();
        });
    }

    // ------------------------------------------------------------------ 2. preserveFirstChoiceStats
    @Test
    void preserveFirstChoiceStatsCountsFirstDecision() throws Exception {
        String child = newChild("v2first");
        send(child, batch(1, 1, EMPTY_ROWS)).andExpect(status().isOk());
        passPostTest(child);
        send(child, batch(2, 1, """
                "picks":[{"seq":1,"sceneId":"p1","optionId":"X"},{"seq":2,"sceneId":"p1","optionId":"Y"}],
                "inspections":[],"miniGames":[],"reflections":[]""")).andExpect(status().isOk());
        UUID slot = slotOf(child);

        // Lần chọn đầu X (+10) được cộng; ChoiceEvent vẫn ghi lựa chọn có hiệu lực Y, effects là delta đã cộng.
        assertThat(runStateRepo.findBySlot_Id(slot).orElseThrow().getSaving()).isEqualTo(60);
        assertThat(rows(ChoiceEvent.class, slot)).singleElement().satisfies(c -> {
            assertThat(c.getChoiceId()).isEqualTo("Y");
            assertThat(c.getEffects()).contains("\"SAVING\":10");
        });
        List<PickEvent> picks = rows(PickEvent.class, slot);
        assertThat(picks).filteredOn(PickEvent::isFirstDecision).singleElement()
                .satisfies(p -> assertThat(p.getOptionId()).isEqualTo("X"));
        assertThat(picks).filteredOn(PickEvent::isEffective).singleElement()
                .satisfies(p -> assertThat(p.getOptionId()).isEqualTo("Y"));
    }

    // ------------------------------------------------------------------ 3. deferLedger
    @Test
    void deferLedgerPickIsStoredButNeitherDecisionNorCounted() throws Exception {
        String child = newChild("v2defer");
        send(child, batch(1, 1, """
                "picks":[{"seq":1,"sceneId":"c1","optionId":"INFO"}],
                "inspections":[],"miniGames":[],"reflections":[]""")).andExpect(status().isOk());
        UUID slot = slotOf(child);

        assertThat(rows(PickEvent.class, slot)).singleElement().satisfies(p -> {
            assertThat(p.isDecision()).isFalse();
            assertThat(p.isEffective()).isFalse();
            assertThat(p.isFirstDecision()).isFalse();
        });
        assertThat(rows(ChoiceEvent.class, slot)).isEmpty();
        assertThat(runStateRepo.findBySlot_Id(slot).orElseThrow().getSaving()).isEqualTo(50);
    }

    // ------------------------------------------------------------------ 4. cùng optionId ở hai cảnh
    @Test
    void optionIsCheckedAgainstTheSceneOfTheRow() throws Exception {
        String child = newChild("v2scene");
        // SHARED có ở c2 và c2_receipt; ONLY_RECEIPT chỉ có ở c2_receipt → đứng ở c2 mà bấm nó là sai.
        send(child, batch(1, 1, """
                "picks":[{"seq":1,"sceneId":"c2","optionId":"SHARED"},{"seq":2,"sceneId":"c2","optionId":"ONLY_RECEIPT"}],
                "inspections":[],"miniGames":[],"reflections":[]"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("picks[1].optionId")));
        // Cảnh không phải choice cũng sai, kể cả cảnh phản tư.
        send(child, batch(1, 1, """
                "picks":[{"seq":1,"sceneId":"r1","optionId":"SHARED"}],
                "inspections":[],"miniGames":[],"reflections":[]"""))
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("picks[0].sceneId")));
        assertThat(runStateRepo.findBySlot_Id(slotOf(child))).isEmpty();

        send(child, batch(1, 1, """
                "picks":[{"seq":1,"sceneId":"c2_receipt","optionId":"ONLY_RECEIPT"}],
                "inspections":[],"miniGames":[],"reflections":[]""")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 5. inspections
    @Test
    void inspectionsKeepReopeningsAndRejectWrongCard() throws Exception {
        String child = newChild("v2insp");
        send(child, batch(1, 1, """
                "picks":[],
                "inspections":[{"seq":1,"sceneId":"i1","inspectId":"card1"},{"seq":2,"sceneId":"i1","inspectId":"card2"}],
                "miniGames":[],"reflections":[]"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("inspections[1].inspectId")));

        send(child, batch(1, 1, """
                "picks":[],
                "inspections":[{"seq":1,"sceneId":"i1","inspectId":"card1"},{"seq":3,"sceneId":"i2","inspectId":"card2"},
                               {"seq":5,"sceneId":"i1","inspectId":"card1","at":"2026-10-02T03:00:05Z"}],
                "miniGames":[],"reflections":[]""")).andExpect(status().isOk());
        assertThat(rows(InspectEvent.class, slotOf(child))).extracting(InspectEvent::getSeq)
                .containsExactlyInAnyOrder(1, 3, 5);
    }

    // ------------------------------------------------------------------ 6. seq
    @Test
    void duplicateSeqIsRejectedExceptRowsOfOneMiniGame() throws Exception {
        String child = newChild("v2seq");
        send(child, batch(1, 1, """
                "picks":[{"seq":4,"sceneId":"c1","optionId":"A"},{"seq":4,"sceneId":"c1","optionId":"B"}],
                "inspections":[],"miniGames":[],"reflections":[]"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("picks[1].seq")));
        // Ô mini-game không được dùng chung seq với dòng khác.
        send(child, batch(1, 1, """
                "picks":[{"seq":4,"sceneId":"c1","optionId":"A"}],"inspections":[],
                "miniGames":[{"seq":4,"gameId":"SHOP_V3","itemId":"phone","chosen":"A"}],"reflections":[]"""))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("miniGames[0].seq")));
        // seq phải nguyên >= 1.
        send(child, batch(1, 1, """
                "picks":[{"seq":0,"sceneId":"c1","optionId":"A"}],"inspections":[],"miniGames":[],"reflections":[]"""))
                .andExpect(jsonPath("$.code").value(1001));
        // Trùng (gameId, itemId) → 1001.
        send(child, batch(1, 1, """
                "picks":[],"inspections":[],"miniGames":[
                  {"seq":2,"gameId":"SHOP_V3","itemId":"phone","chosen":"A"},
                  {"seq":3,"gameId":"SHOP_V3","itemId":"phone","chosen":"B"}],"reflections":[]"""))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("miniGames[1].itemId")));
        // Ô không câu grade nào đọc → 7002.
        send(child, batch(1, 1, """
                "picks":[],"inspections":[],"miniGames":[{"seq":2,"gameId":"SHOP_V3","itemId":"junk","chosen":1}],
                "reflections":[]"""))
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("miniGames[0].itemId")));

        send(child, batch(1, 1, """
                "picks":[],"inspections":[],"miniGames":[
                  {"seq":2,"gameId":"SHOP_V3","itemId":"phone","chosen":"A"},
                  {"seq":2,"gameId":"SHOP_V3","itemId":"cancelDay","chosen":0}],"reflections":[]"""))
                .andExpect(status().isOk());
        assertThat(rows(MiniGameResult.class, slotOf(child))).filteredOn(m -> m.getItemId().equals("budget"))
                .singleElement().satisfies(m -> assertThat(m.getSeq()).isEqualTo(2));
    }

    // ------------------------------------------------------------------ 7. reflections
    @Test
    void reflectionsNeedDeclaredInputsAndKnownOptions() throws Exception {
        String child = newChild("v2refl");
        send(child, batch(1, 1, refl("{\"seq\":1,\"sceneId\":\"r0\",\"answer\":{}}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("reflections[0].sceneId")));
        send(child, batch(1, 1, refl("{\"seq\":1,\"sceneId\":\"r1\",\"answer\":{\"method\":\"magic\"}}")))
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("reflections[0].answer.method")));
        send(child, batch(1, 1, refl("{\"seq\":1,\"sceneId\":\"r1\",\"answer\":{\"other\":\"net\"}}")))
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(containsString("reflections[0].answer.other")));
        // multi phải là mảng, single phải là chuỗi.
        send(child, batch(1, 1, refl("{\"seq\":1,\"sceneId\":\"r1\",\"answer\":{\"selected\":\"fees\"}}")))
                .andExpect(jsonPath("$.code").value(7002));
        // Giá trị không phải chuỗi / mảng chuỗi là sai kiểu hợp đồng → 1001.
        send(child, batch(1, 1, refl("{\"seq\":1,\"sceneId\":\"r1\",\"answer\":{\"method\":1}}")))
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(runStateRepo.findBySlot_Id(slotOf(child))).isEmpty();

        send(child, batch(1, 1, refl("{\"seq\":9,\"sceneId\":\"r1\",\"answer\":{\"selected\":[\"deadline\",\"fees\"],"
                + "\"method\":\"net\"},\"at\":\"2026-10-02T03:00:09Z\"}")))
                .andExpect(status().isOk());
        assertThat(rows(ReflectionAnswer.class, slotOf(child))).singleElement().satisfies(r -> {
            assertThat(r.getSceneId()).isEqualTo("r1");
            assertThat(r.getSeq()).isEqualTo(9);
            assertThat(r.getAnswer()).isEqualTo("{\"selected\":[\"deadline\",\"fees\"],\"method\":\"net\"}");
        });
    }

    // ------------------------------------------------------------------ 8. trường lạ, schema khác 2
    @Test
    void unknownFieldsRejectedOnlyOnSchemaTwo() throws Exception {
        String child = newChild("v2strict");
        send(child, batch(1, 1, EMPTY_ROWS + ",\"stats\":{\"SAVING\":95}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("stats")));
        send(child, batch(1, 1, """
                "picks":[{"seq":1,"sceneId":"c1","optionId":"A","revised":true}],
                "inspections":[],"miniGames":[],"reflections":[]"""))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("picks[0].revised")));
        send(child, "{\"schema\":3,\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + version + "\"," + EMPTY_ROWS + "}")
                .andExpect(jsonPath("$.code").value(1001));
        send(child, "{\"schema\":2,\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + version + "\","
                + "\"picks\":[],\"inspections\":[],\"miniGames\":[]}")   // thiếu reflections
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("reflections")));
        assertThat(runStateRepo.findBySlot_Id(slotOf(child))).isEmpty();

        // Cùng trường lạ trong lô bản 1 (không có schema) → bỏ qua, 200.
        send(child, "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + version + "\",\"stats\":{\"SAVING\":95},"
                + "\"choices\":[{\"sceneId\":\"c1\",\"optionId\":\"A\",\"revised\":true}],\"miniGames\":[]}")
                .andExpect(status().isOk());
        assertThat(runStateRepo.findBySlot_Id(slotOf(child)).orElseThrow().getSaving()).isEqualTo(60);
        assertThat(rows(PickEvent.class, slotOf(child))).isEmpty();
    }

    // ------------------------------------------------------------------ 9. gửi lại
    @Test
    void resendingSameBatchIsNoop() throws Exception {
        String child = newChild("v2again");
        String body = batch(1, 1, """
                "picks":[{"seq":1,"sceneId":"c1","optionId":"A"},{"seq":2,"sceneId":"c1","optionId":"B"}],
                "inspections":[{"seq":3,"sceneId":"i1","inspectId":"card1"}],
                "miniGames":[{"seq":4,"gameId":"SHOP_V3","itemId":"phone","chosen":"B"}],
                "reflections":[{"seq":5,"sceneId":"r1","answer":{"method":"net"}}]""");
        send(child, body).andExpect(status().isOk());
        send(child, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.chaptersDone.length()").value(1))
                .andExpect(jsonPath("$.result.continueChapter").value(2));
        UUID slot = slotOf(child);
        assertThat(rows(PickEvent.class, slot)).hasSize(2);
        assertThat(rows(InspectEvent.class, slot)).hasSize(1);
        assertThat(rows(ReflectionAnswer.class, slot)).hasSize(1);
        assertThat(rows(ChoiceEvent.class, slot)).hasSize(1);
        assertThat(rows(MiniGameResult.class, slot)).hasSize(3);
        assertThat(runStateRepo.findBySlot_Id(slot).orElseThrow().getSaving()).isEqualTo(45);   // 50 + B (-5) một lần
    }

    // ------------------------------------------------------------------ 11. giới hạn
    @Test
    void limitsGive413() throws Exception {
        String child = newChild("v2big");
        StringBuilder picks = new StringBuilder();
        for (int i = 1; i <= 501; i++) {
            picks.append(i == 1 ? "" : ",").append("{\"seq\":").append(i).append(",\"sceneId\":\"c1\",\"optionId\":\"A\"}");
        }
        send(child, batch(1, 1, "\"picks\":[" + picks + "],\"inspections\":[],\"miniGames\":[],\"reflections\":[]"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(2003));
        StringBuilder refl = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            refl.append(i == 1 ? "" : ",").append("{\"seq\":").append(i).append(",\"sceneId\":\"r").append(i).append("\",\"answer\":{}}");
        }
        send(child, batch(1, 1, "\"picks\":[],\"inspections\":[],\"miniGames\":[],\"reflections\":[" + refl + "]"))
                .andExpect(jsonPath("$.code").value(2003));
        // Body > 128 KB → 413 trước khi đọc lô; bản 1 vẫn giữ trần 64 KB.
        String pad = "x".repeat(130 * 1024);
        send(child, "{\"schema\":2,\"releaseVersion\":\"" + pad + "\"}").andExpect(status().isPayloadTooLarge());
        send(child, "{\"chapter\":1,\"attemptIndex\":1,\"releaseVersion\":\"" + "x".repeat(70 * 1024)
                + "\",\"choices\":[],\"miniGames\":[]}").andExpect(status().isPayloadTooLarge());
        // 500 picks vẫn nhận (đúng trần).
        picks.setLength(0);
        for (int i = 1; i <= 500; i++) {
            picks.append(i == 1 ? "" : ",").append("{\"seq\":").append(i).append(",\"sceneId\":\"c1\",\"optionId\":\"")
                    .append(i % 2 == 0 ? "A" : "B").append("\"}");
        }
        send(child, batch(1, 1, "\"picks\":[" + picks + "],\"inspections\":[],\"miniGames\":[],\"reflections\":[]"))
                .andExpect(status().isOk());
        assertThat(rows(PickEvent.class, slotOf(child))).hasSize(500);
        assertThat(runStateRepo.findBySlot_Id(slotOf(child)).orElseThrow().getSaving()).isEqualTo(60);   // seq 500 = A
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    private static final String EMPTY_ROWS = "\"picks\":[],\"inspections\":[],\"miniGames\":[],\"reflections\":[]";

    private String batch(int chapter, int attempt, String rows) throws Exception {
        return "{\"schema\":2,\"chapter\":" + chapter + ",\"attemptIndex\":" + attempt + ",\"releaseVersion\":\""
                + releaseVersion() + "\"," + rows + ",\"finishedAt\":\"2026-10-02T03:10:00Z\"}";
    }

    private static String refl(String row) {
        return "\"picks\":[],\"inspections\":[],\"miniGames\":[],\"reflections\":[" + row + "]";
    }

    private String releaseVersion() throws Exception {
        if (version == null) {
            version = ContentTestSupport.publishFresh(mvc, admin(), new ContentTestSupport.Repos(
                    releaseChapterRepo, releaseRepo, draftRepo, chapterRepo), Map.of("CH01", chapterOne(), "CH02", chapterTwo()),
                    "play v2 test");
        }
        return version;
    }

    private static String chapterOne() {
        return """
                {"id":"chapter-1","title":"Chuong 1 cho test play v2","version":"1.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50,"RISK":30,"GOAL":50,"WEALTH":0},
                 "scenes":[
                   {"id":"intro","type":"dialogue","text":"Chao","nextSceneId":"i1"},
                   {"id":"i1","type":"inspect","text":"Ho so","inspect":{"id":"card1","cards":[{"id":"x","text":"Lai"}]},"nextSceneId":"i2"},
                   {"id":"i2","type":"inspect","text":"Ho so 2","inspect":{"id":"card2","cards":[{"id":"y","text":"Phi"}]},"nextSceneId":"c1"},
                   {"id":"c1","type":"choice","choiceGroup":"G1","options":[
                     {"id":"A","text":"Tiet kiem","deltaStats":{"SAVING":10},"nextSceneId":"c1_revise"},
                     {"id":"B","text":"Tieu","deltaStats":{"SAVING":-5},"nextSceneId":"c1_revise"},
                     {"id":"INFO","text":"Doc them","deferLedger":true,"deltaStats":{"SAVING":40},"nextSceneId":"c1"}]},
                   {"id":"c1_revise","type":"choice","choiceGroup":"G1","options":[
                     {"id":"A_REV","text":"Giu","deltaStats":{"SAVING":3},"nextSceneId":"c2"},
                     {"id":"C_REV","text":"Doi","deltaStats":{"SAVING":20},"nextSceneId":"c2"}]},
                   {"id":"c2","type":"choice","options":[
                     {"id":"SHARED","text":"Giu hoa don","deltaStats":{"GOAL":5},"nextSceneId":"c2_receipt"}]},
                   {"id":"c2_receipt","type":"choice","options":[
                     {"id":"SHARED","text":"Giu hoa don","deltaStats":{"GOAL":5},"nextSceneId":"r0"},
                     {"id":"ONLY_RECEIPT","text":"Xem hoa don","nextSceneId":"r0"}]},
                   {"id":"r0","type":"reflection","prompt":"Nghi lai","nextSceneId":"r1"},
                   {"id":"r1","type":"reflection","prompt":"Chon","nextSceneId":"game-3",
                    "inputs":[
                      {"id":"selected","kind":"multi","options":[{"id":"deadline","label":"Ngay"},{"id":"fees","label":"Phi"}]},
                      {"id":"method","kind":"single","options":[{"id":"net","label":"Rong"},{"id":"gross","label":"Gop"}]}]},
                   {"id":"game-3","type":"minigame","knowledgeUnits":["D03.04"],"nextSceneId":"end",
                    "game":{"id":"SHOP_V3","config":{"criteria":{
                      "budget":{"title":"Ngan sach","grade":{"id":"budget","rule":{"any":[{"input":"phone","eq":"B"},
                        {"all":[{"input":"phone","eq":"A"},{"input":"cancelDay","min":1,"max":29}]}]}}},
                      "evidence":{"title":"Bang chung","grade":{"id":"evidence","rule":{"all":[{"input":"phone","eq":"A"},
                        {"input":"inspected","has":["A:app"]}]}}}},
                     "questions":[{"id":"Q_RATE","grade":{"concept":"D03.09","rule":{"eq":5}}}]}}},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """;
    }

    /** CH02 bật preserveFirstChoiceStats: chỉ số theo lần chọn đầu. */
    private static String chapterTwo() {
        return """
                {"id":"chapter-2","title":"Chuong 2 cho test play v2","version":"1.0.0",
                 "metadata":{"preserveFirstChoiceStats":true},
                 "scenes":[
                   {"id":"p1","type":"choice","options":[
                     {"id":"X","text":"Mot","deltaStats":{"SAVING":10},"nextSceneId":"end2"},
                     {"id":"Y","text":"Hai","deltaStats":{"SAVING":-10},"nextSceneId":"end2"}]},
                   {"id":"end2","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """;
    }

    /** Mọi dòng của một slot trong bảng type (bảng có cột slot) — repository sinh tự động không có truy vấn riêng. */
    private <T> List<T> rows(Class<T> type, UUID slotId) {
        return new java.util.ArrayList<>(em.createQuery("select x from " + type.getSimpleName() + " x where x.slot.id = :s", type)
                .setParameter("s", slotId).getResultList());
    }

    /** BR-53: chương vừa xong có đề hậu trắc thì nộp bài trắng để mở chương kế tiếp (như PlayFlowTest). */
    private void passPostTest(String child) throws Exception {
        String state = mvc.perform(get("/api/play/state").header("Authorization", "Bearer " + child))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var required = new com.fasterxml.jackson.databind.ObjectMapper().readTree(state)
                .path("result").path("postTestRequired");
        if (required.isObject()) {
            mvc.perform(post("/api/learner/quizzes/" + required.path("quizId").asText() + "/submit")
                            .header("Authorization", "Bearer " + child)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[]}"))
                    .andExpect(status().isOk());
        }
    }

    private ResultActions send(String childToken, String body) throws Exception {
        return mvc.perform(post("/api/play/chapter")
                .header("Authorization", "Bearer " + childToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static org.springframework.test.web.servlet.result.ContentResultMatchers content() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.content();
    }

    private static UUID slotOf(String jwt) {
        return UUID.fromString(subject(jwt));
    }

    // ------------------------------------------------------------------ trẻ mới (khuôn PlayFlowTest)
    private String newChild(String prefix) throws Exception {
        releaseVersion();
        String parent = registerAndVerify(prefix + "-" + System.nanoTime() + "@test.local");
        grant(parent);
        String group = mvc.perform(post("/api/groups").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nha " + prefix + "\",\"context\":\"FAMILY\",\"plannedSize\":40}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String slotBody = mvc.perform(post("/api/slots").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + find(group, "\"id\":\"([0-9a-f-]+)\"") + "\",\"displayName\":\"Be "
                                + prefix + "\",\"pin\":\"123456\",\"badge\":\"nhi dong\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String login = mvc.perform(post("/api/slots/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + find(slotBody, "\"code\":\"([A-Z0-9]+)\"") + "\",\"pin\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return find(login, "\"accessToken\":\"([^\"]+)\"");
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("playv2-admin-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin Play v2");
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

    private void grant(String jwt) {
        Account a = accountRepo.findById(UUID.fromString(subject(jwt))).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(PlanKind.PARENT);
        e.setStartsOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
        e.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(FIXED_CLOCK));
        entitlementRepo.save(e);
    }

    private String registerAndVerify(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"phone\":\"0900000000\","
                                + "\"password\":\"matkhau123\",\"displayName\":\"Nguyen Test\"}"))
                .andExpect(status().isOk());
        assertThat(mailCatcher.sent).isNotEmpty();
        OutgoingMail last = mailCatcher.sent.get(mailCatcher.sent.size() - 1);
        String otp = find(last.body(), ">(\\d{6})</p>");
        String body = mvc.perform(post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"otp\":\"" + otp + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return find(body, "\"accessToken\":\"([^\"]+)\"");
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as(regex + " trong " + text).isTrue();
        return m.group(1);
    }

    private static String subject(String jwt) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return find(payload, "\"sub\":\"([^\"]+)\"");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }

        @Bean
        AuthFlowTest.MailCatcher thu() {
            return new AuthFlowTest.MailCatcher();
        }
    }
}
