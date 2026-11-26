package com.doan.game.service.impl;

import com.doan.game.entity.ArchiveItem;
import com.doan.game.entity.ArchiveLog;
import com.doan.game.enums.ArchiveAction;
import com.doan.game.enums.ArchiveKind;
import com.doan.game.enums.ArchiveState;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.ArchiveItemRepository;
import com.doan.game.repository.ArchiveLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Luật chung của vòng đời lưu trữ (27/11, spec build/spec-luu-tru-be-2026-11-27.md): chế độ, thời hạn, và chốt chặn ở các luồng
 * đang chạy (mục 8). Tách khỏi ArchiveServiceImpl vì kho ảnh và nội dung cần các chốt này, còn ArchiveServiceImpl lại cần kho ảnh
 * (tập đang dùng) — để chung một lớp là vòng phụ thuộc. Lớp này chỉ đụng hai bảng lưu trữ, không đụng đĩa.
 */
@Component
public class ArchivePolicy {

    private static final Logger log = LoggerFactory.getLogger(ArchivePolicy.class);

    public enum Mode { OFF, DRY_RUN, ON }

    /** Tệp không còn (hoặc sắp không còn) trên máy chủ: không được gắn vào nội dung, không hiện ở kho. */
    static final Set<ArchiveState> GONE = EnumSet.of(ArchiveState.ARCHIVING, ArchiveState.ARCHIVED, ArchiveState.RESTORING);
    static final Set<ArchiveKind> FILE_KINDS = EnumSet.of(ArchiveKind.IMAGE, ArchiveKind.AUDIO);
    private static final Duration DAY = Duration.ofDays(1);

    private final ArchiveItemRepository itemRepo;
    private final ArchiveLogRepository logRepo;
    private final AccountRepository accountRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** volatile: test đổi chế độ giữa chừng (ReflectionTestUtils), job đọc ở luồng lập lịch. */
    private volatile Mode mode;
    private final int imageDays;
    private final int audioDays;
    private final int releaseDays;
    private final int warnAssetDays;
    private final int warnReleaseDays;

    public ArchivePolicy(ArchiveItemRepository itemRepo, ArchiveLogRepository logRepo, AccountRepository accountRepo,
                         ObjectMapper objectMapper, Clock clock,
                         @Value("${app.archive.mode:OFF}") String mode,
                         @Value("${app.archive.image-days:30}") int imageDays,
                         @Value("${app.archive.audio-days:30}") int audioDays,
                         @Value("${app.archive.release-days:14}") int releaseDays,
                         @Value("${app.archive.warn-asset-days:7}") int warnAssetDays,
                         @Value("${app.archive.warn-release-days:3}") int warnReleaseDays) {
        this.itemRepo = itemRepo;
        this.logRepo = logRepo;
        this.accountRepo = accountRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.mode = parseMode(mode);
        this.imageDays = imageDays;
        this.audioDays = audioDays;
        this.releaseDays = releaseDays;
        this.warnAssetDays = warnAssetDays;
        this.warnReleaseDays = warnReleaseDays;
    }

    /** Giá trị lạ → OFF (chốt chặn số 1): gõ nhầm biến môi trường không được bật thứ gì. */
    static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.OFF;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("app.archive.mode = '{}' không hợp lệ (OFF | DRY_RUN | ON) — coi như OFF", raw);
            return Mode.OFF;
        }
    }

    public Mode mode() {
        return mode;
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    Duration countdown(ArchiveKind kind) {
        return DAY.multipliedBy(switch (kind) {
            case IMAGE -> imageDays;
            case AUDIO -> audioDays;
            case RELEASE -> releaseDays;
        });
    }

    int warnDays(ArchiveKind kind) {
        return kind == ArchiveKind.RELEASE ? warnReleaseDays : warnAssetDays;
    }

    /** Số ngày còn lại làm tròn lên, tối thiểu 0. */
    static int daysLeft(Instant dueAt, Instant now) {
        long seconds = Duration.between(now, dueAt).getSeconds();
        return seconds <= 0 ? 0 : (int) ((seconds + DAY.getSeconds() - 1) / DAY.getSeconds());
    }

    /** Đếm lại từ đầu (Giữ lại, khôi phục xong, đối soát). */
    void restartCountdown(ArchiveItem item, Instant now) {
        item.setState(ArchiveState.COUNTING);
        item.setCountdownFrom(now);
        item.setDueAt(now.plus(countdown(item.getKind())));
        item.setClaimedAt(null);
    }

    void log(ArchiveKind kind, String ref, ArchiveAction action, UUID actorId, String note) {
        ArchiveLog l = new ArchiveLog();
        l.setKind(kind);
        l.setItemRef(ref);
        l.setAction(action);
        l.setActor(actorId == null ? null : accountRepo.findById(actorId).orElse(null));
        l.setCreatedAt(Instant.now(clock));
        l.setNote(cut(note));
        logRepo.save(l);
    }

    void log(ArchiveItem item, ArchiveAction action, UUID actorId, String note) {
        log(item.getKind(), item.getItemRef(), action, actorId, note);
    }

    static String cut(String s) {
        return s == null ? null : s.length() > 255 ? s.substring(0, 255) : s;
    }

    // ------------------------------------------------------------------ kho ảnh
    static boolean isGone(ArchiveItem row) {
        return row != null && GONE.contains(row.getState());
    }

    /** Đường dẫn tệp → dòng lưu trữ (một truy vấn; bảng rỗng khi chưa bật). */
    public Map<String, ArchiveItem> fileRows() {
        return itemRepo.findByKindIn(FILE_KINDS).stream()
                .collect(Collectors.toMap(ArchiveItem::getItemRef, Function.identity(), (a, b) -> a));
    }

    /** archiveDaysLeft của kho ảnh: chỉ khi đang đếm và chế độ khác OFF (OFF thì không có gì sắp xảy ra). */
    public Integer daysLeft(ArchiveItem row) {
        if (row == null || mode == Mode.OFF || row.getState() != ArchiveState.COUNTING || row.getDueAt() == null) {
            return null;
        }
        return daysLeft(row.getDueAt(), Instant.now(clock));
    }

    public Integer daysLeft(String path) {
        ArchiveKind kind = path.startsWith("v/") ? ArchiveKind.AUDIO : ArchiveKind.IMAGE;
        return daysLeft(itemRepo.findByKindAndItemRef(kind, path).orElse(null));
    }

    /** Xoá tay ảnh mồ côi: đã lưu trữ → 409; đang đếm → bỏ dòng (cùng giao dịch với lần xoá). */
    public void onAssetDelete(String path, UUID actorId) {
        itemRepo.lockByKindAndRef(ArchiveKind.IMAGE, path).ifPresent(row -> {
            if (GONE.contains(row.getState())) {
                throw new AppException(ErrorCode.ASSET_ARCHIVED, path, List.of(path));
            }
            itemRepo.delete(row);
            log(row, ArchiveAction.COUNT_CLEAR, actorId, "xoá tay khỏi kho");
        });
    }

    /**
     * Tải lên lại đúng tệp đã lưu trữ: tệp đã nằm lại trên đĩa nên dòng về đếm ngay, không chờ job. Chỉ ARCHIVED: dòng ARCHIVING
     * có thể sắp bị gỡ (máy lưu trữ đã nhận việc) — để job đối soát sau; RESTORING thì máy lưu trữ tự báo xong.
     */
    public void onFileRewritten(ArchiveKind kind, String path) {
        itemRepo.lockByKindAndRef(kind, path).filter(row -> row.getState() == ArchiveState.ARCHIVED).ifPresent(row -> {
            restartCountdown(row, Instant.now(clock));
            log(row, ArchiveAction.RECONCILED, null, "tệp được tải lên lại");
        });
    }

    // ------------------------------------------------------------------ chốt chặn số 6 (nội dung)
    /**
     * JSON chương trỏ tới tệp đang ARCHIVING / ARCHIVED / RESTORING → 409 ASSET_ARCHIVED kèm danh sách đường dẫn. Một truy vấn theo
     * danh sách đường dẫn; khoá các dòng tìm được để máy lưu trữ nhận việc cùng lúc phải chờ lần ghi này rồi mới tính lại chỗ dùng.
     */
    public void requireFilesUsable(String json) {
        requirePathsUsable(filePaths(json));
    }

    private void requirePathsUsable(Set<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        List<String> blocked = itemRepo.lockByRefs(paths, FILE_KINDS).stream()
                .filter(ArchivePolicy::isGone).map(ArchiveItem::getItemRef).sorted().toList();
        if (!blocked.isEmpty()) {
            throw new AppException(ErrorCode.ASSET_ARCHIVED, String.join(", ", blocked), blocked);
        }
    }

    /**
     * Bản đưa lên chạy (đặt làm hiện tại / lùi về): bản đã lưu trữ → 6012; bản đã khôi phục nhưng còn tệp (kể cả giọng voiceReady)
     * chưa về máy chủ → 6011 kèm danh sách đường dẫn, để trẻ không tải phải bản thiếu ảnh / giọng.
     */
    public void requireReleaseUsable(String version, List<String> chapterJsons) {
        itemRepo.findByKindAndItemRef(ArchiveKind.RELEASE, version)
                .filter(row -> row.getState() != ArchiveState.COUNTING)
                .ifPresent(row -> {
                    throw new AppException(ErrorCode.RELEASE_ARCHIVED, version);
                });
        Set<String> paths = new LinkedHashSet<>();
        chapterJsons.forEach(j -> paths.addAll(filePaths(j)));
        requirePathsUsable(paths);
    }

    /** Đường dẫn tệp kho trong một JSON chương: chuỗi {{ASSET_BASE}}/… và giọng biến thể đóng băng (voiceReady). */
    Set<String> filePaths(String json) {
        Set<String> out = new LinkedHashSet<>();
        if (json == null) {
            return out;
        }
        Matcher m = AssetServiceImpl.REF.matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        if (json.contains("\"voiceReady\"")) {
            try {
                out.addAll(voiceReadyPaths(objectMapper.readTree(json)));
            } catch (IOException e) {
                // JSON đã qua kiểm ở bước trước; hỏng ở đây thì chỉ còn các đường dẫn dạng chuỗi
                log.warn("Không đọc được voiceReady: {}", e.getMessage());
            }
        }
        return out;
    }

    /** personalization.voiceReady (khoá giọng) → v/<khoá>.mp3. */
    static Set<String> voiceReadyPaths(JsonNode chapter) {
        Set<String> keys = VoiceKeys.frozenReady(chapter);
        Set<String> out = new LinkedHashSet<>();
        if (keys != null) {
            keys.stream().filter(k -> k.matches("[0-9a-f]{20}")).sorted().forEach(k -> out.add("v/" + k + ".mp3"));
        }
        return out;
    }
}
