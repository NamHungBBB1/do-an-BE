package com.doan.game.service.impl;

import com.doan.game.DTO.request.CreateReleaseRequest;
import com.doan.game.DTO.request.ReviewDraftRequest;
import com.doan.game.DTO.request.SubmitDraftRequest;
import com.doan.game.DTO.response.ChapterSummaryResponse;
import com.doan.game.DTO.response.DraftSummaryResponse;
import com.doan.game.DTO.response.ReleaseSummaryResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.Chapter;
import com.doan.game.entity.ChapterDraft;
import com.doan.game.entity.GameRelease;
import com.doan.game.entity.ReleaseChapter;
import com.doan.game.enums.DraftStatus;
import com.doan.game.enums.Role;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.service.ContentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ruột nội dung game, giai đoạn 1 (09/10). Nội dung lưu NGUYÊN hình dạng JSON của FE
 * (chapterNV2VisualNovel.js: id, title, initialStats, sprites, scenes[], endingRules…) — BE không hiểu
 * từng trường, chỉ kiểm phần cấu trúc dùng để chuyển cảnh và thay tiền tố kho file lúc trả.
 *
 * Đường dẫn ảnh / giọng trong JSON lưu dạng {{ASSET_BASE}}/a/<hash>.webp: bản phát hành vẫn đóng băng
 * đúng tệp nào, còn đổi domain CDN chỉ đổi app.assets.base-url, không phải phát hành lại.
 */
@Service
public class ContentServiceImpl implements ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentServiceImpl.class);
    static final String ASSET_PLACEHOLDER = "{{ASSET_BASE}}";
    private static final Pattern CODE = Pattern.compile("^CH(\\d{1,2})$");
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    /** Các trường trỏ sang cảnh khác, theo đúng data FE (nextSceneId, onPass/onFail, if/else, option.nextSceneId). */
    private static final String[] SCENE_REFS = {
            "nextSceneId", "onPassSceneId", "onFailSceneId", "ifTrueSceneId", "ifFalseSceneId"};
    /** JSON một chương FE hiện ~20–35 KB; chặn 2 MB để không ai nhét ảnh base64 vào DB. */
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private final ChapterRepository chapterRepo;
    private final ChapterDraftRepository draftRepo;
    private final GameReleaseRepository releaseRepo;
    private final ReleaseChapterRepository releaseChapterRepo;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String assetBase;

    public ContentServiceImpl(ChapterRepository chapterRepo, ChapterDraftRepository draftRepo,
                              GameReleaseRepository releaseRepo, ReleaseChapterRepository releaseChapterRepo,
                              AccountRepository accountRepo, AccountRoleRepository accountRoleRepo,
                              ObjectMapper objectMapper, Clock clock,
                              @Value("${app.assets.base-url}") String assetBase) {
        this.chapterRepo = chapterRepo;
        this.draftRepo = draftRepo;
        this.releaseRepo = releaseRepo;
        this.releaseChapterRepo = releaseChapterRepo;
        this.accountRepo = accountRepo;
        this.accountRoleRepo = accountRoleRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.assetBase = assetBase.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ đọc (công khai)
    @Override
    @Transactional(readOnly = true)
    public ReleaseSummaryResponse currentRelease() {
        return toSummary(requireCurrent());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReleaseSummaryResponse> listReleases() {
        return releaseRepo.findAllByOrderByReleasedAtDesc().stream().map(this::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode releaseChapter(String version, String code) {
        GameRelease release = releaseRepo.findByVersion(version)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có phiên bản " + version));
        return chapterOf(release, code);
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode currentChapter(String code) {
        return chapterOf(requireCurrent(), code);
    }

    // ------------------------------------------------------------------ ghi (Studio, theo vai — PR B 21/10)
    @Override
    @Transactional
    public ChapterSummaryResponse saveDraft(UUID callerId, String code, JsonNode content) {
        requireAnyRole(callerId, Role.ADMIN, Role.GAME_EDITOR);
        int number = parseChapterNumber(code);
        String json = validateAndNormalize(content);
        Instant now = Instant.now(clock);
        Account editor = accountRepo.findById(callerId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "account " + callerId));

        Chapter chapter = chapterRepo.findByCode(code).orElseGet(() -> {
            Chapter c = new Chapter();
            c.setCode(code);
            c.setChapterNumber(number);
            c.setCreatedAt(now);
            return c;
        });
        chapter.setTitle(content.path("title").asText(chapter.getTitle() == null ? code : chapter.getTitle()));
        Chapter saved = chapterRepo.save(chapter);

        // Một bản nháp sống mỗi chương, lưu đè. Nháp đã vào bản phát hành vẫn giữ nguyên ở ReleaseChapter
        // (đóng băng), nên đè nháp không đụng tới thứ trẻ đang chơi. Đang chờ duyệt thì khoá: Reviewer phải
        // duyệt đúng thứ đã gửi. Sửa nháp REJECTED / APPROVED thì về DRAFT — phiếu duyệt cũ hết giá trị.
        ChapterDraft draft = draftRepo.findTopByChapter_IdOrderByUpdatedAtDesc(saved.getId()).orElseGet(() -> {
            ChapterDraft d = new ChapterDraft();
            d.setChapter(saved);
            d.setCreatedAt(now);
            d.setRevision(0);
            return d;
        });
        if (draft.getStatus() == DraftStatus.SUBMITTED) {
            throw new AppException(ErrorCode.DRAFT_STATE_CONFLICT, code + " đang chờ duyệt — rút lại rồi mới sửa");
        }
        draft.setContent(json);
        draft.setStatus(DraftStatus.DRAFT);
        draft.setEditor(editor);
        draft.setRevision(draft.getRevision() + 1);
        draft.setUpdatedAt(now);
        draftRepo.save(draft);
        log.info("Lưu nháp chương {} (revision {}) bởi {}", code, draft.getRevision(), callerId);
        return new ChapterSummaryResponse(saved.getCode(), saved.getChapterNumber(), saved.getTitle());
    }

    @Override
    @Transactional
    public ReleaseSummaryResponse publish(UUID adminId, CreateReleaseRequest req) {
        requireAnyRole(adminId, Role.ADMIN, Role.GAME_MANAGER);
        String version = req == null || req.version() == null ? "" : req.version().trim();
        if (!SEMVER.matcher(version).matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "version: dạng MAJOR.MINOR.PATCH, vd 1.0.1");
        }
        if (releaseRepo.findByVersion(version).isPresent()) {
            throw new AppException(ErrorCode.RELEASE_VERSION_TAKEN, version);
        }
        List<Chapter> chapters = chapterRepo.findAllByOrderByChapterNumberAsc();
        List<ChapterDraft> drafts = new ArrayList<>();
        for (Chapter c : chapters) {
            draftRepo.findTopByChapter_IdOrderByUpdatedAtDesc(c.getId()).ifPresent(drafts::add);
        }
        if (drafts.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "chưa có bản nháp chương nào để phát hành");
        }
        Instant now = Instant.now(clock);
        Account admin = accountRepo.findById(adminId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "account " + adminId));

        // Đóng băng: chép nguyên JSON nháp sang ReleaseChapter kèm hash; nháp sửa tiếp không ảnh hưởng.
        GameRelease release = new GameRelease();
        release.setVersion(version);
        release.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        release.setReleasedBy(admin);
        release.setReleasedAt(now);
        release.setCurrent(false);
        release = releaseRepo.saveAndFlush(release);
        for (ChapterDraft d : drafts) {
            ReleaseChapter rc = new ReleaseChapter();
            rc.setRelease(release);
            rc.setChapter(d.getChapter());
            rc.setContent(d.getContent());
            rc.setContentHash(sha256(d.getContent()));
            releaseChapterRepo.save(rc);
        }
        moveCurrentPointer(release);
        log.info("Phát hành {} ({} chương) bởi {}", version, drafts.size(), adminId);
        return toSummary(release);
    }

    @Override
    @Transactional
    public ReleaseSummaryResponse setCurrent(UUID adminId, String version) {
        requireAnyRole(adminId, Role.ADMIN, Role.GAME_MANAGER);
        GameRelease release = releaseRepo.findByVersion(version)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có phiên bản " + version));
        moveCurrentPointer(release);
        log.info("Đổi bản hiện tại sang {} bởi {}", version, adminId);
        return toSummary(release);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DraftSummaryResponse> listDrafts() {
        // ponytail: một truy vấn mỗi chương (8 chương); gộp thành một query nếu số chương lớn lên.
        List<DraftSummaryResponse> out = new ArrayList<>();
        for (Chapter c : chapterRepo.findAllByOrderByChapterNumberAsc()) {
            draftRepo.findTopByChapter_IdOrderByUpdatedAtDesc(c.getId()).ifPresent(d -> out.add(toDraftSummary(d)));
        }
        return out;
    }

    @Override
    @Transactional
    public DraftSummaryResponse submitDraft(UUID callerId, String code, SubmitDraftRequest req) {
        requireAnyRole(callerId, Role.ADMIN, Role.GAME_EDITOR);
        ChapterDraft draft = requireDraft(code);
        requireStatus(draft, DraftStatus.DRAFT, "chỉ gửi duyệt được nháp DRAFT (bị trả về thì sửa trước)");
        if (req == null || req.changeClass() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "changeClass: PATCH, MINOR hoặc MAJOR");
        }
        draft.setChangeClass(req.changeClass());
        if (req.note() != null && !req.note().isBlank()) {
            draft.setChangeNote(req.note().trim());
        }
        draft.setStatus(DraftStatus.SUBMITTED);
        draft.setSubmittedAt(Instant.now(clock));
        log.info("Gửi duyệt {} (revision {}, {}) bởi {}", code, draft.getRevision(), req.changeClass(), callerId);
        return toDraftSummary(draftRepo.save(draft));
    }

    @Override
    @Transactional
    public DraftSummaryResponse withdrawDraft(UUID callerId, String code) {
        requireAnyRole(callerId, Role.ADMIN, Role.GAME_EDITOR);
        ChapterDraft draft = requireDraft(code);
        requireStatus(draft, DraftStatus.SUBMITTED, "chỉ rút được nháp đang chờ duyệt");
        if (draft.getEditor() == null || !draft.getEditor().getId().equals(callerId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "chỉ người sửa nháp mới rút lại được");
        }
        draft.setStatus(DraftStatus.DRAFT);
        return toDraftSummary(draftRepo.save(draft));
    }

    @Override
    @Transactional
    public DraftSummaryResponse reviewDraft(UUID callerId, String code, ReviewDraftRequest req) {
        requireAnyRole(callerId, Role.ADMIN, Role.GAME_REVIEWER);
        ChapterDraft draft = requireDraft(code);
        requireStatus(draft, DraftStatus.SUBMITTED, "chỉ duyệt được nháp đang chờ duyệt");
        if (req == null || req.pass() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "pass: true (duyệt) hoặc false (trả về)");
        }
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (!req.pass() && note == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "note: trả về phải ghi lý do");
        }
        // Duyệt hai người: so với người lưu nháp cuối cùng, kể cả khi người duyệt là ADMIN.
        if (draft.getEditor() != null && draft.getEditor().getId().equals(callerId)) {
            throw new AppException(ErrorCode.SELF_REVIEW_FORBIDDEN, code);
        }
        Account reviewer = accountRepo.findById(callerId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "account " + callerId));
        if (req.changeClass() != null) {
            draft.setChangeClass(req.changeClass());
        }
        draft.setStatus(req.pass() ? DraftStatus.APPROVED : DraftStatus.REJECTED);
        draft.setReviewer(reviewer);
        draft.setReviewNote(note);
        draft.setReviewedAt(Instant.now(clock));
        draft.setReviewedRevision(draft.getRevision());
        log.info("Duyệt {} (revision {}) → {} bởi {}", code, draft.getRevision(), draft.getStatus(), callerId);
        return toDraftSummary(draftRepo.save(draft));
    }

    // ------------------------------------------------------------------ lõi
    /** Vai tra DB (AccountRole), không tin JWT — cùng cách QuestionBankServiceImpl. */
    private void requireAnyRole(UUID callerId, Role... roles) {
        for (Role r : roles) {
            if (accountRoleRepo.existsByAccount_IdAndRole(callerId, r)) {
                return;
            }
        }
        throw new AppException(ErrorCode.FORBIDDEN, "cần một trong các vai " + Arrays.toString(roles));
    }

    private ChapterDraft requireDraft(String code) {
        Chapter chapter = chapterRepo.findByCode(code)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có chương " + code));
        return draftRepo.findTopByChapter_IdOrderByUpdatedAtDesc(chapter.getId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "chương " + code + " chưa có nháp"));
    }

    private static void requireStatus(ChapterDraft draft, DraftStatus expected, String message) {
        if (draft.getStatus() != expected) {
            throw new AppException(ErrorCode.DRAFT_STATE_CONFLICT, message + " (đang " + draft.getStatus() + ")");
        }
    }

    private static DraftSummaryResponse toDraftSummary(ChapterDraft d) {
        Chapter c = d.getChapter();
        Account editor = d.getEditor();
        Account reviewer = d.getReviewer();
        return new DraftSummaryResponse(c.getCode(), c.getChapterNumber(), c.getTitle(), d.getStatus(),
                d.getChangeClass(), d.getRevision(), d.getChangeNote(),
                editor == null ? null : editor.getId(), editor == null ? null : editor.getDisplayName(),
                d.getSubmittedAt(),
                reviewer == null ? null : reviewer.getId(), reviewer == null ? null : reviewer.getDisplayName(),
                d.getReviewNote(), d.getReviewedAt(), d.getReviewedRevision(), d.getUpdatedAt());
    }

    private GameRelease requireCurrent() {
        return releaseRepo.findFirstByIsCurrentTrue()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "chưa phát hành bản nào"));
    }

    private JsonNode chapterOf(GameRelease release, String code) {
        ReleaseChapter rc = releaseChapterRepo.findByRelease_IdAndChapter_Code(release.getId(), code)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "phiên bản " + release.getVersion() + " không có chương " + code));
        try {
            // Thay tiền tố kho file lúc trả: JSON đóng băng giữ placeholder, đổi domain không cần phát hành lại.
            JsonNode node = objectMapper.readTree(rc.getContent().replace(ASSET_PLACEHOLDER, assetBase));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("releaseVersion", release.getVersion());
            return node;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "JSON chương hỏng trong DB: " + code);
        }
    }

    /** Đúng một bản isCurrent = true. */
    private void moveCurrentPointer(GameRelease target) {
        for (GameRelease r : releaseRepo.findByIsCurrentTrue()) {
            if (!r.getId().equals(target.getId())) {
                r.setCurrent(false);
                releaseRepo.save(r);
            }
        }
        target.setCurrent(true);
        releaseRepo.save(target);
    }

    private ReleaseSummaryResponse toSummary(GameRelease r) {
        List<ChapterSummaryResponse> chapters = releaseChapterRepo
                .findByRelease_IdOrderByChapter_ChapterNumberAsc(r.getId()).stream()
                .map(rc -> new ChapterSummaryResponse(rc.getChapter().getCode(),
                        rc.getChapter().getChapterNumber(), rc.getChapter().getTitle()))
                .toList();
        return new ReleaseSummaryResponse(r.getVersion(), r.getReleasedAt(), r.getNote(), r.isCurrent(), chapters);
    }

    static int parseChapterNumber(String code) {
        Matcher m = code == null ? null : CODE.matcher(code.trim().toUpperCase());
        if (m == null || !m.matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "code: dạng CH01…CH99");
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * Kiểm phần BE phải hiểu: scenes[] không rỗng, id không trùng, mọi tham chiếu cảnh (nextSceneId, onPass /
     * onFail, ifTrue / ifFalse, options[].nextSceneId) trỏ tới cảnh có thật. Đây chính là ràng buộc "next trỏ
     * phong-ngủ không tồn tại" trong bài CMS 08/10. Phần còn lại giữ nguyên, không đoán.
     */
    String validateAndNormalize(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "content: phải là JSON object của một chương");
        }
        JsonNode scenes = root.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "scenes: phải là mảng không rỗng");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode s : scenes) {
            String id = s.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "scenes[]: mỗi cảnh phải có id");
            }
            if (!ids.add(id)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "scenes: id trùng " + id);
            }
        }
        for (JsonNode s : scenes) {
            String id = s.path("id").asText();
            for (String ref : SCENE_REFS) {
                requireScene(ids, s.get(ref), id + "." + ref);
            }
            JsonNode options = s.path("options");
            if (options.isArray()) {
                for (JsonNode o : options) {
                    requireScene(ids, o.get("nextSceneId"), id + ".options[].nextSceneId");
                }
            }
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "content: không ghi được JSON");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "content: quá 2 MB — ảnh / giọng để ở kho file");
        }
        return json;
    }

    private static void requireScene(Set<String> ids, JsonNode ref, String where) {
        if (ref == null || ref.isNull()) {
            return;
        }
        if (!ref.isTextual() || !ids.contains(ref.asText())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, where + ": cảnh '" + ref.asText() + "' không tồn tại");
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
