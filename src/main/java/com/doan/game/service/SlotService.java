package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Chỗ ngồi của trẻ: phát, trả, xoá sạch, và đường trẻ đăng nhập.
 *
 * Bảng phụ trách: ChildSlot
 */
public interface SlotService {

    SlotResponse phatCho(UUID groupId, CreateSlotRequest req);

    void traCho(UUID slotId);

    void xoaSach(UUID slotId);

    TokenResponse dangNhapTre(String code, String pin);

}
