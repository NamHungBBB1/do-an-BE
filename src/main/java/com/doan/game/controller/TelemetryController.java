package com.doan.game.telemetry.web;

import com.doan.game.shared.error.AppException;
import com.doan.game.shared.error.ErrorCode;
import com.doan.game.shared.web.ApiResponse;
import com.doan.game.telemetry.domain.EstimateEvent;
import com.doan.game.telemetry.repository.EstimateEventRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sổ ghi sự kiện. Không có nghiệp vụ nào ở đây — nhận, kiểm, cất.
 *
 * Client gom log vào IndexedDB rồi đẩy theo lô. Server chết thì game vẫn chơi
 * trọn vẹn (chốt D3), log nằm lại máy người chơi chờ lần đẩy sau.
 */
@RestController
@RequestMapping("/api/telemetry")
// @Validated BẮT BUỘC ở đây: @Valid trên List<T> KHÔNG lan xuống từng phần tử,
// thiếu nó thì dòng hỏng lọt qua và chỉ vỡ ở tầng DB thành lỗi 500.
@Validated
public class TelemetryController {

    /** Trần lô. Client gửi quá thì đó là lỗi client, không phải tải thật. */
    private static final int MAX_BATCH = 500;

    private final EstimateEventRepository repo;

    public TelemetryController(EstimateEventRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/estimates")
    @Transactional
    public ApiResponse<Map<String, Object>> ingest(@RequestBody @Valid List<EstimateRequest> batch) {
        if (batch.size() > MAX_BATCH) {
            throw new AppException(ErrorCode.BATCH_TOO_LARGE,
                    batch.size() + " dòng, trần là " + MAX_BATCH);
        }
        List<EstimateEvent> rows = batch.stream().map(TelemetryController::toEntity).toList();
        repo.saveAll(rows);
        return ApiResponse.ok(Map.of("saved", rows.size()));
    }

    /** Cho người phân tích lấy lại toàn bộ một lượt thí điểm theo seed. */
    @GetMapping("/estimates")
    public ApiResponse<List<EstimateEvent>> bySeed(@RequestParam String seed) {
        if (seed.isBlank()) throw new AppException(ErrorCode.SEED_REQUIRED);
        return ApiResponse.ok(repo.findBySeedOrderByCommittedAtAsc(seed));
    }

    private static EstimateEvent toEntity(EstimateRequest r) {
        EstimateEvent e = new EstimateEvent();
        e.setPlayerKey(r.playerKey());
        e.setSeed(r.seed());
        e.setBuildVersion(r.buildVersion());
        e.setVai(r.vai());
        e.setItemId(r.itemId());
        e.setGuess(r.guess());
        e.setTruth(r.truth());
        e.setUnit(r.unit());
        e.setConfidence(r.confidence());
        e.setAttemptIndex(r.attemptIndex());
        e.setShownAt(r.shownAt());
        e.setCommittedAt(r.committedAt());
        return e;
    }
}
