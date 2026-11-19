package com.doan.game.service.impl;

import com.doan.game.DTO.response.AiCallLogResponse;
import com.doan.game.DTO.response.AiKeyHealthResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.entity.AiKey;
import com.doan.game.repository.AiCallLogRepository;
import com.doan.game.repository.AiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Trang admin đợt D: nhật ký gọi AI (lọc, mới nhất trước), sức khoẻ khoá, dọn nhật ký quá 30 ngày (khung 13.4). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallLogService {

    static final Duration RETENTION = Duration.ofDays(30);
    private static final Instant FAR_PAST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("3000-01-01T00:00:00Z");

    private final AiCallLogRepository logRepo;
    private final AiKeyRepository keyRepo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AiCallLogResponse> list(String keyAlias, String outcome, String kind, Instant from, Instant to,
                                                int page, int size) {
        return PageResponse.of(logRepo.search(blankToNull(keyAlias), blankToNull(outcome), blankToNull(kind),
                from == null ? FAR_PAST : from, to == null ? FAR_FUTURE : to,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), AiCallLogResponse::of);
    }

    @Transactional(readOnly = true)
    public AiKeyHealthResponse health() {
        Instant now = Instant.now(clock);
        Instant dayStart = ReportAiWriter.quotaDayStart(now);
        Map<String, Map<String, long[]>> counts = new HashMap<>();
        for (Object[] row : logRepo.countsSince(dayStart)) {
            counts.computeIfAbsent((String) row[0], k -> new TreeMap<>()).put((String) row[1],
                    new long[] {((Number) row[2]).longValue(), ((Number) row[3]).longValue(),
                            ((Number) row[4]).longValue()});
        }
        Map<String, Instant> lastOk = new HashMap<>();
        for (Object[] row : logRepo.lastOk()) {
            lastOk.put((String) row[0], (Instant) row[1]);
        }
        List<AiKeyHealthResponse.KeyHealth> out = new ArrayList<>();
        int healthy = 0;
        boolean keyNeedsHuman = false;
        List<AiKey> keys = keyRepo.findAll().stream()
                .sorted(Comparator.comparing(AiKey::getProvider).thenComparing(AiKey::getAlias)).toList();
        for (AiKey k : keys) {
            boolean ok = k.isEnabled() && (k.getCoolDownUntil() == null || !k.getCoolDownUntil().isAfter(now));
            healthy += ok ? 1 : 0;
            String err = k.getLastError() == null ? "" : k.getLastError();
            keyNeedsHuman |= !k.isEnabled()
                    && AiCallClassifier.NEEDS_HUMAN.stream().anyMatch(c -> err.startsWith(c + ":"));
            Map<String, Long> byOutcome = new TreeMap<>();
            long calls = 0;
            long in = 0;
            long outTokens = 0;
            for (Map.Entry<String, long[]> e : counts.getOrDefault(k.getAlias(), Map.of()).entrySet()) {
                byOutcome.put(e.getKey(), e.getValue()[0]);
                calls += e.getValue()[0];
                in += e.getValue()[1];
                outTokens += e.getValue()[2];
            }
            out.add(new AiKeyHealthResponse.KeyHealth(k.getId(), k.getAlias(), k.getProvider(),
                    k.getPurpose() == null ? ReportAiWriter.GENERAL : k.getPurpose(), k.getProject(), k.getTierLabel(),
                    k.isEnabled(), k.getCoolDownUntil(), ok, calls, byOutcome.getOrDefault(AiCallClassifier.OK, 0L),
                    byOutcome, in, outTokens, lastOk.get(k.getAlias()), k.getLastError(), k.getRpmLimit(),
                    k.getRpdLimit()));
        }
        String warning = healthy >= 2 ? null : "Chỉ còn " + healthy + " khoá AI dùng được (nên có ít nhất 2). "
                + (healthy == 0 ? "Báo cáo người lớn đang dùng bản viết theo luật. " : "")
                + "Kiểm tra khoá bị tắt / đang nghỉ hoặc nhập thêm khoá.";
        return new AiKeyHealthResponse(dayStart, healthy, warning, keyNeedsHuman || healthy == 0, out);
    }

    /** Giữ 30 ngày; 03:20 giờ máy chủ mỗi ngày. */
    @Scheduled(cron = "${app.ai.call-log.cleanup-cron:0 20 3 * * *}")
    @Transactional
    public void cleanup() {
        int n = logRepo.deleteOlderThan(Instant.now(clock).minus(RETENTION));
        if (n > 0) {
            log.info("Xoá {} dòng nhật ký gọi AI quá {} ngày", n, RETENTION.toDays());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
