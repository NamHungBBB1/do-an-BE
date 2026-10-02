package com.doan.game.service.impl;

import com.doan.game.service.PlayService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Nhận lô một chương và ghi vào ba dòng đo lường. Lô gửi lại nhận ra nhờ UNIQUE (slot, chương, lần chơi, cảnh). Luôn nhận lô kể cả khi gói đã hết hạn; chỉ không mở khoá chương mới.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class PlayServiceImpl implements PlayService {

    @Override
    public void nhanLoChuong(UUID slotId, ChapterBatchRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public RunStateResponse xemTrangThai(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
