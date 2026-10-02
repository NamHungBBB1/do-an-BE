package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Thành tựu và chứng chỉ. Đổi thưởng đã bỏ (01/10).
 *
 * Bảng phụ trách: Achievement
 */
public interface AchievementService {

    void traoThanhTuu(UUID slotId, String code);

    java.util.List<AchievementResponse> xemThanhTuu(UUID slotId);

}
