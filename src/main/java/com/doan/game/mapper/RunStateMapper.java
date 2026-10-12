package com.doan.game.mapper;

import com.doan.game.DTO.response.RunStateResponse;
import com.doan.game.entity.RunState;

import java.time.Instant;
import java.util.List;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class RunStateMapper {

    private RunStateMapper() {
    }

    /**
     * GET /api/play/state. `source` null khi trẻ chưa từng chơi → attemptIndex 1, continueChapter 1,
     * lastPlayedAt null. `chaptersDone` do service parse từ cột text (bảng JSON) rồi trao xuống —
     * mapper chỉ xếp hình, không đụng Jackson. continueChapter = size + 1, xong hết chương của bản
     * hiện tại → null (FE hiện nút "Chơi lại").
     */
    public static RunStateResponse toResponse(RunState source, String releaseVersion,
                                              Integer totalChapters, List<Integer> chaptersDone) {
        int attemptIndex = source == null || source.getAttemptIndex() == null ? 1 : source.getAttemptIndex();
        Instant lastPlayedAt = source == null ? null : source.getUpdatedAt();
        List<Integer> done = chaptersDone == null ? List.of() : chaptersDone;
        int size = done.size();
        Integer continueChapter = (totalChapters != null && size >= totalChapters) ? null : size + 1;
        return new RunStateResponse(releaseVersion, attemptIndex, totalChapters, size, done,
                continueChapter, lastPlayedAt);
    }
}
