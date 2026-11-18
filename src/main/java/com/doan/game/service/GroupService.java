package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Vòng đời nhóm: mở nhóm gia đình (gói PARENT) hoặc lớp (gói TEACHER), kết thúc nhóm, đếm chỗ trống;
 * giáo viên xác nhận đã có đồng ý của phụ huynh.
 *
 * 17/11/2026: hạn mức slot nằm trên TÀI KHOẢN (SlotQuotaService) chứ không chép 4/40 vào nhóm nữa;
 * một tài khoản tối đa một nhóm FAMILY đang mở, còn CLASS mở được nhiều lớp.
 *
 * Bảng phụ trách: LearnerGroup
 */
public interface GroupService {

    LearnerGroupResponse openGroup(UUID ownerId, CreateGroupRequest req);

    /**
     * Các nhóm của NGƯỜI GỌI: nhóm đang mở đứng trước, nhóm đã đóng theo sau — FE chỉ gọi một
     * đường là thấy đủ. slotUsed đếm slot ACTIVE. 07/10 (Kidz chốt phạm vi PR 2).
     */
    java.util.List<LearnerGroupResponse> getMyGroups(UUID callerId);

    /** Slot trong một nhóm (mọi trạng thái để FE thấy cả ARCHIVED / WIPED), chỉ chủ nhóm (3004). */
    java.util.List<SlotResponse> listSlots(UUID callerId, UUID groupId);

    /**
     * Chủ nhóm CLASS xác nhận đã có đồng ý của phụ huynh (consentConfirmedAt = now) — bắt buộc
     * trước khi mở slot trong nhóm CLASS (5003); FAMILY không cần. 07/10, Hưng chốt.
     */
    void confirmConsent(UUID callerId, UUID groupId);

    /**
     * Kết thúc nhóm (giáo viên gọi là kết thúc lớp): closedAt = now, openContext = NULL, mọi slot ACTIVE
     * → ARCHIVED. KHÔNG xoá gì, KHÔNG có API khôi phục (Hưng chốt 07/10). 17/11/2026: KHÔNG trả lượt
     * slot về ví — slot đã mở là tiêu hao của kỳ. Chỉ chủ nhóm (3004).
     */
    void closeGroup(UUID callerId, UUID groupId);

    /**
     * Chỗ còn mở được của nhóm = min(ví slot còn lại, sĩ số còn trống); nhóm đã đóng → 0. Chỉ chủ nhóm
     * (3004). 17/11/2026.
     */
    int countFreeSlots(UUID callerId, UUID groupId);

    /**
     * Sửa sĩ số lớp (17/11/2026, chỉ CLASS đang mở): >= số slot ACTIVE và <= ACTIVE + ví còn lại.
     * Trả về nhóm kèm slotUsed mới.
     */
    LearnerGroupResponse updatePlannedSize(UUID callerId, UUID groupId, UpdateGroupRequest req);

    /**
     * 16/10 (quiz.md v2, Hưng chốt): giáo viên bật / tắt hậu trắc cho LỚP — postTestEnabledAt = now hoặc NULL.
     * Chỉ nhóm CLASS đang mở (8008) của người gọi (3004); bật cần gói TEACHER còn hạn (3005), tắt chỉ cần
     * là chủ nhóm. Bật thì mọi em thấy bài hậu trắc của mỗi chương đã xong (QuizService tự tạo đề). KHUNG.
     */
    void setPostTest(UUID callerId, UUID groupId, boolean enabled);

}
