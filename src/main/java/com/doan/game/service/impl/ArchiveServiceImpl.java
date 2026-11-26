package com.doan.game.service.impl;

import com.doan.game.DTO.response.ArchiveItemResponse;
import com.doan.game.DTO.response.ArchiveLogResponse;
import com.doan.game.DTO.response.ArchiveRunResponse;
import com.doan.game.DTO.response.ArchiveSummaryResponse;
import com.doan.game.DTO.response.ArchiveTaskResponse;
import com.doan.game.DTO.response.AssetUsageResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.ArchiveItem;
import com.doan.game.entity.Asset;
import com.doan.game.entity.GameRelease;
import com.doan.game.entity.VoiceClip;
import com.doan.game.enums.ArchiveAction;
import com.doan.game.enums.ArchiveKind;
import com.doan.game.enums.ArchiveState;
import com.doan.game.enums.AssetKind;
import com.doan.game.enums.Role;
import com.doan.game.exception.AppException;
import com.doan.game.exception.DbErrors;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ArchiveItemRepository;
import com.doan.game.repository.ArchiveLogRepository;
import com.doan.game.repository.AssetRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.VoiceClipRepository;
import com.doan.game.service.ArchiveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Vòng đời lưu trữ (27/11, spec build/spec-luu-tru-be-2026-11-27.md mục 4–7). Job mỗi đêm tính mồ côi + giữ đồng hồ; máy lưu trữ
 * nhận việc qua /api/archive-worker; Studio xem / Giữ lại / Khôi phục. BE KHÔNG xoá tệp, không gọi tiến trình ngoài, không biết
 * kho lưu trữ ở đâu — với đĩa chỉ liệt kê, kiểm tồn tại, đọc cỡ tệp.
 */
@Service
public class ArchiveServiceImpl implements ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveServiceImpl.class);
    /** Tên tệp kho do BE đặt (hash 20 ký tự); tệp tạm, tệp lạ bị bỏ qua. */
    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f]{20}\\.(?:webp|mp3)");
    private static final Duration STUCK = Duration.ofHours(24);
    private static final Set<ArchiveState> LISTED_GONE =
            EnumSet.of(ArchiveState.ARCHIVING, ArchiveState.ARCHIVED, ArchiveState.RESTORING);

    private final ArchivePolicy policy;
    private final ArchiveItemRepository itemRepo;
    private final ArchiveLogRepository logRepo;
    private final AssetServiceImpl assets;
    private final AssetRepository assetRepo;
    private final VoiceClipRepository clipRepo;
    private final GameReleaseRepository releaseRepo;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository roleRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Path dir;
    private final String workerKey;
    private final int keepReleases;
    private final double maxNewOrphanRatio;
    private final int maxClaim;
    private final String statusFile;

    public ArchiveServiceImpl(ArchivePolicy policy, ArchiveItemRepository itemRepo, ArchiveLogRepository logRepo,
                              AssetServiceImpl assets, AssetRepository assetRepo, VoiceClipRepository clipRepo,
                              GameReleaseRepository releaseRepo, AccountRepository accountRepo,
                              AccountRoleRepository roleRepo, ObjectMapper objectMapper, Clock clock,
                              PlatformTransactionManager txManager,
                              @Value("${app.assets.dir}") String dir,
                              @Value("${app.archive.worker-key:}") String workerKey,
                              @Value("${app.archive.keep-releases:4}") int keepReleases,
                              @Value("${app.archive.max-new-orphan-ratio:0.5}") double maxNewOrphanRatio,
                              @Value("${app.archive.max-claim:200}") int maxClaim,
                              @Value("${app.archive.status-file:}") String statusFile) {
        this.policy = policy;
        this.itemRepo = itemRepo;
        this.logRepo = logRepo;
        this.assets = assets;
        this.assetRepo = assetRepo;
        this.clipRepo = clipRepo;
        this.releaseRepo = releaseRepo;
        this.accountRepo = accountRepo;
        this.roleRepo = roleRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.dir = Path.of(dir);
        this.workerKey = workerKey == null ? "" : workerKey.trim();
        this.keepReleases = Math.max(0, keepReleases);
        this.maxNewOrphanRatio = maxNewOrphanRatio;
        this.maxClaim = Math.max(1, maxClaim);
        this.statusFile = statusFile == null ? "" : statusFile.trim();
    }

    // ================================================================== job (mục 5)
    @Scheduled(cron = "${app.archive.cron:0 30 3 * * *}")
    public void nightly() {
        if (!policy.enabled()) {
            return;
        }
        try {
            runJob();
        } catch (RuntimeException e) {
            log.error("Lượt tính vòng đời lưu trữ lỗi: {}", e.toString());
        }
    }

    /** synchronized + giao dịch bên trong: lịch đêm và nút chạy tay không chồng nhau (chỉ job tạo dòng mới). */
    public synchronized ArchiveRunResponse runJob() {
        try {
            return tx.execute(s -> computeAndApply());
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            if (e instanceof DataIntegrityViolationException && !DbErrors.isUniqueViolation(e)) {
                throw e;   // lỗi ràng buộc khác là lỗi thật, giữ 500
            }
            // Chỉ gặp khi hai tiến trình BE cùng chạy job (vd lúc đổi bản): cả lượt rollback, lượt sau làm lại — không 500.
            log.warn("Lượt tính vòng đời lưu trữ đụng lượt khác, bỏ qua: {}", e.getMessage());
            return new ArchiveRunResponse(true, 0, itemRepo.countByState(ArchiveState.COUNTING), 0, 0, 0, 0,
                    "đụng một lượt khác đang chạy — thử lại sau");
        }
    }

    private ArchiveRunResponse computeAndApply() {
        Instant now = Instant.now(clock);
        // Xếp hàng với phát hành / đổi bản / thu hồi (cùng khoá con trỏ), rồi khoá mọi dòng lưu trữ theo id.
        releaseRepo.lockCurrent();
        List<ArchiveItem> rows = itemRepo.lockAll();
        boolean firstRun = rows.isEmpty() && !logRepo.existsByAction(ArchiveAction.COUNT_START);
        Map<String, ArchiveItem> fileRows = new HashMap<>();
        Map<String, ArchiveItem> releaseRows = new HashMap<>();
        for (ArchiveItem r : rows) {
            (r.getKind() == ArchiveKind.RELEASE ? releaseRows : fileRows).put(r.getItemRef(), r);
        }
        Set<String> archivedVersions = releaseRows.values().stream()
                .filter(r -> r.getState() == ArchiveState.ARCHIVED).map(ArchiveItem::getItemRef).collect(Collectors.toSet());
        Map<String, List<AssetUsageResponse>> usage = assets.usageIndexForArchive(archivedVersions);

        // ---- 1. phanh an toàn (chốt chặn số 2): tính xong mọi thứ rồi mới đổi
        String brake = currentUsageProblem(usage);
        if (brake == null && !Files.isDirectory(dir)) {
            brake = "không đọc được thư mục kho";
        }
        Map<String, Long> files = brake == null ? scanFiles() : Map.of();
        if (brake == null && files == null) {
            brake = "không đọc được thư mục kho";
        }
        if (brake == null) {
            long newOrphans = files.keySet().stream()
                    .filter(p -> !usage.containsKey(p) && !fileRows.containsKey(p)).count();
            if (!firstRun && !files.isEmpty() && newOrphans > maxNewOrphanRatio * files.size()) {
                brake = "tệp mồ côi mới " + newOrphans + " / " + files.size() + " vượt ngưỡng " + maxNewOrphanRatio;
            }
        }
        if (brake != null) {
            log.error("Phanh an toàn vòng đời lưu trữ: {} — không đổi gì", brake);
            policy.log(null, null, ArchiveAction.JOB_ABORTED, null, brake);
            return new ArchiveRunResponse(true, files == null ? 0 : files.size(), itemRepo.countByState(ArchiveState.COUNTING),
                    0, 0, 0, 0, brake);
        }

        int[] started = {0};
        int[] cleared = {0};
        int[] reconciled = {0};
        // ---- 2. tệp kho có trên đĩa
        for (Map.Entry<String, Long> f : files.entrySet()) {
            String path = f.getKey();
            boolean inUse = usage.containsKey(path);
            ArchiveItem row = fileRows.get(path);
            if (row == null) {
                if (!inUse) {
                    ArchiveItem n = newRow(kindOf(path), path, now, f.getValue());
                    policy.log(n, ArchiveAction.COUNT_START, null, null);
                    started[0]++;
                }
            } else if (row.getState() == ArchiveState.COUNTING) {
                if (inUse) {
                    itemRepo.delete(row);
                    policy.log(row, ArchiveAction.COUNT_CLEAR, null, "được dùng lại");
                    cleared[0]++;
                }
            } else if (row.getState() == ArchiveState.ARCHIVED) {
                // ---- 3. đối soát: tệp đã lưu trữ lại có trên đĩa (vd sinh lại cùng mã băm)
                if (inUse) {
                    itemRepo.delete(row);
                } else {
                    policy.restartCountdown(row, now);
                    row.setSizeBytes(f.getValue());
                }
                policy.log(row, ArchiveAction.RECONCILED, null, "tệp có lại trên máy chủ");
                reconciled[0]++;
            } else if (row.getState() == ArchiveState.ARCHIVING && isStuck(row, now)) {
                row.setState(ArchiveState.COUNTING);   // giữ dueAt: lượt nhận việc sau thử lại
                row.setClaimedAt(null);
                policy.log(row, ArchiveAction.RECONCILED, null, "nhận việc quá 24 giờ, tệp vẫn còn");
                reconciled[0]++;
            }
        }
        // ---- 3. đối soát: dòng mà tệp không còn trên đĩa
        for (ArchiveItem row : fileRows.values()) {
            if (files.containsKey(row.getItemRef())) {
                continue;
            }
            boolean inUse = usage.containsKey(row.getItemRef());
            switch (row.getState()) {
                case COUNTING -> {
                    itemRepo.delete(row);   // người dùng đã xoá ảnh mồ côi
                    policy.log(row, ArchiveAction.COUNT_CLEAR, null, "tệp không còn trên máy chủ");
                    cleared[0]++;
                }
                case ARCHIVING -> {
                    if (isStuck(row, now)) {
                        row.setState(ArchiveState.ARCHIVED);
                        row.setArchivedAt(now);
                        policy.log(row, ArchiveAction.RECONCILED, null, "nhận việc quá 24 giờ, tệp đã rời máy chủ");
                        reconciled[0]++;
                    }
                }
                case ARCHIVED -> {
                    if (inUse) {
                        // Không được phép xảy ra (chốt chặn số 6) — tự kéo tệp về thay vì để nội dung trỏ vào tệp không có.
                        log.error("Tệp đã lưu trữ {} đang được nội dung dùng — tự yêu cầu khôi phục", row.getItemRef());
                        row.setState(ArchiveState.RESTORING);
                        row.setRestoreRequestedAt(now);
                        policy.log(row, ArchiveAction.RECONCILED, null, "đang được dùng — tự yêu cầu khôi phục");
                        reconciled[0]++;
                    }
                }
                case RESTORING -> {
                    if (inUse) {
                        log.error("Tệp đang khôi phục {} đã được nội dung dùng", row.getItemRef());
                    }
                }
            }
        }

        // ---- 4. bản phát hành
        int archivedReleases = 0;
        UUID currentId = releaseRepo.findFirstByIsCurrentTrue().map(GameRelease::getId).orElse(null);
        List<GameRelease> releases = releaseRepo.findAllByOrderByReleasedAtDesc();
        Set<UUID> window = new HashSet<>();
        releases.stream().limit(keepReleases).forEach(r -> window.add(r.getId()));
        if (currentId != null) {
            window.add(currentId);
        }
        Map<String, Instant> lastPlay = lastPlayByVersion();
        for (GameRelease r : releases) {
            ArchiveItem row = releaseRows.remove(r.getVersion());
            if (window.contains(r.getId())) {
                if (row != null && row.getState() == ArchiveState.COUNTING) {
                    itemRepo.delete(row);
                    policy.log(row, ArchiveAction.COUNT_CLEAR, null, "trở lại nhóm bản gần nhất");
                    cleared[0]++;
                }
                continue;
            }
            Instant played = lastPlay.get(r.getVersion());
            if (row == null) {
                Instant from = played != null && played.isAfter(now) ? played : now;
                row = newRow(ArchiveKind.RELEASE, r.getVersion(), from, null);
                policy.log(row, ArchiveAction.COUNT_START, null, null);
                started[0]++;
            } else if (row.getState() == ArchiveState.COUNTING && played != null && played.isAfter(row.getCountdownFrom())) {
                // Còn trẻ gửi lô chơi của bản này: đồng hồ tính từ lô mới nhất.
                row.setCountdownFrom(played);
                row.setDueAt(played.plus(policy.countdown(ArchiveKind.RELEASE)));
            }
            if (row.getState() == ArchiveState.COUNTING && !row.getDueAt().isAfter(now)
                    && policy.mode() == ArchivePolicy.Mode.ON) {
                row.setState(ArchiveState.ARCHIVED);
                row.setArchivedAt(now);
                policy.log(row, ArchiveAction.ARCHIVED, null, null);
                archivedReleases++;
            }
        }
        // Dòng của bản không còn trong DB (chỉ gặp khi dữ liệu thử bị xoá): bỏ.
        releaseRows.values().forEach(itemRepo::delete);

        itemRepo.flush();
        long counting = itemRepo.countByState(ArchiveState.COUNTING);
        log.info("Vòng đời lưu trữ ({}): quét {} tệp, đang đếm {}, mới đếm {}, bỏ đếm {}, đối soát {}, bản phát hành lưu trữ {}",
                policy.mode(), files.size(), counting, started[0], cleared[0], reconciled[0], archivedReleases);
        return new ArchiveRunResponse(false, files.size(), counting, started[0], cleared[0], reconciled[0], archivedReleases,
                null);
    }

    /** null = ổn. Có bản đang chạy mà không trỏ tới tệp nào (đọc JSON hỏng / mất kết nối) thì mọi tệp trông như mồ côi. */
    private String currentUsageProblem(Map<String, List<AssetUsageResponse>> usage) {
        if (releaseRepo.findFirstByIsCurrentTrue().isEmpty()) {
            return "chưa có bản đang chạy";
        }
        boolean any = usage.values().stream().anyMatch(u -> u.stream().anyMatch(x -> "CURRENT".equals(x.where())));
        return any ? null : "bản đang chạy không trỏ tới tệp nào";
    }

    /** a/, v/ trên đĩa: đường dẫn → cỡ tệp. null = không đọc được (phanh). */
    private Map<String, Long> scanFiles() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String sub : List.of("a", "v")) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (var list = Files.list(d)) {
                for (Path f : (Iterable<Path>) list::iterator) {
                    String name = f.getFileName().toString();
                    if (FILE_NAME.matcher(name).matches() && Files.isRegularFile(f)) {
                        out.put(sub + "/" + name, Files.size(f));
                    }
                }
            } catch (IOException e) {
                log.error("Không duyệt được {}: {}", d, e.getMessage());
                return null;
            }
        }
        return out;
    }

    private Map<String, Instant> lastPlayByVersion() {
        Map<String, Instant> out = new HashMap<>();
        List<Object[]> all = new ArrayList<>(itemRepo.lastChoiceEventByVersion());
        all.addAll(itemRepo.lastPickEventByVersion());
        for (Object[] r : all) {
            if (r[0] != null && r[1] != null) {
                out.merge((String) r[0], (Instant) r[1], (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return out;
    }

    private ArchiveItem newRow(ArchiveKind kind, String ref, Instant from, Long size) {
        ArchiveItem n = new ArchiveItem();
        n.setKind(kind);
        n.setItemRef(ref);
        n.setState(ArchiveState.COUNTING);
        n.setCountdownFrom(from);
        n.setDueAt(from.plus(policy.countdown(kind)));
        n.setSizeBytes(size);
        return itemRepo.save(n);
    }

    private static boolean isStuck(ArchiveItem row, Instant now) {
        return row.getClaimedAt() == null || row.getClaimedAt().plus(STUCK).isBefore(now);
    }

    private static ArchiveKind kindOf(String path) {
        return path.startsWith("v/") ? ArchiveKind.AUDIO : ArchiveKind.IMAGE;
    }

    // ================================================================== Studio (mục 7)
    @Override
    @Transactional(readOnly = true)
    public ArchiveSummaryResponse summary() {
        if (!policy.enabled()) {
            return new ArchiveSummaryResponse(false, 0, 0, 0, 0, 0, null);
        }
        Instant now = Instant.now(clock);
        long dueSoon = 0;
        long counting = 0;
        long archived = 0;
        long restoring = 0;
        long bytes = 0;
        for (ArchiveItem r : itemRepo.findAll()) {
            switch (r.getState()) {
                case COUNTING -> {
                    counting++;
                    if (isDueSoon(r, now)) {
                        dueSoon++;
                    }
                }
                case ARCHIVED -> {
                    archived++;
                    bytes += r.getSizeBytes() == null ? 0 : r.getSizeBytes();
                }
                case RESTORING -> restoring++;
                case ARCHIVING -> {
                }
            }
        }
        return new ArchiveSummaryResponse(true, dueSoon, counting, archived, restoring, bytes, lastSync());
    }

    private boolean isDueSoon(ArchiveItem r, Instant now) {
        return ArchivePolicy.daysLeft(r.getDueAt(), now) <= policy.warnDays(r.getKind());
    }

    /** Tệp trạng thái do máy lưu trữ ghi sau mỗi lần đồng bộ: {"finishedAt": "...Z", "ok": true}. Thiếu / hỏng → null. */
    private ArchiveSummaryResponse.LastSync lastSync() {
        if (statusFile.isEmpty()) {
            return null;
        }
        try {
            JsonNode n = objectMapper.readTree(Files.readString(Path.of(statusFile), StandardCharsets.UTF_8));
            if (n == null || !n.path("finishedAt").isTextual() || !n.path("ok").isBoolean()) {
                return null;
            }
            return new ArchiveSummaryResponse.LastSync(Instant.parse(n.path("finishedAt").asText()), n.path("ok").asBoolean());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArchiveItemResponse> dueSoon() {
        if (!policy.enabled()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        List<ArchiveItem> rows = itemRepo.findByStateIn(Set.of(ArchiveState.COUNTING)).stream()
                .filter(r -> isDueSoon(r, now))
                .sorted(Comparator.comparing(ArchiveItem::getDueAt).thenComparing(ArchiveItem::getItemRef))
                .toList();
        return toResponses(rows, now, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArchiveItemResponse> archived() {
        if (!policy.enabled()) {
            return List.of();
        }
        Comparator<ArchiveItem> newest = Comparator.comparing(
                (ArchiveItem r) -> r.getArchivedAt() != null ? r.getArchivedAt() : r.getClaimedAt(),
                Comparator.nullsFirst(Comparator.reverseOrder()));
        List<ArchiveItem> rows = itemRepo.findByStateIn(LISTED_GONE).stream()
                .sorted(newest.thenComparing(ArchiveItem::getItemRef)).toList();
        return toResponses(rows, Instant.now(clock), false);
    }

    @Override
    @Transactional
    public ArchiveItemResponse keep(UUID callerId, UUID id) {
        requireInternal(callerId);
        ArchiveItem row = itemRepo.lockById(id).orElseThrow(() -> notFound(id));
        if (row.getState() != ArchiveState.COUNTING) {
            throw new AppException(ErrorCode.ARCHIVE_STATE_CONFLICT, "chỉ giữ lại được mục đang đếm ngày");
        }
        Instant now = Instant.now(clock);
        policy.restartCountdown(row, now);
        row.setKeptBy(accountRepo.findById(callerId).orElse(null));
        row.setKeptAt(now);
        policy.log(row, ArchiveAction.KEEP, callerId, null);
        return toResponses(List.of(row), now, row.getState() == ArchiveState.COUNTING).get(0);
    }

    @Override
    @Transactional
    public ArchiveItemResponse restore(UUID callerId, UUID id) {
        requireInternal(callerId);
        Instant now = Instant.now(clock);
        // Chỉ đọc số bản (không nạp thực thể): nạp trước rồi khoá sau thì Hibernate trả lại bản đã nạp, có thể cũ.
        Optional<String> version = itemRepo.findReleaseRef(id);
        if (version.isEmpty()) {
            ArchiveItem row = itemRepo.lockById(id).orElseThrow(() -> notFound(id));
            if (row.getState() == ArchiveState.ARCHIVED) {
                requestRestore(row, callerId, now, null);
            } else if (row.getState() != ArchiveState.RESTORING) {
                throw new AppException(ErrorCode.ARCHIVE_STATE_CONFLICT, "chỉ khôi phục được mục đã lưu trữ");
            }
            return toResponses(List.of(row), now, false).get(0);
        }
        // Bản phát hành: khoá dòng bản + dòng các tệp của bản trong MỘT truy vấn xếp theo id (cùng thứ tự với job / máy lưu trữ).
        GameRelease release = releaseRepo.findByVersion(version.get())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có phiên bản " + version.get()));
        Set<String> files = assets.releaseFiles(release);
        Set<UUID> ids = new HashSet<>();
        ids.add(id);
        if (!files.isEmpty()) {
            itemRepo.findByItemRefInAndKindIn(files, ArchivePolicy.FILE_KINDS).forEach(r -> ids.add(r.getId()));
        }
        Map<UUID, ArchiveItem> locked = itemRepo.lockByIds(ids).stream()
                .collect(Collectors.toMap(ArchiveItem::getId, Function.identity()));
        ArchiveItem row = Optional.ofNullable(locked.get(id)).orElseThrow(() -> notFound(id));
        if (row.getState() != ArchiveState.ARCHIVED) {
            throw new AppException(ErrorCode.ARCHIVE_STATE_CONFLICT, "chỉ khôi phục được bản đã lưu trữ");
        }
        // RELEASE không rời DB: về đếm ngay với đồng hồ mới, tệp đã lưu trữ của nó được kéo về.
        policy.restartCountdown(row, now);
        row.setRestoreRequestedBy(accountRepo.findById(callerId).orElse(null));
        row.setRestoreRequestedAt(now);
        policy.log(row, ArchiveAction.RESTORE_REQUEST, callerId, null);
        for (ArchiveItem f : locked.values()) {
            if (f.getKind() != ArchiveKind.RELEASE && f.getState() == ArchiveState.ARCHIVED && files.contains(f.getItemRef())) {
                requestRestore(f, callerId, now, "khôi phục cùng bản " + row.getItemRef());
            }
        }
        return toResponses(List.of(row), now, true).get(0);
    }

    private void requestRestore(ArchiveItem row, UUID callerId, Instant now, String note) {
        row.setState(ArchiveState.RESTORING);
        row.setRestoreRequestedBy(callerId == null ? null : accountRepo.findById(callerId).orElse(null));
        row.setRestoreRequestedAt(now);
        row.setLastError(null);
        policy.log(row, ArchiveAction.RESTORE_REQUEST, callerId, note);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArchiveLogResponse> log(Integer limit) {
        if (!policy.enabled()) {
            return List.of();
        }
        int n = limit == null ? 100 : Math.max(1, Math.min(500, limit));
        return logRepo.findLatest(PageRequest.of(0, n)).stream()
                .map(l -> new ArchiveLogResponse(l.getCreatedAt(), l.getAction(), l.getKind(), l.getItemRef(),
                        l.getActor() == null ? null : l.getActor().getDisplayName(), l.getNote()))
                .toList();
    }

    @Override
    public ArchiveRunResponse run(UUID callerId) {
        if (!roleRepo.existsByAccount_IdAndRole(callerId, Role.ADMIN)) {
            throw new AppException(ErrorCode.FORBIDDEN, "chỉ ADMIN chạy tay được");
        }
        if (!policy.enabled()) {
            throw new AppException(ErrorCode.ARCHIVE_DISABLED);
        }
        return runJob();
    }

    private void requireInternal(UUID callerId) {
        if (!roleRepo.isInternal(callerId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "cần vai nội bộ");
        }
    }

    private static AppException notFound(UUID id) {
        return new AppException(ErrorCode.NOT_FOUND, "không có mục lưu trữ " + id);
    }

    /** Nhãn / người tải theo lô: một truy vấn cho ảnh, một cho giọng. withUrl = tệp còn trên máy chủ. */
    private List<ArchiveItemResponse> toResponses(List<ArchiveItem> rows, Instant now, boolean withUrl) {
        Set<String> images = rows.stream().filter(r -> r.getKind() == ArchiveKind.IMAGE).map(ArchiveItem::getItemRef)
                .collect(Collectors.toSet());
        Set<String> keys = rows.stream().filter(r -> r.getKind() == ArchiveKind.AUDIO).map(r -> clipKey(r.getItemRef()))
                .collect(Collectors.toSet());
        Map<String, Asset> byPath = images.isEmpty() ? Map.of() : assetRepo.findByPathIn(images).stream()
                .collect(Collectors.toMap(Asset::getPath, Function.identity(), (a, b) -> a));
        Map<String, VoiceClip> byKey = keys.isEmpty() ? Map.of() : clipRepo.findByClipKeyIn(keys).stream()
                .collect(Collectors.toMap(VoiceClip::getClipKey, Function.identity(), (a, b) -> a));
        List<ArchiveItemResponse> out = new ArrayList<>();
        for (ArchiveItem r : rows) {
            String ref = r.getItemRef();
            String label = ref;
            Account by = null;
            String url = null;
            String thumb = null;
            if (r.getKind() == ArchiveKind.IMAGE) {
                Asset a = byPath.get(ref);
                label = a == null || a.getLabel() == null ? ref : a.getLabel();
                by = a == null ? null : a.getUploadedBy();
                thumb = assets.thumbUrl(AssetKind.IMAGE, ref);
            } else if (r.getKind() == ArchiveKind.AUDIO) {
                VoiceClip c = byKey.get(clipKey(ref));
                label = c == null || c.getReadText() == null ? ref
                        : c.getReadText().length() > 120 ? c.getReadText().substring(0, 120) : c.getReadText();
                by = c == null ? null : c.getRequestedBy();
            }
            if (withUrl && r.getKind() != ArchiveKind.RELEASE) {
                url = assets.fileUrl(ref);
            }
            Account kept = r.getKeptBy();
            out.add(new ArchiveItemResponse(r.getId(), r.getKind(), ref, label, url, thumb,
                    by == null ? null : by.getDisplayName(), r.getSizeBytes(), r.getDueAt(),
                    r.getState() == ArchiveState.COUNTING && r.getDueAt() != null ? ArchivePolicy.daysLeft(r.getDueAt(), now) : null,
                    r.getKeptAt(), kept == null ? null : kept.getDisplayName(), r.getState(), r.getArchivedAt(),
                    r.getRestoreRequestedAt()));
        }
        return out;
    }

    /** v/<khoá>.mp3 → khoá. */
    private static String clipKey(String path) {
        String name = path.substring(path.indexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // ================================================================== máy lưu trữ (mục 6)
    /** Chốt chặn số 4: khoá trống = không ai gọi được; so sánh thời gian hằng như VOICE_WORKER_KEY. Sai / thiếu / trống đều 403. */
    @Override
    public void requireWorker(String presentedKey) {
        if (workerKey.isEmpty()) {
            throw new AppException(ErrorCode.FORBIDDEN, "cửa lưu trữ chưa bật");
        }
        byte[] a = workerKey.getBytes(StandardCharsets.UTF_8);
        byte[] b = presentedKey == null ? new byte[0] : presentedKey.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new AppException(ErrorCode.FORBIDDEN, "khoá không đúng");
        }
    }

    @Override
    @Transactional
    public List<ArchiveTaskResponse> claim(Integer limit) {
        if (policy.mode() != ArchivePolicy.Mode.ON) {
            return List.of();
        }
        int n = limit == null || limit <= 0 ? maxClaim : Math.min(limit, maxClaim);
        Instant now = Instant.now(clock);
        List<UUID> ids = itemRepo.findDueIds(ArchivePolicy.FILE_KINDS, ArchiveState.COUNTING, now, PageRequest.of(0, n));
        if (ids.isEmpty()) {
            return List.of();
        }
        // Khoá trước, tính chỗ dùng sau (chốt chặn số 5): lần lưu nháp đang giữ khoá dòng thì claim chờ nó xong rồi mới thấy nháp.
        List<ArchiveItem> locked = new ArrayList<>(itemRepo.lockByIds(ids));
        Set<String> archivedVersions = itemRepo.findByKindInAndState(Set.of(ArchiveKind.RELEASE), ArchiveState.ARCHIVED).stream()
                .map(ArchiveItem::getItemRef).collect(Collectors.toSet());
        Map<String, List<AssetUsageResponse>> usage = assets.usageIndexForArchive(archivedVersions);
        String brake = currentUsageProblem(usage);
        if (brake != null) {
            log.error("Phanh an toàn khi phát việc lưu trữ: {} — không phát việc", brake);
            policy.log(null, null, ArchiveAction.JOB_ABORTED, null, "phát việc: " + brake);
            return List.of();
        }
        locked.sort(Comparator.comparing(ArchiveItem::getDueAt).thenComparing(ArchiveItem::getItemRef));
        List<ArchiveTaskResponse> out = new ArrayList<>();
        for (ArchiveItem row : locked) {
            if (row.getState() != ArchiveState.COUNTING || row.getDueAt().isAfter(now)) {
                continue;   // đã đổi trong lúc chờ khoá (Giữ lại, job)
            }
            if (usage.containsKey(row.getItemRef())) {
                itemRepo.delete(row);
                policy.log(row, ArchiveAction.COUNT_CLEAR, null, "được dùng lại trước lúc lưu trữ");
                continue;
            }
            if (!Files.exists(dir.resolve(row.getItemRef()))) {
                itemRepo.delete(row);
                policy.log(row, ArchiveAction.COUNT_CLEAR, null, "tệp không còn trên máy chủ");
                continue;
            }
            row.setState(ArchiveState.ARCHIVING);
            row.setClaimedAt(now);
            row.setLastError(null);
            policy.log(row, ArchiveAction.CLAIM, null, null);
            out.add(new ArchiveTaskResponse(row.getId(), row.getKind(), row.getItemRef()));
        }
        return out;
    }

    @Override
    @Transactional
    public void reportArchived(UUID id) {
        ArchiveItem row = requireFileRow(id, ArchiveState.ARCHIVING);
        row.setState(ArchiveState.ARCHIVED);
        row.setArchivedAt(Instant.now(clock));
        row.setLastError(null);
        policy.log(row, ArchiveAction.ARCHIVED, null, null);
    }

    @Override
    @Transactional
    public void reportFailed(UUID id, String error) {
        ArchiveItem row = requireFileRow(id, ArchiveState.ARCHIVING);
        row.setState(ArchiveState.COUNTING);   // giữ dueAt: lượt sau thử lại
        row.setClaimedAt(null);
        row.setLastError(ArchivePolicy.cut(error == null || error.isBlank() ? "không rõ lỗi" : error.trim()));
        log.warn("Lưu trữ {} không thành: {}", row.getItemRef(), row.getLastError());
        // Lỗi nguyên văn chỉ giữ ở lastError (không trả FE): nhật ký Studio không lộ cơ chế lưu trữ.
        policy.log(row, ArchiveAction.FAILED, null, "lưu trữ không thành, sẽ thử lại");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArchiveTaskResponse> restores() {
        return itemRepo.findByKindInAndState(ArchivePolicy.FILE_KINDS, ArchiveState.RESTORING).stream()
                .sorted(Comparator.comparing(ArchiveItem::getItemRef))
                .map(r -> new ArchiveTaskResponse(r.getId(), r.getKind(), r.getItemRef())).toList();
    }

    @Override
    @Transactional
    public void reportRestored(UUID id) {
        ArchiveItem row = requireFileRow(id, ArchiveState.RESTORING);
        if (!Files.isRegularFile(dir.resolve(row.getItemRef()))) {
            throw new AppException(ErrorCode.ARCHIVE_STATE_CONFLICT, "tệp chưa có trên máy chủ");
        }
        policy.restartCountdown(row, Instant.now(clock));
        row.setLastError(null);
        policy.log(row, ArchiveAction.RESTORED, null, null);
    }

    @Override
    @Transactional
    public void reportRestoreFailed(UUID id, String error) {
        ArchiveItem row = requireFileRow(id, ArchiveState.RESTORING);
        row.setLastError(ArchivePolicy.cut(error == null || error.isBlank() ? "không rõ lỗi" : error.trim()));
        log.warn("Khôi phục {} không thành: {}", row.getItemRef(), row.getLastError());
        policy.log(row, ArchiveAction.FAILED, null, "khôi phục không thành, sẽ thử lại");
    }

    private ArchiveItem requireFileRow(UUID id, ArchiveState expected) {
        ArchiveItem row = itemRepo.lockById(id).orElseThrow(() -> notFound(id));
        if (row.getKind() == ArchiveKind.RELEASE || row.getState() != expected) {
            throw new AppException(ErrorCode.ARCHIVE_STATE_CONFLICT, "đang " + row.getState() + ", cần " + expected);
        }
        return row;
    }
}
