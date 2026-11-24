package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizResult;
import com.doan.game.entity.RunState;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.NotificationType;
import com.doan.game.enums.QuizKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.NotificationRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chuông thông báo học tập (24/11/2026): dòng của phụ huynh theo từng trẻ, dòng gộp của giáo viên
 * theo lớp + chương/đề, chống trùng, đọc / đọc tất cả, và không đọc hộ người khác.
 *
 * Dựng thẳng account/nhóm/slot/đề qua repository rồi gọi service móc sự kiện — phần nối vào luồng
 * chơi/nộp bài đã có test riêng (PlayFlowTest, QuizFlowTest) chạy qua đúng các chỗ móc này.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationFlowTest {

    private static final Instant START = Instant.parse("2026-11-24T02:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private NotificationRepository notificationRepo;
    @Autowired
    private AccountRepository accountRepo;
    @Autowired
    private LearnerGroupRepository groupRepo;
    @Autowired
    private LearnerSlotRepository slotRepo;
    @Autowired
    private RunStateRepository runStateRepo;
    @Autowired
    private QuizRepository quizRepo;
    @Autowired
    private QuizResultRepository quizResultRepo;

    // ------------------------------------------------------------------ phụ huynh

    @Test
    void parentGetsOneRowPerChapterRetryDoesNotDuplicateAndReadIsIdempotent() throws Exception {
        Account parent = adult("phu-huynh");
        LearnerGroup family = group(parent, LearningContext.FAMILY);
        LearnerSlot minh = slot(family, "Minh");
        finishChapters(minh, 3);

        notifications.notifyChapterCompleted(minh, 3, 1);
        notifications.notifyChapterCompleted(minh, 3, 1);   // FE gửi lại lô: không thêm dòng

        String listBody = mvc.perform(get("/api/notifications").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(1))
                .andExpect(jsonPath("$.result.items[0].type").value("CHAPTER_COMPLETED"))
                .andExpect(jsonPath("$.result.items[0].slotDisplayName").value("Minh"))
                .andExpect(jsonPath("$.result.items[0].chapter").value(3))
                .andExpect(jsonPath("$.result.items[0].title").value(containsString("Minh")))
                .andExpect(jsonPath("$.result.items[0].title").value(containsString("Chương 3")))
                .andExpect(jsonPath("$.result.items[0].read").value(false))
                .andExpect(jsonPath("$.result.items[0].action.type").value("OPEN_LEARNER_REPORT"))
                .andExpect(jsonPath("$.result.unreadCount").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = com.jayway.jsonpath.JsonPath.read(listBody, "$.result.items[0].id");

        // Lần chơi mới (attemptIndex mới) là tin mới.
        notifications.notifyChapterCompleted(minh, 3, 2);
        assertThat(notificationRepo.findByRecipient_Id(parent.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);

        // Đọc một dòng: gọi lại vẫn 200 và readAt giữ nguyên bản đầu.
        String firstRead = mvc.perform(patch("/api/notifications/" + id + "/read").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.read").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String readAt = com.jayway.jsonpath.JsonPath.read(firstRead, "$.result.readAt");
        mvc.perform(patch("/api/notifications/" + id + "/read").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.readAt").value(readAt));

        mvc.perform(get("/api/notifications/unread-count").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.unreadCount").value(1));

        mvc.perform(patch("/api/notifications/read-all").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.updatedCount").value(1))
                .andExpect(jsonPath("$.result.unreadCount").value(0));

        // unreadOnly lọc đúng.
        mvc.perform(get("/api/notifications").param("unreadOnly", "true").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(0));
    }

    @Test
    void anotherAccountCannotReadOrMarkMyNotification() throws Exception {
        Account parent = adult("chu-so-huu");
        Account stranger = adult("nguoi-la");
        LearnerGroup family = group(parent, LearningContext.FAMILY);
        LearnerSlot kid = slot(family, "An");
        notifications.notifyChapterCompleted(kid, 1, 1);
        UUID id = notificationRepo.findByRecipient_Id(parent.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0).getId();

        // Người lạ: danh sách rỗng, đọc hộ trả 404 1003 (không phải 403 — không lộ là có tồn tại).
        mvc.perform(get("/api/notifications").with(as(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(0));
        mvc.perform(patch("/api/notifications/" + id + "/read").with(as(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void wipedSlotKeepsTheNotificationButHidesTheChildName() throws Exception {
        Account parent = adult("xoa-slot");
        LearnerGroup family = group(parent, LearningContext.FAMILY);
        LearnerSlot kid = slot(family, "Bi");
        notifications.notifyChapterCompleted(kid, 2, 1);

        // Xoá slot = ẩn danh hoá thật: tên về NULL, thông báo cũ vẫn còn nhưng không lộ tên.
        kid.setDisplayName(null);
        kid.setStatus(SlotStatus.WIPED);
        kid.setWipedAt(START);
        slotRepo.save(kid);

        mvc.perform(get("/api/notifications").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(1))
                .andExpect(jsonPath("$.result.items[0].slotDisplayName").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].title").value(containsString("Học sinh đã xoá")));
    }

    // ------------------------------------------------------------------ giáo viên

    @Test
    void teacherGetsOneGroupedRowPerChapterAndItReopensWhenAnotherStudentSubmits() throws Exception {
        Account teacher = adult("giao-vien");
        LearnerGroup room = group(teacher, LearningContext.CLASS);
        LearnerSlot first = slot(room, "An");
        LearnerSlot second = slot(room, "Binh");
        finishChapters(first, 1, 2);

        notifications.notifyChapterCompleted(first, 2, 1);
        notifications.notifyChapterCompleted(first, 1, 1);   // dòng chương 1 mới hơn -> đang đứng đầu

        UUID chapterTwo = notificationRepo.findByRecipient_Id(teacher.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent().stream()
                .filter(n -> n.getChapter() != null && n.getChapter() == 2)
                .findFirst().orElseThrow().getId();
        mvc.perform(patch("/api/notifications/" + chapterTwo + "/read").with(as(teacher)))
                .andExpect(status().isOk());

        finishChapters(second, 1, 2);
        notifications.notifyChapterCompleted(second, 2, 1);

        // Vẫn MỘT dòng cho chương 2, tiến độ cập nhật, dòng nổi lại chưa đọc VÀ nhảy lên đầu danh sách.
        mvc.perform(get("/api/notifications").with(as(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(2))
                .andExpect(jsonPath("$.result.items[0].type").value("CHAPTER_COMPLETED"))
                .andExpect(jsonPath("$.result.items[0].chapter").value(2))
                .andExpect(jsonPath("$.result.items[0].slotId").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].title").value(containsString("2/2 em đã hoàn thành")))
                .andExpect(jsonPath("$.result.items[0].read").value(false))
                .andExpect(jsonPath("$.result.items[0].updatedAt").exists());
    }

    @Test
    void quizSubmittedAndReportReadyGoToTeacherAsGroupedRows() throws Exception {
        Account teacher = adult("gv-de");
        LearnerGroup room = group(teacher, LearningContext.CLASS);
        LearnerSlot kid = slot(room, "Cuong");

        Quiz quiz = quiz(teacher, room, "Kiểm tra chương 1", QuizKind.CLASS_TEST, null);
        quizResultRepo.save(result(quiz, kid));
        notifications.notifyQuizSubmitted(kid, quiz);

        Quiz postTest = quiz(teacher, room, "Hậu trắc chương 2", QuizKind.POST_TEST, 2);
        quizResultRepo.save(result(postTest, kid));
        notifications.notifyReportReady(kid, postTest);

        // Mốc thời gian cố định để thứ tự không phụ thuộc độ phân giải đồng hồ.
        var rows = notificationRepo.findByRecipient_Id(teacher.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        for (var n : rows) {
            Instant at = n.getType() == NotificationType.REPORT_READY
                    ? START.plusSeconds(2) : START.plusSeconds(1);
            n.setCreatedAt(at);
            n.setUpdatedAt(at);
        }
        notificationRepo.saveAll(rows);

        mvc.perform(get("/api/notifications").with(as(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(2))
                .andExpect(jsonPath("$.result.items[0].updatedAt").exists())
                .andExpect(jsonPath("$.result.items[0].type").value("REPORT_READY"))
                .andExpect(jsonPath("$.result.items[0].title").value(containsString("1/1 em đã có báo cáo")))
                .andExpect(jsonPath("$.result.items[0].action.type").value("OPEN_LEARNER_REPORT"))
                .andExpect(jsonPath("$.result.items[1].type").value("QUIZ_SUBMITTED"))
                .andExpect(jsonPath("$.result.items[1].title").value(containsString("Kiểm tra chương 1")))
                .andExpect(jsonPath("$.result.items[1].action.type").value("OPEN_QUIZ_RESULTS"));
    }

    // ------------------------------------------------------------------ phân trang

    @Test
    void invalidPagingIs1001() throws Exception {
        Account parent = adult("phan-trang");
        mvc.perform(get("/api/notifications").param("page", "-1").with(as(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/api/notifications").param("size", "0").with(as(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/api/notifications").param("size", "101").with(as(parent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void listIsNewestFirstAndPaged() throws Exception {
        Account parent = adult("sap-xep");
        LearnerGroup family = group(parent, LearningContext.FAMILY);
        LearnerSlot kid = slot(family, "Dung");
        for (int chapter = 1; chapter <= 3; chapter++) {
            notifications.notifyChapterCompleted(kid, chapter, 1);
        }
        // Mốc thời gian cố định: chương 3 mới nhất, chương 1 cũ nhất.
        var rows = notificationRepo.findByRecipient_Id(parent.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        for (var n : rows) {
            Instant at = START.plusSeconds(n.getChapter());
            n.setCreatedAt(at);
            n.setUpdatedAt(at);
        }
        notificationRepo.saveAll(rows);

        mvc.perform(get("/api/notifications").param("page", "0").param("size", "2").with(as(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items.length()").value(2))
                .andExpect(jsonPath("$.result.items[0].chapter").value(3))
                .andExpect(jsonPath("$.result.items[1].chapter").value(2))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2))
                .andExpect(jsonPath("$.result.unreadCount").value(3));
    }

    // ------------------------------------------------------------------ dựng dữ liệu

    private Account adult(String tag) {
        Account account = new Account();
        account.setEmail(tag + "-" + System.nanoTime() + "@test.local");
        account.setDisplayName("Test " + tag);
        account.setCreatedAt(START);
        return accountRepo.save(account);
    }

    private LearnerGroup group(Account owner, LearningContext context) {
        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(context);
        group.setOpenContext(context);
        group.setName("Nhom " + System.nanoTime());
        group.setSlotLimit(context == LearningContext.CLASS ? 40 : 4);
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

    private void finishChapters(LearnerSlot slot, int... chapters) {
        RunState run = runStateRepo.findBySlot_Id(slot.getId()).orElseGet(RunState::new);
        run.setSlot(slot);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < chapters.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(chapters[i]);
        }
        run.setChaptersDone(json.append(']').toString());
        run.setAttemptIndex(1);
        run.setUpdatedAt(START);
        runStateRepo.save(run);
    }

    private Quiz quiz(Account owner, LearnerGroup group, String title, QuizKind kind, Integer chapter) {
        Quiz quiz = new Quiz();
        quiz.setOwner(owner);
        quiz.setGroup(group);
        quiz.setTitle(title);
        quiz.setKind(kind);
        quiz.setChapter(chapter);
        quiz.setPublishedAt(START);
        return quizRepo.save(quiz);
    }

    private QuizResult result(Quiz quiz, LearnerSlot slot) {
        QuizResult result = new QuizResult();
        result.setQuiz(quiz);
        result.setSlot(slot);
        result.setSubmittedAt(START);
        result.setScore(1);
        return result;
    }

    private static RequestPostProcessor as(Account account) {
        String id = account.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"));
    }
}
