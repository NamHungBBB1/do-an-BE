package com.doan.game;

import com.doan.game.DTO.response.ClassChapterReportResponse;
import com.doan.game.DTO.response.ClassCourseReportResponse;
import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizResult;
import com.doan.game.entity.RunState;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.QuizKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API báo cáo lớp theo chương / toàn khoá (26/11, Triệu đề xuất): bốn nhóm tiến độ không trùng em, tổng = sĩ số đang hoạt
 * động (hồ sơ đã xoá không vào mẫu số), count = learners.size(), bài sau chương gộp + phân bố theo em, chỉ chủ nhóm.
 */
@SpringBootTest
class ClassReportApiFlowTest {

    private static final Instant START = Instant.parse("2026-11-26T02:00:00Z");

    @Autowired private ReportService reports;
    @Autowired private AccountRepository accountRepo;
    @Autowired private LearnerGroupRepository groupRepo;
    @Autowired private LearnerSlotRepository slotRepo;
    @Autowired private RunStateRepository runStateRepo;
    @Autowired private QuizRepository quizRepo;
    @Autowired private QuizResultRepository quizResultRepo;

    @Test
    void chapterProgressBucketsArePartitionOfActiveLearners() {
        Account teacher = adult("gv-api");
        LearnerGroup room = group(teacher);
        LearnerSlot k0 = slot(room, "An", "[1]");
        LearnerSlot k1 = slot(room, "Binh", "[1]");
        LearnerSlot k2 = slot(room, "Chi", "[1]");
        LearnerSlot k3 = slot(room, "Dung", null);
        LearnerSlot k4 = slot(room, "Em", "[]");
        LearnerSlot k5 = slot(room, "Giang", "[1,2]");
        LearnerSlot gone = slot(room, "Da xoa", "[1]");
        gone.setStatus(SlotStatus.WIPED);
        slotRepo.save(gone);
        Quiz post1 = postTest(teacher, room, 1);
        Quiz post2 = postTest(teacher, room, 2);
        submit(post1, k0, 6);
        submit(post1, k1, 2);
        submit(post2, k5, 3);
        submit(post1, gone, 6);

        ClassChapterReportResponse ch1 = reports.chapterReport(teacher.getId(), room.getId(), 1);
        assertThat(ch1.classSize()).isEqualTo(6);
        assertThat(ch1.chapter().number()).isEqualTo(1);
        assertThat(ch1.chapter().quizId()).isEqualTo(post1.getId());
        assertPartition(ch1, 6);
        assertThat(names(ch1.progress().submitted())).containsExactlyInAnyOrder("An", "Binh");
        assertThat(names(ch1.progress().finishedNotSubmitted())).containsExactlyInAnyOrder("Chi", "Giang");
        assertThat(names(ch1.progress().inProgress())).containsExactlyInAnyOrder("Dung", "Em");
        assertThat(ch1.progress().notStarted().count()).isZero();

        ClassChapterReportResponse ch2 = reports.chapterReport(teacher.getId(), room.getId(), 2);
        assertPartition(ch2, 6);
        assertThat(names(ch2.progress().submitted())).containsExactly("Giang");
        assertThat(names(ch2.progress().inProgress())).containsExactlyInAnyOrder("An", "Binh", "Chi");
        assertThat(names(ch2.progress().notStarted())).containsExactlyInAnyOrder("Dung", "Em");

        // Lớp học có khối nhận xét (chưa đủ mốc → WAITING); quyết định / nội dung rỗng vì test không có dữ liệu chơi.
        assertThat(ch1.comment().state()).isEqualTo("WAITING");
        assertThat(ch1.storyDecisions()).isEmpty();

        assertThatThrownBy(() -> reports.chapterReport(teacher.getId(), room.getId(), 0))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        Account stranger = adult("la");
        assertThatThrownBy(() -> reports.chapterReport(stranger.getId(), room.getId(), 1))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> reports.courseReport(stranger.getId(), room.getId()))
                .isInstanceOf(AppException.class);
    }

    @Test
    void courseCountsOnlyActiveLearnersAndBandsEachLearnerOnce() {
        Account teacher = adult("gv-khoa");
        LearnerGroup room = group(teacher);
        LearnerSlot k0 = slot(room, "An", "[1]");
        LearnerSlot k1 = slot(room, "Binh", "[1]");
        LearnerSlot k2 = slot(room, "Chi", "[1,2]");
        slot(room, "Dung", null);
        LearnerSlot gone = slot(room, "Da xoa", "[1]");
        gone.setStatus(SlotStatus.WIPED);
        slotRepo.save(gone);
        Quiz post1 = postTest(teacher, room, 1);
        Quiz post2 = postTest(teacher, room, 2);
        submit(post1, k0, 6);      // 100 %
        submit(post1, k1, 2);      // 33 %
        submit(post2, k2, 3);      // 50 %
        submit(post1, gone, 0);    // không tính

        ClassCourseReportResponse r = reports.courseReport(teacher.getId(), room.getId());
        assertThat(r.classSize()).isEqualTo(4);
        assertThat(r.chapters()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(r.chapters().get(0).number()).isEqualTo(1);
        assertThat(r.chapters().get(0).finished()).isEqualTo(3);
        assertThat(r.chapters().get(0).submitted()).isEqualTo(2);
        assertThat(r.chapters().get(1).finished()).isEqualTo(1);
        assertThat(r.chapters().get(1).submitted()).isEqualTo(1);

        assertThat(r.postTest().basedOn()).isEqualTo(3);
        assertThat(r.postTest().average().correct()).isEqualTo(11);
        assertThat(r.postTest().average().total()).isEqualTo(18);
        assertThat(r.postTest().average().rate()).isEqualTo(61);
        assertThat(r.postTest().distribution()).extracting(ClassCourseReportResponse.ScoreBand::count)
                .containsExactly(1, 1, 0, 1);
        assertThat(r.postTest().distribution()).allSatisfy(b -> assertThat(b.learners()).hasSize(b.count()));
        assertThat(r.postTest().distribution().stream().mapToInt(ClassCourseReportResponse.ScoreBand::count).sum())
                .isEqualTo(r.postTest().basedOn());

        assertThat(r.topics()).hasSize(8);
        assertThat(r.topics()).extracting(ClassCourseReportResponse.Topic::code)
                .containsExactly("D01", "D02", "D03", "D04", "D05", "D06", "D07", "D08");
        assertThat(r.topics()).allSatisfy(t -> assertThat(t.hasEnoughData()).isFalse());

        assertThat(r.learners()).hasSize(4);
        assertThat(r.learners()).extracting(ClassCourseReportResponse.Learner::displayName).doesNotContain("Da xoa");
        assertThat(r.learners()).filteredOn(l -> l.slotId().equals(k2.getId())).singleElement().satisfies(l -> {
            assertThat(l.chaptersDone()).isEqualTo(2);
            assertThat(l.postTestsSubmitted()).isEqualTo(1);
            assertThat(l.postTestCorrect()).isEqualTo(3);
            assertThat(l.postTestTotal()).isEqualTo(6);
        });
    }

    // ------------------------------------------------------------------ kiểm chung

    private static void assertPartition(ClassChapterReportResponse r, int classSize) {
        ClassChapterReportResponse.Progress p = r.progress();
        List<ClassChapterReportResponse.Bucket> buckets = List.of(p.submitted(), p.finishedNotSubmitted(),
                p.inProgress(), p.notStarted());
        assertThat(buckets).allSatisfy(b -> assertThat(b.learners()).hasSize(b.count()));
        assertThat(buckets.stream().mapToInt(ClassChapterReportResponse.Bucket::count).sum()).isEqualTo(classSize);
        Set<UUID> seen = new HashSet<>();
        buckets.stream().flatMap(b -> b.learners().stream()).forEach(l -> assertThat(seen.add(l.slotId())).isTrue());
    }

    private static List<String> names(ClassChapterReportResponse.Bucket b) {
        return b.learners().stream().map(GroupReportResponse.LearnerRef::displayName).toList();
    }

    // ------------------------------------------------------------------ dựng dữ liệu

    private void submit(Quiz quiz, LearnerSlot who, int score) {
        QuizResult r = new QuizResult();
        r.setQuiz(quiz);
        r.setSlot(who);
        r.setSubmittedAt(START);
        r.setScore(score);
        quizResultRepo.save(r);
    }

    private Account adult(String tag) {
        Account account = new Account();
        account.setEmail(tag + "-" + System.nanoTime() + "@test.local");
        account.setDisplayName("Test " + tag);
        account.setCreatedAt(START);
        return accountRepo.save(account);
    }

    private LearnerGroup group(Account owner) {
        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(LearningContext.CLASS);
        group.setOpenContext(LearningContext.CLASS);
        group.setName("Lop " + System.nanoTime());
        group.setSlotLimit(40);
        group.setConsentConfirmedAt(START);
        group.setOpenedAt(START);
        return groupRepo.save(group);
    }

    /** chaptersDone null = chưa có RunState (chưa chơi). */
    private LearnerSlot slot(LearnerGroup group, String name, String chaptersDone) {
        LearnerSlot slot = new LearnerSlot();
        slot.setGroup(group);
        slot.setCode(Long.toString(System.nanoTime(), 36).toUpperCase());
        slot.setPinHash("x");
        slot.setDisplayName(name);
        slot.setStatus(SlotStatus.ACTIVE);
        slot.setCreatedAt(START);
        slot = slotRepo.save(slot);
        if (chaptersDone != null) {
            RunState run = new RunState();
            run.setSlot(slot);
            run.setChaptersDone(chaptersDone);
            run.setAttemptIndex(1);
            run.setUpdatedAt(START);
            runStateRepo.save(run);
        }
        return slot;
    }

    /** Đề hậu trắc 6 câu (id giả: chỉ dùng để đếm số câu mỗi em). */
    private Quiz postTest(Account owner, LearnerGroup group, int chapter) {
        Quiz quiz = new Quiz();
        quiz.setOwner(owner);
        quiz.setGroup(group);
        quiz.setTitle("Hậu trắc chương " + chapter);
        quiz.setKind(QuizKind.POST_TEST);
        quiz.setChapter(chapter);
        quiz.setPublishedAt(START);
        List<String> ids = new ArrayList<>();
        Stream.generate(UUID::randomUUID).limit(6).forEach(id -> ids.add("\"" + id + "\""));
        quiz.setQuestionIds("[" + String.join(",", ids) + "]");
        return quizRepo.save(quiz);
    }
}
