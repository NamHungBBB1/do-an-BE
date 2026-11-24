package com.doan.game.service.impl;

import com.doan.game.DTO.response.NotificationListResponse;
import com.doan.game.DTO.response.NotificationReadAllResponse;
import com.doan.game.DTO.response.NotificationReadResponse;
import com.doan.game.DTO.response.NotificationResponse;
import com.doan.game.DTO.response.UnreadCountResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Notification;
import com.doan.game.entity.Quiz;
import com.doan.game.entity.QuizResult;
import com.doan.game.entity.RunState;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.NotificationType;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.NotificationRepository;
import com.doan.game.repository.QuizRepository;
import com.doan.game.repository.QuizResultRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chuông thông báo học tập (24/11/2026).
 *
 * Hai kiểu dòng:
 * — PHỤ HUYNH: một dòng / một sự kiện / một trẻ, gắn slotId.
 * — GIÁO VIÊN: một dòng gộp / lớp + chương hoặc đề, slotId NULL; có em nộp thêm thì UPDATE dòng cũ
 *   (đặt readAt = NULL để nổi lại là chưa đọc), không insert thêm.
 *
 * Không lưu tên trẻ và không lưu sẵn title/message: xoá slot là xoá tên thật, nên câu chữ ghép lúc
 * đọc từ dữ liệu hiện tại. Số "12/40 em" cũng đếm lúc đọc — không có cột đếm để lệch khi em bị xoá.
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final String ACTION_LEARNER_REPORT = "OPEN_LEARNER_REPORT";
    private static final String ACTION_GROUP_REPORT = "OPEN_GROUP_REPORT";
    private static final String ACTION_QUIZ_RESULTS = "OPEN_QUIZ_RESULTS";
    private static final String ACTION_LEARNER_LIST = "OPEN_LEARNER_LIST";

    private final NotificationRepository notificationRepo;
    private final LearnerSlotRepository slotRepo;
    private final LearnerGroupRepository groupRepo;
    private final RunStateRepository runStateRepo;
    private final QuizResultRepository quizResultRepo;
    private final QuizRepository quizRepo;
    private final Clock clock;

    // ------------------------------------------------------------------ đọc

    @Override
    @Transactional(readOnly = true)
    public NotificationListResponse list(UUID accountId, int page, int size, boolean unreadOnly) {
        if (page < 0) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "page phải ≥ 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "size phải từ 1 đến " + MAX_PAGE_SIZE);
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<Notification> rows = unreadOnly
                ? notificationRepo.findByRecipient_IdAndReadAtIsNull(accountId, pageable)
                : notificationRepo.findByRecipient_Id(accountId, pageable);
        long unread = notificationRepo.countByRecipient_IdAndReadAtIsNull(accountId);
        // Bộ đếm dùng chung trong một trang: 20 dòng gộp cùng lớp không đếm lại 20 lần.
        Map<String, Long> counts = new HashMap<>();
        List<NotificationResponse> items = rows.getContent().stream()
                .map(n -> toResponse(n, counts))
                .toList();
        return new NotificationListResponse(items, rows.getTotalElements(), rows.getTotalPages(),
                rows.getNumber(), rows.getSize(), unread);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID accountId) {
        return new UnreadCountResponse(notificationRepo.countByRecipient_IdAndReadAtIsNull(accountId));
    }

    @Override
    @Transactional
    public NotificationReadResponse markRead(UUID accountId, UUID notificationId) {
        Notification n = notificationRepo.findByIdAndRecipient_Id(notificationId, accountId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now(clock));
            notificationRepo.save(n);
        }
        return new NotificationReadResponse(n.getId(), true, n.getReadAt());
    }

    @Override
    @Transactional
    public NotificationReadAllResponse markAllRead(UUID accountId) {
        int updated = notificationRepo.markAllRead(accountId, Instant.now(clock));
        long unread = notificationRepo.countByRecipient_IdAndReadAtIsNull(accountId);
        return new NotificationReadAllResponse(updated, unread);
    }

    // ------------------------------------------------------------------ móc sự kiện

    @Override
    @Transactional
    public void notifyChapterCompleted(LearnerSlot slot, int chapter, int attemptIndex) {
        LearnerGroup group = slot.getGroup();
        if (group == null || group.getOwner() == null) {
            return;
        }
        if (group.getContext() == LearningContext.CLASS) {
            // Dòng gộp của lớp: một chương một dòng, lần chơi lại của một em chỉ cập nhật dòng đó.
            upsertTeacher(group, NotificationType.CHAPTER_COMPLETED, chapter, null,
                    ACTION_LEARNER_LIST, group.getId(),
                    "T:CH:" + group.getId() + ":" + chapter);
        } else {
            // Phụ huynh: lần chơi mới (attemptIndex mới) là một tin mới, đúng như gửi lại lô không trùng.
            insertParent(group, slot, NotificationType.CHAPTER_COMPLETED, chapter, null,
                    ACTION_LEARNER_REPORT, slot.getId(),
                    "P:CH:" + slot.getId() + ":" + chapter + ":" + attemptIndex);
        }
    }

    @Override
    @Transactional
    public void notifyQuizSubmitted(LearnerSlot slot, Quiz quiz) {
        LearnerGroup group = quiz.getGroup() == null ? slot.getGroup() : quiz.getGroup();
        if (group == null || group.getOwner() == null) {
            return;
        }
        if (group.getContext() == LearningContext.CLASS) {
            upsertTeacher(group, NotificationType.QUIZ_SUBMITTED, null, quiz.getId(),
                    ACTION_QUIZ_RESULTS, quiz.getId(),
                    "T:QZ:" + group.getId() + ":" + quiz.getId());
        } else {
            insertParent(group, slot, NotificationType.QUIZ_SUBMITTED, null, quiz.getId(),
                    ACTION_QUIZ_RESULTS, quiz.getId(),
                    "P:QZ:" + slot.getId() + ":" + quiz.getId());
        }
    }

    @Override
    @Transactional
    public void notifyReportReady(LearnerSlot slot, Quiz postTest) {
        LearnerGroup group = postTest.getGroup() == null ? slot.getGroup() : postTest.getGroup();
        if (group == null || group.getOwner() == null) {
            return;
        }
        Integer chapter = postTest.getChapter();
        if (group.getContext() == LearningContext.CLASS) {
            // Dòng gộp trỏ tới báo cáo CỦA NHÓM (GET /api/groups/{id}/report), không phải báo cáo của một slot.
            upsertTeacher(group, NotificationType.REPORT_READY, chapter, postTest.getId(),
                    ACTION_GROUP_REPORT, group.getId(),
                    "T:RP:" + group.getId() + ":" + chapter);
        } else {
            insertParent(group, slot, NotificationType.REPORT_READY, chapter, postTest.getId(),
                    ACTION_LEARNER_REPORT, slot.getId(),
                    "P:RP:" + slot.getId() + ":" + chapter);
        }
    }

    /** Dòng của phụ huynh: một sự kiện một dòng; gửi lại thì thôi. */
    private void insertParent(LearnerGroup group, LearnerSlot slot, NotificationType type,
                              Integer chapter, UUID quizId, String actionType, UUID resourceId,
                              String eventKey) {
        if (notificationRepo.findByEventKey(eventKey).isPresent()) {
            return;
        }
        Instant now = Instant.now(clock);
        Notification n = new Notification();
        n.setRecipient(group.getOwner());
        n.setType(type);
        n.setSlot(slot);
        n.setGroup(group);
        n.setChapter(chapter);
        n.setQuizId(quizId);
        n.setActionType(actionType);
        n.setActionResourceId(resourceId);
        n.setEventKey(eventKey);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        notificationRepo.save(n);
    }

    /**
     * Dòng gộp của giáo viên: khoá dòng nhóm trước để hai em nộp cùng lúc không cùng thấy "chưa có"
     * rồi cùng insert (đụng UNIQUE event_key thành 500). Có dòng rồi thì chỉ cập nhật mốc và đặt lại
     * chưa đọc.
     */
    private void upsertTeacher(LearnerGroup group, NotificationType type, Integer chapter, UUID quizId,
                               String actionType, UUID resourceId, String eventKey) {
        groupRepo.lockById(group.getId());
        Instant now = Instant.now(clock);
        Optional<Notification> existing = notificationRepo.findByEventKey(eventKey);
        if (existing.isPresent()) {
            Notification n = existing.get();
            n.setUpdatedAt(now);
            n.setReadAt(null);
            notificationRepo.save(n);
            return;
        }
        Notification n = new Notification();
        n.setRecipient(group.getOwner());
        n.setType(type);
        n.setGroup(group);
        n.setChapter(chapter);
        n.setQuizId(quizId);
        n.setActionType(actionType);
        n.setActionResourceId(resourceId);
        n.setEventKey(eventKey);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        notificationRepo.save(n);
    }

    // ------------------------------------------------------------------ ghép câu chữ lúc đọc

    private NotificationResponse toResponse(Notification n, Map<String, Long> counts) {
        LearnerSlot slot = n.getSlot();
        LearnerGroup group = n.getGroup();
        String name = slot == null || slot.getStatus() == SlotStatus.WIPED ? null : slot.getDisplayName();
        String who = name == null ? "Học sinh đã xoá" : name;

        String title;
        String message;
        if (slot != null) {
            // Dòng của phụ huynh: nói về MỘT trẻ.
            switch (n.getType()) {
                case CHAPTER_COMPLETED -> {
                    title = who + " đã hoàn thành Chương " + n.getChapter();
                    message = who + " vừa hoàn thành chương " + n.getChapter() + " trong hành trình FinTeen.";
                }
                case QUIZ_SUBMITTED -> {
                    String quiz = quizTitle(n.getQuizId());
                    title = who + " đã nộp " + quiz;
                    message = "Bài làm của " + who + " đã được ghi nhận. Xem kết quả trong mục bài kiểm tra.";
                }
                default -> {
                    title = "Báo cáo Chương " + n.getChapter() + " của " + who + " đã sẵn sàng";
                    message = "Xem báo cáo học tập của " + who + " ngay.";
                }
            }
        } else {
            // Dòng gộp của giáo viên: nói về CẢ LỚP, tiến độ đếm lúc đọc.
            String className = group == null || group.getName() == null || group.getName().isBlank()
                    ? "Lớp của bạn" : group.getName();
            long total = group == null ? 0
                    : slotRepo.countByGroup_IdAndStatusNot(group.getId(), SlotStatus.WIPED);
            long submitted = submittedCount(n, counts);
            String progress = submitted + "/" + total;
            switch (n.getType()) {
                case CHAPTER_COMPLETED -> {
                    title = "Chương " + n.getChapter() + ": " + progress + " em đã hoàn thành";
                    message = className + " có " + progress + " em hoàn thành chương " + n.getChapter() + ".";
                }
                case QUIZ_SUBMITTED -> {
                    String quiz = quizTitle(n.getQuizId());
                    title = quiz + ": " + progress + " em đã nộp";
                    message = className + " có " + progress + " em nộp bài. Xem kết quả trong mục bài kiểm tra.";
                }
                default -> {
                    title = "Chương " + n.getChapter() + ": " + progress + " em đã có báo cáo";
                    message = className + " có " + progress + " em có báo cáo chương " + n.getChapter() + ".";
                }
            }
        }
        return new NotificationResponse(n.getId(), n.getType(), title, message,
                slot == null ? null : slot.getId(), name,
                group == null ? null : group.getId(), n.getChapter(), n.getQuizId(),
                n.getReadAt() != null, n.getReadAt(), n.getCreatedAt(), n.getUpdatedAt(),
                new NotificationResponse.NotificationAction(n.getActionType(), n.getActionResourceId()));
    }

    /** Số em đã đạt mốc của dòng gộp — chỉ bỏ slot đã xoá để khớp mẫu số. */
    private long submittedCount(Notification n, Map<String, Long> counts) {
        if (n.getGroup() == null) {
            return 0;
        }
        String cacheKey = n.getGroup().getId() + ":" + n.getType() + ":"
                + (n.getType() == NotificationType.CHAPTER_COMPLETED ? n.getChapter() : n.getQuizId());
        Long cached = counts.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long value;
        if (n.getType() == NotificationType.CHAPTER_COMPLETED) {
            int chapter = n.getChapter() == null ? 0 : n.getChapter();
            List<RunState> states = runStateRepo.findBySlot_Group_Id(n.getGroup().getId());
            value = states.stream()
                    .filter(r -> r.getSlot().getStatus() != SlotStatus.WIPED)
                    .filter(r -> ChaptersDone.parse(r.getChaptersDone()).contains(chapter))
                    .count();
        } else {
            // QUIZ_SUBMITTED và REPORT_READY (hậu trắc): đếm bài đã nộp của đúng đề đó.
            List<QuizResult> results = n.getQuizId() == null
                    ? List.of() : quizResultRepo.findByQuiz_Id(n.getQuizId());
            value = results.stream()
                    .filter(r -> r.getSlot().getStatus() != SlotStatus.WIPED)
                    .map(r -> r.getSlot().getId())
                    .distinct()
                    .count();
        }
        counts.put(cacheKey, value);
        return value;
    }

    private String quizTitle(UUID quizId) {
        if (quizId == null) {
            return "bài kiểm tra";
        }
        return quizRepo.findById(quizId)
                .map(Quiz::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .orElse("bài kiểm tra");
    }
}
