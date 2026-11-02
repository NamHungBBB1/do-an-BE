package com.doan.game.service.impl;

import com.doan.game.entity.Chapter;
import com.doan.game.entity.ChapterDraft;
import com.doan.game.entity.VoiceClip;
import com.doan.game.enums.Role;
import com.doan.game.enums.VoiceClipStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.VoiceClipRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sinh giọng đọc (Hưng 03/11; spec build/spec-kho-anh-giong-2026-10-24.md mục 4.5–4.6). Máy sinh = GitHub Actions (Hưng chốt 24/10):
 * <ol>
 *   <li>Editor bấm "Sinh giọng" → {@link #enqueue}: câu chưa có tệp khớp khoá → VoiceClip PENDING; câu đã có (cùng khoá) bỏ qua.</li>
 *   <li>BE gọi {@code workflow_dispatch} của workflow voice-render.yml (token GitHub chỉ quyền Actions của repo này).</li>
 *   <li>Runner ({@code voice/worker.py}) lấy việc {@link #jobs}, sinh bằng VieNeu, nộp mp3 {@link #submit} → tệp v/&lt;key&gt;.mp3
 *       vào kho VPS, VoiceClip DONE. Nháp tự có voiceUrl lần đọc / lưu sau ({@link VoiceKeys#attach}).</li>
 * </ol>
 * Runner xác thực bằng header {@code X-Voice-Worker-Key} (khoá chung ở /etc/finteen.env + GitHub Secrets), không dùng tài khoản.
 */
@Service
public class VoiceRenderService {

    private static final Logger log = LoggerFactory.getLogger(VoiceRenderService.class);
    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{20}$");
    private static final int MAX_MP3_BYTES = 5 * 1024 * 1024;

    public record LineStatus(String sceneId, String speakerId, String profile, String readText, String key, String status,
                             String error) {
    }

    public record ChapterVoice(String code, int lines, long ready, long missing, long pending, long failed,
                               List<LineStatus> items) {
    }

    public record EnqueueResult(int queued, int alreadyPending, int ready, boolean dispatched, String dispatchNote) {
    }

    public record Job(String key, String profile, String readText) {
    }

    private final ChapterRepository chapterRepo;
    private final ChapterDraftRepository draftRepo;
    private final VoiceClipRepository clipRepo;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository roleRepo;
    private final VoiceKeys keys;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path assetsDir;
    private final Path refsDir;
    private final String workerKey;
    private final String githubToken;
    private final String githubRepo;
    private final String workflow;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public VoiceRenderService(ChapterRepository chapterRepo, ChapterDraftRepository draftRepo, VoiceClipRepository clipRepo,
                              AccountRepository accountRepo, AccountRoleRepository roleRepo, VoiceKeys keys,
                              ObjectMapper objectMapper, Clock clock,
                              @Value("${app.assets.dir}") String assetsDir,
                              @Value("${app.voice.refs-dir}") String refsDir,
                              @Value("${app.voice.worker-key}") String workerKey,
                              @Value("${app.voice.github-token}") String githubToken,
                              @Value("${app.voice.github-repo}") String githubRepo,
                              @Value("${app.voice.workflow}") String workflow) {
        this.chapterRepo = chapterRepo;
        this.draftRepo = draftRepo;
        this.clipRepo = clipRepo;
        this.accountRepo = accountRepo;
        this.roleRepo = roleRepo;
        this.keys = keys;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.assetsDir = Paths.get(assetsDir).toAbsolutePath();
        this.refsDir = Paths.get(refsDir).toAbsolutePath();
        this.workerKey = workerKey == null ? "" : workerKey.trim();
        this.githubToken = githubToken == null ? "" : githubToken.trim();
        this.githubRepo = githubRepo;
        this.workflow = workflow;
    }

    // ------------------------------------------------------------------ nạp 52 giọng đã sinh tay (ops/voice/clips.json)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedRenderedClips() {
        JsonNode clips;
        try (InputStream in = new ClassPathResource("voice/clips.json").getInputStream()) {
            clips = objectMapper.readTree(in);
        } catch (IOException e) {
            log.warn("Không đọc được voice/clips.json: {}", e.getMessage());
            return;
        }
        Set<String> have = clipRepo.findByClipKeyIn(iterable(clips.fieldNames())).stream()
                .map(VoiceClip::getClipKey).collect(Collectors.toSet());
        int added = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = clips.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (have.contains(e.getKey())) {
                continue;
            }
            JsonNode c = e.getValue();
            VoiceClip v = new VoiceClip();
            v.setClipKey(e.getKey());
            v.setProfile(c.path("profile").asText());
            v.setModelVersion(c.path("modelVersion").asText());
            v.setReadText(c.path("text").asText());
            v.setStatus(VoiceClipStatus.DONE);
            v.setPath("v/" + e.getKey() + ".mp3");
            v.setSeconds(c.has("sec") ? BigDecimal.valueOf(c.path("sec").asDouble()) : null);
            v.setChapterCode(c.path("chapter").asText(null));
            v.setSceneId(c.path("sceneId").asText(null));
            v.setRequestedAt(Instant.now(clock));
            v.setDoneAt(Instant.now(clock));
            clipRepo.save(v);
            added++;
        }
        if (added > 0) {
            log.info("Nạp {} giọng đã sinh từ voice/clips.json vào VoiceClip", added);
        }
    }

    private static List<String> iterable(Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    // ------------------------------------------------------------------ Studio
    @Transactional(readOnly = true)
    public ChapterVoice status(String code) {
        JsonNode content = latestDraft(code);
        List<VoiceKeys.Line> lines = keys.lines(content);
        Set<String> ready = keys.readyKeys(content);
        Map<String, VoiceClip> clips = clipRepo.findByClipKeyIn(lines.stream().map(VoiceKeys.Line::key).toList()).stream()
                .collect(Collectors.toMap(VoiceClip::getClipKey, Function.identity()));
        List<LineStatus> items = new ArrayList<>();
        for (VoiceKeys.Line l : lines) {
            VoiceClip c = clips.get(l.key());
            String st = ready.contains(l.key()) ? "READY" : c == null ? "MISSING" : c.getStatus().name();
            items.add(new LineStatus(l.sceneId(), l.speakerId(), l.profile(), l.readText(), l.key(), st,
                    "FAILED".equals(st) ? c.getError() : null));
        }
        return new ChapterVoice(code, items.size(), count(items, "READY"), count(items, "MISSING"),
                count(items, "PENDING"), count(items, "FAILED"), items);
    }

    private static long count(List<LineStatus> items, String st) {
        return items.stream().filter(i -> i.status().equals(st)).count();
    }

    /** Xếp PENDING cho mọi câu chưa có tệp (MISSING, FAILED) của nháp mới nhất rồi gọi GitHub Actions. Câu đã có khoá DONE bỏ qua. */
    @Transactional
    public EnqueueResult enqueue(UUID callerId, String code) {
        if (!(roleRepo.existsByAccount_IdAndRole(callerId, Role.ADMIN)
                || roleRepo.existsByAccount_IdAndRole(callerId, Role.GAME_EDITOR))) {
            throw new AppException(ErrorCode.FORBIDDEN, "cần vai ADMIN hoặc GAME_EDITOR");
        }
        JsonNode content = latestDraft(code);
        Set<String> ready = keys.readyKeys(content);
        // Nhiều cảnh cùng một câu + cùng giọng → một khoá, sinh một lần.
        Map<String, VoiceKeys.Line> todo = new LinkedHashMap<>();
        for (VoiceKeys.Line l : keys.lines(content)) {
            if (!ready.contains(l.key())) {
                todo.putIfAbsent(l.key(), l);
            }
        }
        Map<String, VoiceClip> existing = clipRepo.findByClipKeyIn(todo.keySet()).stream()
                .collect(Collectors.toMap(VoiceClip::getClipKey, Function.identity()));
        Instant now = Instant.now(clock);
        int queued = 0;
        int pending = 0;
        for (VoiceKeys.Line l : todo.values()) {
            VoiceClip c = existing.get(l.key());
            if (c != null && c.getStatus() == VoiceClipStatus.PENDING) {
                pending++;
                continue;
            }
            if (c == null) {
                c = new VoiceClip();
                c.setClipKey(l.key());
            }
            // ponytail: hai người bấm cùng lúc cùng câu mới → UNIQUE(clip_key) chặn người sau (500); hiếm, bấm lại là xong.
            c.setProfile(l.profile());
            c.setModelVersion(keys.modelVersion());
            c.setReadText(l.readText());
            c.setStatus(VoiceClipStatus.PENDING);
            c.setError(null);
            c.setChapterCode(code);
            c.setSceneId(l.sceneId());
            c.setRequestedBy(accountRepo.findById(callerId).orElse(null));
            c.setRequestedAt(now);
            clipRepo.save(c);
            queued++;
        }
        String note = queued + pending == 0 ? "không có câu nào cần sinh" : dispatch();
        boolean dispatched = "ok".equals(note);
        log.info("Sinh giọng {}: xếp {} câu, {} câu đang chờ, {} câu đã có; dispatch {}", code, queued, pending, ready.size(), note);
        return new EnqueueResult(queued, pending, ready.size(), dispatched, note);
    }

    /** Gọi workflow_dispatch. Không ném lỗi: việc đã nằm trong hàng đợi, chạy tay workflow (hoặc máy Hưng) vẫn lấy được. */
    String dispatch() {
        if (githubToken.isEmpty()) {
            return "chưa cấu hình GITHUB_VOICE_TOKEN";
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://api.github.com/repos/" + githubRepo + "/actions/workflows/" + workflow + "/dispatches"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"ref\":\"main\"}"))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 204 ? "ok" : "GitHub trả " + res.statusCode();
        } catch (IOException e) {
            return "không gọi được GitHub: " + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "bị ngắt";
        }
    }

    private JsonNode latestDraft(String code) {
        Chapter chapter = chapterRepo.findByCode(code)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có chương " + code));
        ChapterDraft d = draftRepo.findTopByChapter_IdOrderByRevisionDesc(chapter.getId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "chương " + code + " chưa có nháp"));
        try {
            return objectMapper.readTree(d.getContent());
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "JSON nháp hỏng trong DB: " + code);
        }
    }

    // ------------------------------------------------------------------ máy sinh (GitHub Actions)
    public void requireWorker(String presented) {
        if (workerKey.isEmpty()) {
            throw new AppException(ErrorCode.FORBIDDEN, "máy chủ chưa bật máy sinh giọng (VOICE_WORKER_KEY)");
        }
        byte[] a = workerKey.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented == null ? new byte[0] : presented.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "khoá máy sinh không đúng");
        }
    }

    @Transactional(readOnly = true)
    public List<Job> jobs() {
        return clipRepo.findTop50ByStatusOrderByRequestedAtAsc(VoiceClipStatus.PENDING).stream()
                .map(c -> new Job(c.getClipKey(), c.getProfile(), c.getReadText())).toList();
    }

    /** Tệp mẫu của giọng nhân bản (người thật) — không nằm trong repo công khai; chỉ máy sinh có khoá mới tải được. */
    public byte[] ref(String profile) {
        JsonNode p = keys.config().path("profiles").path(profile);
        if (!"clone".equals(p.path("kind").asText())) {
            throw new AppException(ErrorCode.NOT_FOUND, "giọng " + profile + " không cần tệp mẫu");
        }
        Path f = refsDir.resolve(Paths.get(p.path("ref").asText()).getFileName().toString());
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            throw new AppException(ErrorCode.NOT_FOUND, "thiếu tệp mẫu giọng " + profile);
        }
    }

    @Transactional
    public void submit(String key, byte[] mp3, BigDecimal seconds) {
        VoiceClip c = requireClip(key);
        if (c.getStatus() == VoiceClipStatus.DONE) {
            return;   // runner gửi lại → giữ tệp cũ (tên = hash, không ghi đè)
        }
        if (mp3 == null || mp3.length < 4 || mp3.length > MAX_MP3_BYTES || !isMp3(mp3)) {
            throw new AppException(ErrorCode.ASSET_INVALID, "giọng phải là mp3, tối đa 5 MB");
        }
        String path = "v/" + key + ".mp3";
        writeOnce(path, mp3);
        c.setStatus(VoiceClipStatus.DONE);
        c.setPath(path);
        c.setSeconds(seconds);
        c.setError(null);
        c.setDoneAt(Instant.now(clock));
        log.info("Nộp giọng {} ({} byte, {} s)", key, mp3.length, seconds);
    }

    @Transactional
    public void fail(String key, String error) {
        VoiceClip c = requireClip(key);
        if (c.getStatus() == VoiceClipStatus.DONE) {
            return;
        }
        c.setStatus(VoiceClipStatus.FAILED);
        String e = error == null || error.isBlank() ? "không rõ lỗi" : error.trim();
        c.setError(e.length() > 255 ? e.substring(0, 255) : e);
        log.warn("Sinh giọng {} lỗi: {}", key, c.getError());
    }

    private VoiceClip requireClip(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "key: 20 ký tự hex");
        }
        return clipRepo.findByClipKey(key).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có việc " + key));
    }

    /** ID3 ở đầu, hoặc khung MPEG (11 bit đồng bộ). */
    static boolean isMp3(byte[] b) {
        return (b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0);
    }

    /** Như AssetServiceImpl.writeOnce: tên = hash, có rồi thì thôi, ghi tạm rồi đổi tên, quyền 644 cho nginx. */
    private void writeOnce(String path, byte[] bytes) {
        Path target = assetsDir.resolve(path);
        try {
            if (Files.exists(target)) {
                return;
            }
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".up-", ".tmp");
            Files.write(tmp, bytes);
            if (Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "không ghi được tệp kho: " + e.getMessage());
        }
    }
}
