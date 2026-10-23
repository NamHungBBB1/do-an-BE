package com.doan.game.service;

import com.doan.game.DTO.request.AiKeyRequest;
import com.doan.game.DTO.response.AiKeyResponse;

import java.util.List;
import java.util.UUID;

/**
 * Quản lý khoá AI trên trang admin (Hưng 24/10: thử, nhập, xoá, dừng). Cổng /api/admin chỉ ADMIN. Khoá lưu mã hoá AES-GCM;
 * API không bao giờ trả khoá thật. Hàng đợi AI (đợt 2c) đọc các khoá đang bật, ưu tiên nhỏ trước, bỏ khoá đang nghỉ.
 */
public interface AiKeyService {

    List<AiKeyResponse> list();

    /** Nhập rồi thử luôn một lần (kết quả ghi vào lastTest*). Alias trùng → 9002; khoá rỗng / có khoảng trắng → 9001. */
    AiKeyResponse add(UUID callerId, AiKeyRequest req);

    /** Thử lại: OK xoá cờ nghỉ; hết lượt → nghỉ 15 phút; khoá sai → ghi lỗi (không tự dừng — admin quyết). */
    AiKeyResponse test(UUID callerId, UUID id);

    /** Dừng / bật lại: khoá dừng không bị hàng đợi dùng, vẫn giữ để bật lại. */
    AiKeyResponse setEnabled(UUID callerId, UUID id, boolean enabled);

    void delete(UUID callerId, UUID id);
}
