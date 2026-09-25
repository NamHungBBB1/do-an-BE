package com.doan.game.service.impl;

import com.doan.game.service.AdminService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phát và thu vai bằng tay, kèm sổ ghi.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    @Override
    public void phatVai(UUID actorId, GrantRoleRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void thuVai(UUID actorId, GrantRoleRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
