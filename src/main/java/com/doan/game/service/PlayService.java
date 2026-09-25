package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Nhận dữ liệu một chương gửi lên và ghi vào ba dòng đo lường.
 *
 * Bảng phụ trách: ChoiceEvent, MiniGameResult, RunState
 */
public interface PlayService {

    void nhanLoChuong(UUID slotId, ChapterBatchRequest req);

    RunStateResponse xemTrangThai(UUID slotId);

}
