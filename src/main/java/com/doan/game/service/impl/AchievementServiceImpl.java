package com.doan.game.service.impl;

import com.doan.game.service.AchievementService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Thành tựu và chứng chỉ. Đổi thưởng đã bỏ (01/10).
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class AchievementServiceImpl implements AchievementService {

    @Override
    public void awardAchievement(UUID slotId, String code) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public java.util.List<AchievementResponse> listAchievements(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
