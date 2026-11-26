package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Nhận lô một chương và ghi vào ba dòng đo lường. Lô gửi lại nhận ra nhờ UNIQUE (slot, chương, lần chơi, cảnh). Luôn nhận lô kể cả khi gói đã hết hạn; chỉ không mở khoá chương mới.
 *
 * Bảng phụ trách: ChoiceEvent, MiniGameResult, RunState
 */
public interface PlayService {

    /** Nhận lô cuối chương (Hưng 12/10): BE chấm tất cả, một @Transactional, trả tiến độ cho FE. */
    ChapterBatchResponse submitChapterBatch(UUID slotId, ChapterBatchRequest req);

    /** Lô `schema: 2` (play-v2.md): kiểm chặt, BE diễn giải dãy sự kiện; response y như bản 1. */
    ChapterBatchResponse submitChapterBatchV2(UUID slotId, com.fasterxml.jackson.databind.JsonNode body);

    RunStateResponse getRunState(UUID slotId);

}
