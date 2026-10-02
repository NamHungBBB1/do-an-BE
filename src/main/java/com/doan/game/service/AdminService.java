package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Cấp và thu vai nội bộ (AccountRole), kèm sổ ghi bắt buộc có lý do.
 *
 * Bảng phụ trách: AccountRole, RoleGrantLog
 */
public interface AdminService {

    void phatVai(UUID actorId, GrantRoleRequest req);

    void thuVai(UUID actorId, GrantRoleRequest req);

}
