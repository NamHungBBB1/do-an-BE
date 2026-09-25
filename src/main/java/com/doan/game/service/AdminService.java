package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Phát và thu vai bằng tay, kèm sổ ghi.
 *
 * Bảng phụ trách: RoleGrantLog
 */
public interface AdminService {

    void phatVai(UUID actorId, GrantRoleRequest req);

    void thuVai(UUID actorId, GrantRoleRequest req);

}
