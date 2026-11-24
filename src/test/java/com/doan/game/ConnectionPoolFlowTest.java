package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Question;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuestionRepository;
import com.doan.game.repository.QuizAttemptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 25/11 (cạn pool): MỘT request chỉ được giữ MỘT kết nối DB. Pool Hikari = 1, chờ tối đa 2 s: đường nào xin kết nối thứ hai
 * trong lúc còn giữ kết nối đầu sẽ treo 2 s rồi lỗi — production (pool 10) thì ~10 request cùng lúc là treo 30 s rồi 500.
 * DB H2 riêng (pool1) để không đụng dữ liệu các lớp test khác.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.datasource.url=jdbc:h2:mem:pool1;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=2000"})
@AutoConfigureMockMvc
class ConnectionPoolFlowTest {

    private static final Instant START = Instant.parse("2026-10-02T03:00:00Z");
    private static final QuizV3FlowTest.MutableClock CLOCK = new QuizV3FlowTest.MutableClock();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired TransactionTemplate tx;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired LearnerGroupRepository groupRepo;
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired QuizAttemptRepository attemptRepo;

    /**
     * Listener AFTER_COMMIT mở giao dịch REQUIRES_NEW (như ReportSummaryService.onProgress sau khi nộp chương): kết nối của
     * giao dịch vừa commit phải về pool TRƯỚC khi listener chạy (hibernate.connection.handling_mode).
     */
    @Test
    void afterCommitListenerGetsTheOnlyConnection() {
        TransactionTemplate requiresNew = new TransactionTemplate(tx.getTransactionManager());
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicReference<Object> inListener = new AtomicReference<>();
        tx.executeWithoutResult(s -> {
            accountRepo.count();   // giao dịch ngoài thật sự cầm kết nối
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        inListener.set(requiresNew.execute(t -> accountRepo.count()));
                    } catch (RuntimeException e) {
                        inListener.set(e);
                    }
                }
            });
        });
        assertThat(inListener.get()).isInstanceOf(Long.class);
    }

    /** Lượt quá giờ được chốt ở danh sách của em, dashboard, danh sách đề và chi tiết đề — mỗi đường với pool 1 vẫn chạy. */
    @Test
    void finalizeExpiredRunsOutsideTheCallerTransaction() throws Exception {
        CLOCK.set(START);
        Account teacher = teacher();
        LearnerGroup room = classGroup(teacher);
        Question q = poolQuestion();
        String quizId = result(mvc.perform(post("/api/quizzes").with(as(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + room.getId() + "\",\"title\":\"De pool\",\"mode\":\"TEST\","
                        + "\"dueAt\":\"2026-10-03T03:00:00Z\",\"durationMinutes\":1,\"questionIds\":[\""
                        + q.getId() + "\"]}"))
                .andExpect(status().isOk())).path("id").asText();
        mvc.perform(post("/api/quizzes/" + quizId + "/publish").with(as(teacher))).andExpect(status().isOk());

        String[] paths = {"/api/learner/quizzes", "/api/learner/dashboard",
                "/api/quizzes?groupId=" + room.getId(), "/api/quizzes/" + quizId};
        for (int i = 0; i < paths.length; i++) {
            LearnerSlot kid = slot(room, "HS " + i);
            UUID attemptId = UUID.fromString(result(mvc.perform(post("/api/learner/quizzes/" + quizId + "/attempts")
                    .with(as(kid))).andExpect(status().isOk())).path("attemptId").asText());
            CLOCK.set(CLOCK.instant().plusSeconds(120));   // quá deadline (1 phút) + 30 giây trễ
            MockHttpServletRequestBuilder req = get(paths[i]).with(i < 2 ? as(kid) : as(teacher));
            mvc.perform(req).andExpect(status().isOk());
            assertThat(attemptRepo.findById(attemptId).orElseThrow().getSubmittedAt()).as(paths[i]).isNotNull();
        }
    }

    // ------------------------------------------------------------------ dựng dữ liệu (như QuizV3FlowTest)
    private Account teacher() {
        Account account = new Account();
        account.setEmail("pool-" + System.nanoTime() + "@test.local");
        account.setDisplayName("Test pool");
        account.setCreatedAt(START);
        account = accountRepo.save(account);
        Entitlement e = new Entitlement();
        e.setAccount(account);
        e.setKind(PlanKind.TEACHER);
        e.setStartsOn(LocalDate.ofInstant(START, ClockConfig.VN).minusDays(1));
        e.setExpiresOn(LocalDate.ofInstant(START, ClockConfig.VN).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(account);
        e.setReason("test");
        e.setCreatedAt(START);
        entitlementRepo.save(e);
        return account;
    }

    private LearnerGroup classGroup(Account owner) {
        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(LearningContext.CLASS);
        group.setOpenContext(LearningContext.CLASS);
        group.setName("Nhom " + System.nanoTime());
        group.setSlotLimit(40);
        group.setConsentConfirmedAt(START);
        group.setOpenedAt(START);
        return groupRepo.save(group);
    }

    private LearnerSlot slot(LearnerGroup group, String name) {
        LearnerSlot slot = new LearnerSlot();
        slot.setGroup(group);
        slot.setCode(Long.toString(System.nanoTime(), 36).toUpperCase());
        slot.setPinHash("x");
        slot.setDisplayName(name);
        slot.setStatus(SlotStatus.ACTIVE);
        slot.setCreatedAt(START);
        return slotRepo.save(slot);
    }

    private Question poolQuestion() {
        Question question = new Question();
        question.setExternalId("POOL" + System.nanoTime());
        question.setPool("V3");
        question.setStem("Cau " + question.getExternalId());
        question.setOptions("[\"L0\",\"L1\"]");
        question.setCorrectIndex(0);
        question.setKeepOptionOrder(true);
        question.setCreatedAt(START);
        return questionRepo.save(question);
    }

    private static RequestPostProcessor as(Account account) {
        String id = account.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }

    private static RequestPostProcessor as(LearnerSlot slot) {
        String id = slot.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("TYP_SLOT"));
    }

    private static JsonNode result(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("result");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        java.time.Clock testClock() {
            return CLOCK;
        }
    }
}
