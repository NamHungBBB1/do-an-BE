package com.doan.game.service.impl;

import com.doan.game.service.RewardService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Thành tựu, cửa hàng và đổi thưởng.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class RewardServiceImpl implements RewardService {

    @Override
    public void traoThanhTuu(UUID slotId, String code) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public RedemptionResponse doiThuong(UUID slotId, UUID itemId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
