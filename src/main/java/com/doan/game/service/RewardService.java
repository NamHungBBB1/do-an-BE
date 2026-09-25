package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Thành tựu, cửa hàng và đổi thưởng.
 *
 * Bảng phụ trách: Achievement, RewardItem, Redemption
 */
public interface RewardService {

    void traoThanhTuu(UUID slotId, String code);

    RedemptionResponse doiThuong(UUID slotId, UUID itemId);

}
