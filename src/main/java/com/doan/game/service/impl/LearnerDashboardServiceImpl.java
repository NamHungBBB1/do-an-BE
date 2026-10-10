package com.doan.game.service.impl;

import com.doan.game.DTO.response.LearnerDashboardResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.service.LearnerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Ruột dashboard người học đợt 1 (08/10): chỉ khối learner là có dữ liệu, còn lại mặc định
 * đúng bảng "Theo đợt" trong contract — hình dạng response giữ nguyên qua các đợt.
 */
@Service
@RequiredArgsConstructor
public class LearnerDashboardServiceImpl implements LearnerDashboardService {

    private final LearnerSlotRepository slotRepo;
    private final GameReleaseRepository releaseRepo;
    private final ReleaseChapterRepository releaseChapterRepo;

    @Override
    @Transactional(readOnly = true)
    public LearnerDashboardResponse getDashboard(UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        // Cùng luật loginSlot (08/10): WIPED / không có → 3007, ARCHIVED → 3009. Token SLOT vẫn
        // sống sau khi slot bị trả / bị xoá (RevocationAwareJwtDecoder bỏ qua typ=SLOT), nên chặn
        // ở đây chứ không phải ở cửa Security.
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }

        LearnerGroup group = slot.getGroup();
        String groupContext = group == null || group.getContext() == null ? null : group.getContext().name();
        LearnerDashboardResponse.Learner learner = new LearnerDashboardResponse.Learner(
                slot.getId(), slot.getDisplayName(), slot.getBadge(), groupContext,
                group == null ? null : group.getName());

        // totalChapters = số chương của bản phát hành hiện tại (có từ 09/10); chưa phát hành → null.
        // Tiến độ chơi chưa có API lưu → mặc định, continueChapter = 1.
        Integer totalChapters = releaseRepo.findFirstByIsCurrentTrue()
                .map(r -> releaseChapterRepo.findByRelease_IdOrderByChapter_ChapterNumberAsc(r.getId()).size())
                .orElse(null);
        LearnerDashboardResponse.Progress progress = new LearnerDashboardResponse.Progress(
                totalChapters, 0, List.of(), 1, null);

        // Quiz chỉ cho CLASS; FAMILY nhận null (Hưng chốt 08/10) — FE ẩn hẳn khối quiz.
        LearnerDashboardResponse.Quizzes quizzes =
                group != null && group.getContext() == LearningContext.CLASS
                        ? new LearnerDashboardResponse.Quizzes(List.of(), List.of())
                        : null;

        return new LearnerDashboardResponse(learner, progress, quizzes, List.of(), List.of());
    }

}
