package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.List;
import java.util.UUID;

/**
 * Cấp và thu vai nội bộ (AccountRole), kèm sổ ghi bắt buộc có lý do.
 *
 * Bảng phụ trách: AccountRole, RoleGrantLog
 */
public interface AdminService {

    /** Cấp một vai nội bộ cho subjectId; đã có thì lặp vô hại. Trả danh sách vai hiện có của người đó. */
    RoleListResponse grantRole(UUID actorId, GrantRoleRequest req);

    /** Thu một vai nội bộ; không giữ vai đó → 1003; admin tự thu ADMIN của mình → 1001. */
    RoleListResponse revokeRole(UUID actorId, GrantRoleRequest req);

    /** Tìm đúng một tài khoản theo email (không phân biệt hoa thường) để lấy id khi cấp vai; không có → 1003. */
    AccountResponse findAccount(String email);

    /** Mọi tài khoản đang giữ ít nhất một vai nội bộ, xếp theo email, mỗi người kèm đủ vai và gói đang có. */
    List<AccountResponse> listInternalAccounts();

}
