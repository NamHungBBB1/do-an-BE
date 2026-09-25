package com.doan.game.service.impl;

import com.doan.game.service.SlotService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Chỗ ngồi của trẻ: phát, trả, xoá sạch, và đường trẻ đăng nhập.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    @Override
    public SlotResponse phatCho(UUID groupId, CreateSlotRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void traCho(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void xoaSach(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public TokenResponse dangNhapTre(String code, String pin) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
