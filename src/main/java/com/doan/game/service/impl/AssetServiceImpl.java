package com.doan.game.service.impl;

import com.doan.game.DTO.request.UpdateAssetRequest;
import com.doan.game.DTO.response.AssetResponse;
import com.doan.game.DTO.response.AssetUploadResponse;
import com.doan.game.DTO.response.AssetUsageResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.ArchiveItem;
import com.doan.game.entity.Asset;
import com.doan.game.entity.Chapter;
import com.doan.game.entity.GameRelease;
import com.doan.game.entity.ReleaseChapter;
import com.doan.game.enums.ArchiveKind;
import com.doan.game.enums.AssetKind;
import com.doan.game.enums.DraftStatus;
import com.doan.game.enums.Role;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.AssetRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.service.AssetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kho ảnh (24/10, đợt 2a — spec build/spec-kho-anh-giong-2026-10-24.md mục 3). Tệp nằm ở app.assets.dir (production
 * /var/finteen/assets, nginx trả thẳng), tên = sha256(byte cuối)[:20], không ghi đè; DB chỉ giữ phiếu Asset.
 * Trạng thái / chỗ dùng KHÔNG lưu: quét JSON nháp + bản phát hành mỗi lần hỏi (vài chục chương × ≤ 35 KB).
 */
@Service
public class AssetServiceImpl implements AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetServiceImpl.class);
    /** Chuỗi tệp trong JSON chương: {{ASSET_BASE}}/a/<hash>.webp (ảnh), {{ASSET_BASE}}/v/<hash>.mp3 (giọng). */
    static final Pattern REF = Pattern.compile("\\{\\{ASSET_BASE}}/((?:a|v)/[0-9a-f]{20}\\.(?:webp|mp3))");
    private static final Pattern CHAPTER = Pattern.compile("^CH\\d{2}$");
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    // Cùng thông số ops/finteen-assets/build_assets.py (71 ảnh hiện có): rộng ≤ 1920, chất lượng 80.
    private static final int MAX_WIDTH = 1920;
    private static final int QUALITY = 80;
    // Hình thu nhỏ cho màn kho ảnh (26/11): thẻ hiển thị ≤ 320 px, tải 558 ảnh gốc ≈ 60 MB là quá nặng.
    private static final int THUMB_WIDTH = 320;
    private static final int THUMB_QUALITY = 70;
    private static final Set<Role> WRITERS = Set.of(Role.ADMIN, Role.GAME_EDITOR, Role.GAME_MANAGER);

    private final AssetRepository assetRepo;
    private final ChapterRepository chapterRepo;
    private final ChapterDraftRepository draftRepo;
    private final GameReleaseRepository releaseRepo;
    private final ReleaseChapterRepository releaseChapterRepo;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String assetBase;
    private final Path dir;
    private final String cwebp;
    private final ArchivePolicy archivePolicy;

    public AssetServiceImpl(AssetRepository assetRepo, ChapterRepository chapterRepo, ChapterDraftRepository draftRepo,
                            GameReleaseRepository releaseRepo, ReleaseChapterRepository releaseChapterRepo,
                            AccountRepository accountRepo, AccountRoleRepository accountRoleRepo,
                            ObjectMapper objectMapper, Clock clock, ArchivePolicy archivePolicy,
                            @Value("${app.assets.base-url}") String assetBase,
                            @Value("${app.assets.dir}") String dir,
                            @Value("${app.assets.cwebp:cwebp}") String cwebp) {
        this.assetRepo = assetRepo;
        this.chapterRepo = chapterRepo;
        this.draftRepo = draftRepo;
        this.releaseRepo = releaseRepo;
        this.releaseChapterRepo = releaseChapterRepo;
        this.accountRepo = accountRepo;
        this.accountRoleRepo = accountRoleRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.assetBase = assetBase.replaceAll("/+$", "");
        this.dir = Path.of(dir);
        this.cwebp = cwebp;
        this.archivePolicy = archivePolicy;
    }

    // ------------------------------------------------------------------ đọc
    @Override
    @Transactional(readOnly = true)
    public List<AssetResponse> list(String kind, String chapter, String status, String q) {
        Map<String, List<AssetUsageResponse>> usage = usageIndex();
        // Vòng đời lưu trữ (27/11): tệp đã chuyển đi không còn trên máy chủ để hiện; tệp đang đếm kèm số ngày còn lại.
        Map<String, ArchiveItem> archive = archivePolicy.fileRows();
        List<AssetResponse> out = new ArrayList<>();
        Set<String> registered = new LinkedHashSet<>();
        for (Asset a : assetRepo.findAll()) {
            registered.add(a.getPath());
            ArchiveItem row = archive.get(a.getPath());
            if (!ArchivePolicy.isGone(row)) {
                out.add(toResponse(a, usage.getOrDefault(a.getPath(), List.of()), archivePolicy.daysLeft(row)));
            }
        }
        usage.forEach((path, u) -> {
            ArchiveItem row = archive.get(path);
            if (!registered.contains(path) && !ArchivePolicy.isGone(row)) {
                out.add(unregistered(path, u, archivePolicy.daysLeft(row)));
            }
        });
        String ch = chapter == null || chapter.isBlank() ? null : chapter.trim().toUpperCase(Locale.ROOT);
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String kd = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
        String text = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        return out.stream()
                .filter(a -> kd == null || a.kind().name().equals(kd))
                .filter(a -> st == null || a.status().equals(st))
                .filter(a -> ch == null || ch.equals(a.chapterCode())
                        || a.usage().stream().anyMatch(u -> ch.equals(u.chapterCode())))
                .filter(a -> text == null || (a.label() != null && a.label().toLowerCase(Locale.ROOT).contains(text))
                        || a.ref().contains(text))
                .sorted(Comparator.comparing(AssetResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AssetResponse::ref))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AssetResponse get(UUID id) {
        Asset a = requireAsset(id);
        return toResponse(a, usageIndex().getOrDefault(a.getPath(), List.of()));
    }

    // ------------------------------------------------------------------ ghi
    @Override
    @Transactional
    public AssetUploadResponse upload(UUID callerId, String filename, byte[] bytes, String label, String chapterCode) {
        requireWriter(callerId);
        if (bytes == null || bytes.length == 0) {
            throw new AppException(ErrorCode.ASSET_INVALID, "tệp rỗng");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new AppException(ErrorCode.ASSET_INVALID, "ảnh tối đa 5 MB");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new AppException(ErrorCode.ASSET_INVALID, "chỉ nhận ảnh PNG, JPEG, WebP");
        }
        String chapter = normalizeChapter(chapterCode);
        String cleanLabel = normalizeLabel(label, filename);

        // Trùng tệp gốc (kể cả khi nâng cwebp làm WebP ra byte khác) hoặc trùng WebP → trả bản đã có, nhãn cũ giữ nguyên.
        String sourceSha = sha256(bytes);
        var bySource = assetRepo.findFirstBySourceSha256(sourceSha);
        // Tệp của phiếu cũ đã được lưu trữ (không còn trên đĩa): đổi lại để ghi lại đúng tệp đó, thay vì trả bản trùng không có tệp.
        if (bySource.isPresent() && Files.exists(dir.resolve(bySource.get().getPath()))) {
            return duplicate(bySource.get());
        }
        byte[] webp = "webp".equals(type) ? bytes : toWebp(bytes, type);
        String sha = sha256(webp);
        var byHash = assetRepo.findBySha256(sha);
        if (byHash.isPresent()) {
            rewriteIfMissing(byHash.get().getPath(), webp);
            return duplicate(byHash.get());
        }
        if (bySource.isPresent()) {
            // Cùng tệp gốc nhưng WebP ra byte khác (đổi phiên bản cwebp): giữ một phiếu như cũ; lấy lại tệp cũ bằng Khôi phục.
            return duplicate(bySource.get());
        }
        int[] size = webpSize(webp);
        if (size == null) {
            throw new AppException(ErrorCode.ASSET_INVALID, "WebP hỏng — không đọc được kích thước");
        }
        String path = "a/" + sha.substring(0, 20) + ".webp";
        writeOnce(path, webp);
        createThumbnail(path);

        Asset a = new Asset();
        a.setKind(AssetKind.IMAGE);
        a.setSha256(sha);
        a.setSourceSha256(sourceSha);
        a.setPath(path);
        a.setSizeBytes((long) webp.length);
        a.setWidth(size[0]);
        a.setHeight(size[1]);
        a.setLabel(cleanLabel);
        a.setChapterCode(chapter);
        a.setUploadedBy(accountRepo.findById(callerId).orElse(null));
        a.setCreatedAt(Instant.now(clock));
        try {
            a = assetRepo.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // Hai người tải cùng một ảnh cùng lúc: UNIQUE(sha256) chặn người sau → trả bản của người trước.
            return duplicate(assetRepo.findBySha256(sha).orElseThrow(() -> e));
        }
        log.info("Tải ảnh {} ({}x{}, {} byte) bởi {}", path, size[0], size[1], webp.length, callerId);
        return new AssetUploadResponse(toResponse(a, List.of()), false);
    }

    @Override
    @Transactional
    public AssetResponse update(UUID callerId, UUID id, UpdateAssetRequest req) {
        requireWriter(callerId);
        Asset a = requireAsset(id);
        if (req != null) {
            a.setLabel(normalizeLabel(req.label(), null));
            a.setChapterCode(normalizeChapter(req.chapterCode()));
        }
        return toResponse(a, usageIndex().getOrDefault(a.getPath(), List.of()));
    }

    @Override
    @Transactional
    public void delete(UUID callerId, UUID id) {
        requireWriter(callerId);
        Asset a = requireAsset(id);
        // Đã lưu trữ → 409; đang đếm → bỏ dòng đếm cùng giao dịch (rollback theo nếu tệp hoá ra đang dùng).
        archivePolicy.onAssetDelete(a.getPath(), callerId);
        if (usageIndex().containsKey(a.getPath())) {
            throw new AppException(ErrorCode.ASSET_IN_USE, a.getPath());
        }
        assetRepo.delete(a);
        try {
            Files.deleteIfExists(dir.resolve(a.getPath()));
            Files.deleteIfExists(dir.resolve(thumbPath(a.getPath())));
        } catch (IOException e) {
            // Phiếu đã xoá; tệp sót lại vô hại (tên theo hash, không ai trỏ tới) — dọn kho đợt 4 sẽ gom.
            log.warn("Không xoá được tệp {}: {}", a.getPath(), e.getMessage());
        }
        log.info("Xoá ảnh mồ côi {} bởi {}", a.getPath(), callerId);
    }

    // ------------------------------------------------------------------ chỗ dùng
    /** path ("a/<hash>.webp") → nơi dùng. Bản đang chạy = CURRENT, bản khác = OLD, nháp chưa phát hành = DRAFT. */
    private Map<String, List<AssetUsageResponse>> usageIndex() {
        return usageIndex(Set.of(), false);
    }

    /**
     * Tập "đang dùng" của vòng đời lưu trữ (27/11): như {@link #usageIndex()} nhưng bỏ các bản phát hành đã lưu trữ, và tính cả
     * giọng biến thể đã đóng băng (personalization.voiceReady) — câu cá nhân hoá chỉ có giọng sau khi ghép nên đường dẫn không
     * nằm trong JSON; bỏ sót là giọng bé đang nghe bị coi là mồ côi.
     */
    public Map<String, List<AssetUsageResponse>> usageIndexForArchive(Set<String> archivedVersions) {
        return usageIndex(archivedVersions, true);
    }

    /** Mọi tệp một bản phát hành trỏ tới (cả giọng biến thể) — khôi phục bản thì kéo các tệp này về. */
    public Set<String> releaseFiles(GameRelease release) {
        Set<String> out = new LinkedHashSet<>();
        for (ChapterRefs c : frozenRefs.computeIfAbsent(release.getId(), this::refsOfRelease)) {
            out.addAll(c.refs().keySet());
            out.addAll(c.voiceReady().keySet());
        }
        return out;
    }

    private Map<String, List<AssetUsageResponse>> usageIndex(Set<String> skipVersions, boolean withVoiceReady) {
        Map<String, Map<String, Usage>> acc = new LinkedHashMap<>();
        UUID currentId = releaseRepo.findFirstByIsCurrentTrue().map(GameRelease::getId).orElse(null);
        for (GameRelease r : releaseRepo.findAll()) {
            if (skipVersions.contains(r.getVersion())) {
                continue;
            }
            String where = r.getId().equals(currentId) ? "CURRENT" : "OLD";
            // Cache theo id bản vẫn đúng khi bản được lưu trữ / khôi phục: JSON không đổi, chỉ khác có tính bản đó hay không.
            for (ChapterRefs c : frozenRefs.computeIfAbsent(r.getId(), this::refsOfRelease)) {
                add(acc, c.refs(), c.chapter(), where, r.getVersion());
                if (withVoiceReady) {
                    add(acc, c.voiceReady(), c.chapter(), where, r.getVersion());
                }
            }
        }
        for (Chapter c : chapterRepo.findAllByOrderByChapterNumberAsc()) {
            // Nháp RELEASED trùng nội dung bản đã phát hành — đã đếm ở trên.
            draftRepo.findTopByChapter_IdOrderByRevisionDesc(c.getId())
                    .filter(d -> d.getStatus() != DraftStatus.RELEASED)
                    .ifPresent(d -> add(acc, refs(d.getContent()), c.getCode(), "DRAFT", null));
        }
        Map<String, List<AssetUsageResponse>> out = new LinkedHashMap<>();
        acc.forEach((path, byPlace) -> out.put(path, byPlace.values().stream().map(Usage::toResponse).toList()));
        return out;
    }

    /** Bản phát hành đã đóng băng, không đổi nội dung: quét một lần rồi giữ (trước đây mỗi lần gọi quét lại mọi bản, ~5 giây). */
    private final Map<UUID, List<ChapterRefs>> frozenRefs = new ConcurrentHashMap<>();

    private record ChapterRefs(String chapter, Map<String, Set<String>> refs, Map<String, Set<String>> voiceReady) {
    }

    private List<ChapterRefs> refsOfRelease(UUID releaseId) {
        return releaseChapterRepo.findByRelease_IdOrderByChapter_ChapterNumberAsc(releaseId).stream()
                .map(rc -> new ChapterRefs(rc.getChapter().getCode(), refs(rc.getContent()), voiceReadyRefs(rc.getContent())))
                .toList();
    }

    /** Giọng biến thể đóng băng của một chương (v/<khoá>.mp3, không gắn cảnh); rỗng với chương không cá nhân hoá. */
    private Map<String, Set<String>> voiceReadyRefs(String json) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (json == null || !json.contains("\"voiceReady\"")) {
            return out;
        }
        try {
            ArchivePolicy.voiceReadyPaths(objectMapper.readTree(json)).forEach(p -> out.put(p, Set.of()));
        } catch (IOException e) {
            log.warn("JSON bản phát hành hỏng, bỏ qua voiceReady: {}", e.getMessage());
        }
        return out;
    }

    /** Đường dẫn tệp có trong JSON chương → các cảnh nhắc tới nó. */
    private Map<String, Set<String>> refs(String json) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        Matcher m = REF.matcher(json);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>());
        }
        if (out.isEmpty()) {
            return out;
        }
        JsonNode scenes;
        try {
            scenes = objectMapper.readTree(json).path("scenes");
        } catch (IOException e) {
            scenes = objectMapper.createArrayNode();
        }
        for (JsonNode s : scenes) {
            String text = s.toString();
            String id = s.path("id").asText();
            out.forEach((path, sceneIds) -> {
                if (text.contains(path)) {
                    sceneIds.add(id);
                }
            });
        }
        return out;
    }

    private void add(Map<String, Map<String, Usage>> acc, Map<String, Set<String>> refs, String chapter, String where,
                     String version) {
        refs.forEach((path, sceneIds) -> {
            Usage u = acc.computeIfAbsent(path, k -> new LinkedHashMap<>())
                    .computeIfAbsent(chapter + "|" + where, k -> new Usage(chapter, where));
            if (version != null) {
                u.versions.add(version);
            }
            u.sceneIds.addAll(sceneIds);
        });
    }

    private static final class Usage {
        final String chapter;
        final String where;
        final Set<String> versions = new LinkedHashSet<>();
        final Set<String> sceneIds = new LinkedHashSet<>();

        Usage(String chapter, String where) {
            this.chapter = chapter;
            this.where = where;
        }

        AssetUsageResponse toResponse() {
            return new AssetUsageResponse(chapter, where, List.copyOf(versions), List.copyOf(sceneIds));
        }
    }

    /** IN_USE > NEW > OLD > ORPHAN: có ở bản đang chạy là đang dùng, dù nháp hay bản cũ cũng có. */
    static String status(List<AssetUsageResponse> usage) {
        if (usage.stream().anyMatch(u -> "CURRENT".equals(u.where()))) {
            return "IN_USE";
        }
        if (usage.stream().anyMatch(u -> "DRAFT".equals(u.where()))) {
            return "NEW";
        }
        return usage.isEmpty() ? "ORPHAN" : "OLD";
    }

    // ------------------------------------------------------------------ đổi WebP + đọc kích thước
    /** Đoán loại theo byte đầu, không tin tên tệp / Content-Type do client gửi. */
    static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b.length >= 16 && "RIFF".equals(ascii(b, 0)) && "WEBP".equals(ascii(b, 8))) {
            return "webp";
        }
        return null;
    }

    /**
     * cwebp — công cụ chính chủ của libwebp (Google), một tệp chạy vài MB, không kéo gói nào (24/10: ffmpeg qua apt là 142 gói,
     * 361 MB). Chất lượng 80, nén kỹ (-m 6), bỏ EXIF; rộng quá 1920 mới thu nhỏ (giữ tỉ lệ); kênh trong suốt giữ nguyên.
     */
    private byte[] toWebp(byte[] bytes, String type) {
        int width = imageWidth(bytes);
        if (width <= 0) {
            throw new AppException(ErrorCode.ASSET_INVALID, "ảnh hỏng — không đọc được kích thước");
        }
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("asset-", "." + type);
            out = Files.createTempFile("asset-", ".webp");
            Files.write(in, bytes);
            List<String> cmd = new ArrayList<>(List.of(cwebp, "-quiet", "-q", String.valueOf(QUALITY), "-m", "6",
                    "-metadata", "none"));
            if (width > MAX_WIDTH) {
                cmd.addAll(List.of("-resize", String.valueOf(MAX_WIDTH), "0"));
            }
            cmd.addAll(List.of(in.toString(), "-o", out.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new AppException(ErrorCode.ASSET_INVALID, "không đổi được sang WebP: " + err.strip());
            }
            return Files.readAllBytes(out);
        } catch (IOException e) {
            // Không chạy được cwebp (chưa cài / sai đường dẫn app.assets.cwebp) — lỗi máy chủ, không phải lỗi tệp.
            throw new AppException(ErrorCode.ASSET_CONVERTER_UNAVAILABLE, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.ASSET_CONVERTER_UNAVAILABLE, "bị ngắt");
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    /**
     * Hình thu nhỏ t/<tên>.webp cạnh a/<tên>.webp — suy ra từ tên nên không cần cột DB. Hỏng chỉ log: ảnh gốc vẫn dùng
     * được, lần khởi động sau AssetThumbnailBackfill thử lại.
     */
    boolean createThumbnail(String path) {
        Path out = null;
        try {
            out = Files.createTempFile("thumb-", ".webp");
            Process p = new ProcessBuilder(cwebp, "-quiet", "-q", String.valueOf(THUMB_QUALITY), "-metadata", "none",
                    "-resize", String.valueOf(THUMB_WIDTH), "0", "-resize_mode", "down_only", dir.resolve(path).toString(), "-o", out.toString())
                    .redirectErrorStream(true).start();
            // Chờ trước rồi mới đọc: đọc trước sẽ treo theo cwebp, mất tác dụng của giới hạn 60 giây (-quiet nên đầu ra nhỏ).
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Không tạo được hình thu nhỏ {}: quá 60 giây", path);
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("Không tạo được hình thu nhỏ {}: {}", path,
                        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
                return false;
            }
            writeOnce(thumbPath(path), Files.readAllBytes(out));
            return true;
        } catch (IOException | AppException e) {
            log.warn("Không tạo được hình thu nhỏ {}: {}", path, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            deleteQuietly(out);
        }
    }

    /** Tạo bù hình thu nhỏ cho ảnh có trước 26/11 (gọi từ AssetThumbnailBackfill); ảnh lỗi thì log rồi đi tiếp. */
    public void backfillThumbnails() {
        Path images = dir.resolve("a");
        if (!Files.isDirectory(images)) {
            return;
        }
        int made = 0;
        int failed = 0;
        try (var files = Files.list(images)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String path = "a/" + f.getFileName();
                if (!path.endsWith(".webp") || Files.exists(dir.resolve(thumbPath(path)))) {
                    continue;
                }
                if (createThumbnail(path)) {
                    made++;
                } else {
                    failed++;
                }
            }
        } catch (IOException e) {
            log.warn("Không duyệt được {}: {}", images, e.getMessage());
        }
        log.info("Tạo bù hình thu nhỏ: {} tạo, {} lỗi", made, failed);
    }

    /** a/<tên>.webp → t/<tên>.webp. */
    private static String thumbPath(String path) {
        return "t/" + path.substring(path.indexOf('/') + 1);
    }

    /** Một Files.exists mỗi dòng — kho vài trăm ảnh, không cần cache. */
    String thumbUrl(AssetKind kind, String path) {
        if (kind != AssetKind.IMAGE) {
            return null;
        }
        String thumb = thumbPath(path);
        return Files.exists(dir.resolve(thumb)) ? assetBase + "/" + thumb : null;
    }

    /** Rộng ảnh PNG / JPEG đọc từ header bằng ImageIO (không giải mã cả ảnh); -1 nếu không đọc được. */
    static int imageWidth(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) {
                return -1;
            }
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                return r.getWidth(0);
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /** {rộng, cao} đọc từ header WebP (VP8 / VP8L / VP8X) — không cần thư viện ảnh. null nếu không đọc được. */
    static int[] webpSize(byte[] b) {
        if (b.length < 30 || !"webp".equals(sniff(b))) {
            return null;
        }
        String chunk = ascii(b, 12);
        switch (chunk) {
            case "VP8X":
                return new int[]{1 + u24(b, 24), 1 + u24(b, 27)};
            case "VP8L":
                int b0 = b[21] & 0xFF, b1 = b[22] & 0xFF, b2 = b[23] & 0xFF, b3 = b[24] & 0xFF;
                return new int[]{1 + (((b1 & 0x3F) << 8) | b0), 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))};
            case "VP8 ":
                return new int[]{u16(b, 26) & 0x3FFF, u16(b, 28) & 0x3FFF};
            default:
                return null;
        }
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int u24(byte[] b, int at) {
        return u16(b, at) | ((b[at + 2] & 0xFF) << 16);
    }

    /** URL công khai của một tệp kho. */
    String fileUrl(String path) {
        return assetBase + "/" + path;
    }

    /** Tải lại đúng tệp đã lưu trữ: ghi lại lên đĩa (cả hình thu nhỏ nếu mất) rồi đưa dòng lưu trữ về đếm ngay. */
    private void rewriteIfMissing(String path, byte[] webp) {
        if (Files.exists(dir.resolve(path))) {
            return;
        }
        writeOnce(path, webp);
        if (!Files.exists(dir.resolve(thumbPath(path)))) {
            createThumbnail(path);
        }
        archivePolicy.onFileRewritten(ArchiveKind.IMAGE, path);
        log.info("Ghi lại tệp {} từ lần tải lên trùng", path);
    }

    /** Ghi tệp một lần: đã có (cùng hash = cùng nội dung) thì thôi; ghi qua tệp tạm rồi đổi tên để không ai đọc nửa tệp. */
    private void writeOnce(String path, byte[] bytes) {
        Path target = dir.resolve(path);
        try {
            if (Files.exists(target)) {
                return;
            }
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".up-", ".tmp");
            Files.write(tmp, bytes);
            // createTempFile tạo quyền 600 → nginx (www-data) không đọc được, trả 403 (bắt được trên production 24/10).
            // Tệp kho là công khai: 644 như 123 tệp chuyển nhà 22/10. Windows (máy dev) không có quyền POSIX thì bỏ qua.
            if (Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "không ghi được tệp kho: " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // tệp tạm, hệ điều hành tự dọn
            }
        }
    }

    // ------------------------------------------------------------------ lõi
    private void requireWriter(UUID callerId) {
        if (!accountRoleRepo.existsByAccount_IdAndRoleIn(callerId, WRITERS)) {
            throw new AppException(ErrorCode.FORBIDDEN, "cần một trong các vai " + WRITERS);
        }
    }

    private Asset requireAsset(UUID id) {
        return assetRepo.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tệp " + id));
    }

    private static String normalizeChapter(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replaceFirst("^CH(\\d)$", "CH0$1");
        if (!CHAPTER.matcher(c).matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chapterCode: dạng CH04");
        }
        return c;
    }

    /** Nhãn trống thì lấy tên tệp (bỏ đuôi); tối đa 120 ký tự. */
    private static String normalizeLabel(String label, String filename) {
        String l = label != null && !label.isBlank() ? label.trim()
                : filename == null || filename.isBlank() ? null : filename.trim().replaceFirst("\\.[^.]+$", "");
        if (l != null && l.length() > 120) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "label: tối đa 120 ký tự");
        }
        return l;
    }

    private AssetUploadResponse duplicate(Asset a) {
        return new AssetUploadResponse(toResponse(a, usageIndex().getOrDefault(a.getPath(), List.of())), true);
    }

    private AssetResponse toResponse(Asset a, List<AssetUsageResponse> usage) {
        return toResponse(a, usage, archivePolicy.daysLeft(a.getPath()));
    }

    private AssetResponse toResponse(Asset a, List<AssetUsageResponse> usage, Integer archiveDaysLeft) {
        Account by = a.getUploadedBy();
        return new AssetResponse(a.getId(), true, a.getKind(), "{{ASSET_BASE}}/" + a.getPath(), assetBase + "/" + a.getPath(),
                a.getSizeBytes(), a.getWidth(), a.getHeight(), a.getLabel(), a.getChapterCode(),
                by == null ? null : by.getDisplayName(), a.getCreatedAt(), status(usage), usage,
                thumbUrl(a.getKind(), a.getPath()), archiveDaysLeft);
    }

    private AssetResponse unregistered(String path, List<AssetUsageResponse> usage, Integer archiveDaysLeft) {
        AssetKind kind = path.startsWith("v/") ? AssetKind.AUDIO : AssetKind.IMAGE;
        return new AssetResponse(null, false, kind, "{{ASSET_BASE}}/" + path, assetBase + "/" + path,
                null, null, null, null, null, null, null, status(usage), usage, thumbUrl(kind, path), archiveDaysLeft);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
