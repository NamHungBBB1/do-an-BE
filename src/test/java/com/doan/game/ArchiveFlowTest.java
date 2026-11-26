package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.entity.ArchiveItem;
import com.doan.game.entity.ChoiceEvent;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.ArchiveAction;
import com.doan.game.enums.ArchiveKind;
import com.doan.game.enums.ArchiveState;
import com.doan.game.enums.ChangeClass;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.Role;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ArchiveItemRepository;
import com.doan.game.repository.ArchiveLogRepository;
import com.doan.game.repository.AssetRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.ChoiceEventRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.PickEventRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.service.impl.ArchivePolicy;
import com.doan.game.service.impl.ArchiveServiceImpl;
import com.doan.game.service.impl.AssetImageTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vòng đời lưu trữ (27/11, spec build/spec-luu-tru-be-2026-11-27.md mục 9): chế độ, job, máy lưu trữ, Studio, 6 chốt chặn.
 * Đi qua HTTP thật; thời hạn kiểm bằng cách đẩy dueAt / claimedAt về quá khứ qua repository (không sleep). Lịch đêm tắt
 * (cron "-"), chế độ đổi giữa chừng trên bean ArchivePolicy. Tệp kho là byte giả: BE chỉ liệt kê / kiểm tồn tại / đọc cỡ.
 */
@SpringBootTest(properties = {"app.assets.dir=target/test-archive-assets", "app.assets.thumb-backfill=false",
        "app.archive.cron=-", "app.archive.worker-key=" + ArchiveFlowTest.KEY,
        "app.archive.status-file=target/test-archive-status.json"})
@AutoConfigureMockMvc
class ArchiveFlowTest {

    static final String KEY = "test-archive-worker-key";
    private static final String HEADER = "X-Archive-Worker-Key";
    private static final Path DIR = Path.of("target/test-archive-assets");
    private static final Path STATUS = Path.of("target/test-archive-status.json");
    private static final String SHARED = "a/00000000000000000000.webp";   // bản đang chạy luôn trỏ tới

    @Autowired MockMvc mvc;
    @Autowired Environment env;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired AssetRepository assetRepo;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;
    @Autowired ArchiveItemRepository itemRepo;
    @Autowired ArchiveLogRepository logRepo;
    @Autowired ChoiceEventRepository choiceRepo;
    @Autowired PickEventRepository pickRepo;
    @Autowired LearnerGroupRepository groupRepo;
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired ArchivePolicy policy;
    @Autowired com.doan.game.service.impl.VoiceKeys voiceKeys;
    @Autowired com.doan.game.repository.VoiceClipRepository clipRepo;
    @Autowired ArchiveServiceImpl archiveService;
    private final ObjectMapper json = new ObjectMapper();
    private ContentTestSupport.Repos repos;
    private RequestPostProcessor admin;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        repos = new ContentTestSupport.Repos(releaseChapterRepo, releaseRepo, draftRepo, chapterRepo);
        admin = as(Role.ADMIN);
    }

    /** Cùng một H2 cho mọi lớp test: dọn cả khi xong để dòng lưu trữ không chặn nháp của lớp khác. */
    @AfterEach
    void clean() throws Exception {
        setMode(ArchivePolicy.Mode.OFF);
        ReflectionTestUtils.setField(target(), "workerKey", KEY);
        ReflectionTestUtils.setField(target(), "keepReleases", 4);
        logRepo.deleteAll();
        itemRepo.deleteAll();
        // Lô chơi của lớp khác cùng số bản (1.0.0…) làm dời đồng hồ bản phát hành của lớp này.
        choiceRepo.deleteAll();
        pickRepo.deleteAll();
        assetRepo.deleteAll();
        Files.deleteIfExists(STATUS);
        if (Files.exists(DIR)) {
            try (var files = Files.walk(DIR)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ------------------------------------------------------------------ 1. OFF
    @Test
    void offModeChangesNothing() throws Exception {
        assertThat(env.getProperty("app.archive.mode")).as("mặc định khi deploy").isEqualTo("OFF");
        assertThat(ReflectionTestUtils.invokeMethod(ArchivePolicy.class, "parseMode", env.getProperty("app.archive.mode"))
                .toString()).isEqualTo("OFF");
        String orphan = file("a", 1);
        publishFresh(SHARED);

        archiveService.nightly();   // lịch đêm: OFF thì không chạy
        assertThat(itemRepo.count()).isZero();
        mvc.perform(post("/api/studio/archive/run").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6014));
        assertThat(itemRepo.count()).isZero();

        JsonNode summary = result(mvc.perform(get("/api/studio/archive/summary").with(admin)).andExpect(status().isOk()));
        assertThat(summary.path("enabled").asBoolean()).isFalse();
        assertThat(summary.path("dueSoonCount").asLong()).isZero();
        assertThat(summary.path("lastSync").isMissingNode() || summary.path("lastSync").isNull()).isTrue();
        for (String p : List.of("/due-soon", "/archived", "/log")) {
            assertThat(result(mvc.perform(get("/api/studio/archive" + p).with(admin)).andExpect(status().isOk())).size())
                    .as(p).isZero();
        }
        assertThat(claim(10)).isEmpty();

        // Có dòng sót lại (vd từng chạy thử) mà chế độ OFF: claim vẫn rỗng, kho ảnh không hiện số ngày.
        seed(ArchiveKind.IMAGE, orphan, ArchiveState.COUNTING, Instant.now().minus(Duration.ofDays(1)));
        assertThat(claim(10)).isEmpty();
        assertThat(item(orphan).getState()).isEqualTo(ArchiveState.COUNTING);
        JsonNode asset = result(mvc.perform(multipart("/api/studio/assets")
                .file(new MockMultipartFile("file", "z.webp", null, AssetImageTest.VP8)).with(admin))).path("asset");
        String path = asset.path("ref").asText().substring("{{ASSET_BASE}}/".length());
        seed(ArchiveKind.IMAGE, path, ArchiveState.COUNTING, Instant.now().plus(Duration.ofDays(3)));
        JsonNode row = assetRow(path);
        assertThat(row.has("archiveDaysLeft")).isTrue();
        assertThat(row.path("archiveDaysLeft").isNull()).isTrue();
        // Lưu nháp / phát hành vẫn như cũ.
        mvc.perform(put("/api/studio/chapters/CH02/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(chapter("Chương 2", path))).andExpect(status().isOk());

        // Giá trị lạ → OFF.
        assertThat(ReflectionTestUtils.invokeMethod(ArchivePolicy.class, "parseMode", "bat-het").toString()).isEqualTo("OFF");
        assertThat(ReflectionTestUtils.invokeMethod(ArchivePolicy.class, "parseMode", "").toString()).isEqualTo("OFF");
    }

    // ------------------------------------------------------------------ 2. DRY_RUN
    @Test
    void dryRunCountsButNeverHandsOutWorkOrArchivesReleases() throws Exception {
        file("a", 0);
        String orphan = file("a", 1);
        publishFresh(SHARED);
        publishNext("CH01", chapter("Chương 1 bản 2", SHARED));
        setMode(ArchivePolicy.Mode.DRY_RUN);
        ReflectionTestUtils.setField(target(), "keepReleases", 1);

        JsonNode run = runJob();
        assertThat(run.path("aborted").asBoolean()).isFalse();
        assertThat(item(orphan).getState()).isEqualTo(ArchiveState.COUNTING);
        ArchiveItem release = itemRepo.findByKindAndItemRef(ArchiveKind.RELEASE, "1.0.0").orElseThrow();
        assertThat(release.getState()).isEqualTo(ArchiveState.COUNTING);

        due(ArchiveKind.IMAGE, orphan);
        due(ArchiveKind.RELEASE, "1.0.0");
        assertThat(claim(10)).isEmpty();
        runJob();
        assertThat(item(orphan).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(itemRepo.findByKindAndItemRef(ArchiveKind.RELEASE, "1.0.0").orElseThrow().getState())
                .isEqualTo(ArchiveState.COUNTING);
        // DRY_RUN vẫn cho xem "sắp lưu trữ".
        assertThat(result(mvc.perform(get("/api/studio/archive/due-soon").with(as(Role.GAME_REVIEWER)))).size()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 3. ON, trọn vòng một ảnh
    @Test
    void imageFullCycle() throws Exception {
        publishFresh(SHARED);
        file("a", 0);   // SHARED có trên đĩa
        JsonNode asset = result(mvc.perform(multipart("/api/studio/assets")
                .file(new MockMultipartFile("file", "phong.webp", null, AssetImageTest.VP8)).with(admin))).path("asset");
        String path = asset.path("ref").asText().substring("{{ASSET_BASE}}/".length());
        Path thumb = DIR.resolve("t").resolve(path.substring(2));
        Files.createDirectories(thumb.getParent());
        Files.write(thumb, AssetImageTest.VP8);   // không phụ thuộc cwebp
        setMode(ArchivePolicy.Mode.ON);

        JsonNode run = runJob();
        assertThat(run.path("started").asInt()).isEqualTo(1);
        assertThat(item(path).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(item(SHARED)).isNull();
        assertThat(assetRow(path).path("archiveDaysLeft").asInt()).isEqualTo(30);

        due(ArchiveKind.IMAGE, path);
        JsonNode soon = result(mvc.perform(get("/api/studio/archive/due-soon").with(admin)));
        assertThat(soon.get(0).path("ref").asText()).isEqualTo(path);
        assertThat(soon.get(0).path("daysLeft").asInt()).isZero();
        assertThat(soon.get(0).path("label").asText()).isEqualTo("phong");

        List<JsonNode> work = claim(10);
        assertThat(work).hasSize(1);
        assertThat(work.get(0).path("path").asText()).isEqualTo(path);
        assertThat(work.get(0).path("kind").asText()).isEqualTo("IMAGE");
        String id = work.get(0).path("id").asText();
        assertThat(item(path).getState()).isEqualTo(ArchiveState.ARCHIVING);
        assertThat(claim(10)).as("đã nhận thì không phát lại").isEmpty();

        Files.delete(DIR.resolve(path));   // máy lưu trữ gỡ tệp rồi mới báo
        worker(post("/api/archive-worker/items/" + id + "/archived")).andExpect(status().isOk());
        assertThat(item(path).getState()).isEqualTo(ArchiveState.ARCHIVED);
        worker(post("/api/archive-worker/items/" + id + "/archived")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6013));

        JsonNode list = result(mvc.perform(get("/api/studio/assets").with(admin)));
        for (JsonNode a : list) {
            assertThat(a.path("ref").asText()).doesNotContain(path);
        }
        JsonNode archived = result(mvc.perform(get("/api/studio/archive/archived").with(admin)));
        assertThat(archived.get(0).path("ref").asText()).isEqualTo(path);
        assertThat(archived.get(0).path("state").asText()).isEqualTo("ARCHIVED");
        assertThat(archived.get(0).path("url").isNull()).isTrue();
        assertThat(archived.get(0).path("thumbUrl").asText()).endsWith("/t/" + path.substring(2));
        JsonNode summary = result(mvc.perform(get("/api/studio/archive/summary").with(admin)));
        assertThat(summary.path("archivedCount").asLong()).isEqualTo(1);
        assertThat(summary.path("archivedBytes").asLong()).isEqualTo(AssetImageTest.VP8.length);

        RequestPostProcessor editor = as(Role.GAME_EDITOR);
        mvc.perform(post("/api/studio/archive/" + id + "/restore").with(editor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state").value("RESTORING"));
        mvc.perform(post("/api/studio/archive/" + id + "/restore").with(editor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state").value("RESTORING"));
        JsonNode restores = result(worker(get("/api/archive-worker/restores")));
        assertThat(restores.get(0).path("path").asText()).isEqualTo(path);

        worker(post("/api/archive-worker/items/" + id + "/restored")).andExpect(status().isConflict());   // tệp chưa về
        Files.write(DIR.resolve(path), AssetImageTest.VP8);
        Instant before = Instant.now();
        worker(post("/api/archive-worker/items/" + id + "/restored")).andExpect(status().isOk());
        ArchiveItem back = item(path);
        assertThat(back.getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(back.getCountdownFrom()).isAfterOrEqualTo(before);
        assertThat(back.getDueAt()).isAfter(before.plus(Duration.ofDays(29)));
        assertThat(logRepo.existsByAction(ArchiveAction.RESTORED)).isTrue();
    }

    // ------------------------------------------------------------------ 4. chốt chặn số 5
    @Test
    void claimRechecksUsageAtThatMoment() throws Exception {
        file("a", 0);
        String orphan = file("a", 1);
        publishFresh(SHARED);
        setMode(ArchivePolicy.Mode.ON);
        runJob();
        due(ArchiveKind.IMAGE, orphan);
        // Vừa gắn vào nháp trước lúc máy lưu trữ hỏi việc.
        mvc.perform(put("/api/studio/chapters/CH02/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(chapter("Chương 2", orphan))).andExpect(status().isOk());

        assertThat(claim(10)).isEmpty();
        assertThat(item(orphan)).as("dòng bị xoá").isNull();
        assertThat(logRepo.existsByAction(ArchiveAction.COUNT_CLEAR)).isTrue();
    }

    // ------------------------------------------------------------------ 5. phanh an toàn
    @Test
    void safetyBrakeStopsJobWhenUsageLooksWrong() throws Exception {
        String a = file("a", 1);
        publishFresh((String) null);   // bản đang chạy không trỏ tới tệp nào
        setMode(ArchivePolicy.Mode.ON);
        JsonNode run = runJob();
        assertThat(run.path("aborted").asBoolean()).isTrue();
        assertThat(itemRepo.count()).isZero();
        assertThat(logRepo.existsByAction(ArchiveAction.JOB_ABORTED)).isTrue();
        // claim cũng phanh: dòng tới hạn vẫn nguyên.
        seed(ArchiveKind.IMAGE, a, ArchiveState.COUNTING, Instant.now().minus(Duration.ofDays(1)));
        assertThat(claim(10)).isEmpty();
        assertThat(item(a).getState()).isEqualTo(ArchiveState.COUNTING);
    }

    @Test
    void safetyBrakeStopsJobOnTooManyNewOrphans() throws Exception {
        file("a", 0);
        String first = file("a", 1);
        publishFresh(SHARED);
        setMode(ArchivePolicy.Mode.ON);
        assertThat(runJob().path("started").asInt()).as("lượt đầu được phép").isEqualTo(1);

        file("a", 2);
        file("a", 3);
        file("v", 4);   // 3 mồ côi mới / 5 tệp > 0,5
        logRepo.deleteAll();
        JsonNode run = runJob();
        assertThat(run.path("aborted").asBoolean()).isTrue();
        assertThat(run.path("note").asText()).contains("3 / 5");
        assertThat(itemRepo.count()).isEqualTo(1);
        assertThat(item(first).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(logRepo.existsByAction(ArchiveAction.JOB_ABORTED)).isTrue();
    }

    // ------------------------------------------------------------------ 6. Giữ lại
    @Test
    void keepRestartsCountdown() throws Exception {
        file("a", 0);
        String orphan = file("a", 1);
        publishFresh(SHARED);
        setMode(ArchivePolicy.Mode.ON);
        runJob();
        due(ArchiveKind.IMAGE, orphan);
        ArchiveItem row = item(orphan);
        Instant before = Instant.now();
        RequestPostProcessor reviewer = as(Role.GAME_REVIEWER);
        JsonNode kept = result(mvc.perform(post("/api/studio/archive/" + row.getId() + "/keep").with(reviewer))
                .andExpect(status().isOk()));
        assertThat(kept.path("keptByName").asText()).isEqualTo("GAME_REVIEWER");
        assertThat(kept.path("daysLeft").asInt()).isEqualTo(30);
        ArchiveItem after = item(orphan);
        assertThat(after.getDueAt()).isAfter(before.plus(Duration.ofDays(29)));
        assertThat(after.getKeptBy()).isNotNull();
        assertThat(after.getKeptAt()).isAfterOrEqualTo(before);
        assertThat(logRepo.existsByAction(ArchiveAction.KEEP)).isTrue();

        after.setState(ArchiveState.ARCHIVED);
        itemRepo.save(after);
        mvc.perform(post("/api/studio/archive/" + row.getId() + "/keep").with(reviewer))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6013));
        mvc.perform(post("/api/studio/archive/" + UUID.randomUUID() + "/keep").with(reviewer)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 7. chốt chặn số 6
    @Test
    void archivedThingsCannotComeBackIntoUse() throws Exception {
        file("a", 0);
        publishFresh(SHARED);
        String latest = publishNext("CH01", chapter("Chương 1 bản 2", SHARED));
        assertThat(latest).isEqualTo("1.0.1");

        // Nháp trỏ tới tệp đã lưu trữ.
        String gone = file("a", 1);
        seed(ArchiveKind.IMAGE, gone, ArchiveState.ARCHIVED, null);
        JsonNode err = json.readTree(mvc.perform(put("/api/studio/chapters/CH02/draft").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(chapter("Chương 2", gone)))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(err.path("code").asInt()).isEqualTo(6011);
        assertThat(err.path("result").get(0).asText()).isEqualTo(gone);
        assertThat(err.path("message").asText()).contains("khôi phục").doesNotContain("rclone").doesNotContain("Drive");

        // Gửi duyệt nháp đã lưu lúc tệp còn đang đếm, sau đó tệp bị lưu trữ.
        String later = file("a", 2);
        mvc.perform(put("/api/studio/chapters/CH03/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(chapter("Chương 3", later))).andExpect(status().isOk());
        seed(ArchiveKind.IMAGE, later, ArchiveState.RESTORING, null);
        mvc.perform(post("/api/studio/chapters/CH03/submit").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeClass\":\"MAJOR\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6011));

        // Đặt lại / lùi về bản đã lưu trữ.
        seed(ArchiveKind.RELEASE, "1.0.0", ArchiveState.ARCHIVED, null);
        mvc.perform(post("/api/studio/releases/1.0.0/current").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6012));
        mvc.perform(post("/api/studio/releases/1.0.1/recall").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"thử\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6012));
        assertThat(releaseRepo.findFirstByIsCurrentTrue().orElseThrow().getVersion()).isEqualTo("1.0.1");

        // Xoá tay tệp đã lưu trữ.
        JsonNode asset = result(mvc.perform(multipart("/api/studio/assets")
                .file(new MockMultipartFile("file", "x.webp", null, AssetImageTest.VP8)).with(admin))).path("asset");
        String path = asset.path("ref").asText().substring("{{ASSET_BASE}}/".length());
        seed(ArchiveKind.IMAGE, path, ArchiveState.ARCHIVED, null);
        mvc.perform(delete("/api/studio/assets/" + asset.path("id").asText()).with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6011));
        assertThat(assetRepo.count()).isEqualTo(1);

        // Đang đếm thì xoá như cũ, kéo theo dòng đếm.
        ArchiveItem r = item(path);
        r.setState(ArchiveState.COUNTING);
        itemRepo.save(r);
        mvc.perform(delete("/api/studio/assets/" + asset.path("id").asText()).with(admin)).andExpect(status().isOk());
        assertThat(item(path)).isNull();
    }

    // ------------------------------------------------------------------ 8. bản phát hành
    @Test
    void releaseLifecycle() throws Exception {
        file("a", 0);
        List<String> own = List.of(file("a", 11), file("a", 12), file("a", 13), file("a", 14), file("a", 15), file("a", 16));
        publishFresh(SHARED, own.get(0));
        for (int i = 1; i < 6; i++) {
            publishNext("CH01", chapter2("Chương 1 bản " + (i + 1), SHARED, own.get(i)));
        }
        setMode(ArchivePolicy.Mode.ON);
        runJob();
        List<String> counting = itemRepo.findAll().stream().filter(r -> r.getKind() == ArchiveKind.RELEASE)
                .map(ArchiveItem::getItemRef).sorted().toList();
        assertThat(counting).containsExactly("1.0.0", "1.0.1");
        assertThat(itemRepo.findAll().stream().filter(r -> r.getKind() == ArchiveKind.IMAGE)).as("bản chưa lưu trữ vẫn giữ tệp").isEmpty();

        // Có lô chơi mới của 1.0.0 → đồng hồ dời theo lô.
        Instant played = Instant.now().plus(Duration.ofMinutes(5)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        playBatch("1.0.0", played);
        runJob();
        ArchiveItem r0 = itemRepo.findByKindAndItemRef(ArchiveKind.RELEASE, "1.0.0").orElseThrow();
        assertThat(r0.getCountdownFrom()).isEqualTo(played);
        assertThat(r0.getDueAt()).isEqualTo(played.plus(Duration.ofDays(14)));

        // Tới hạn (ON) → ARCHIVED; cùng lượt tệp của bản đó CHƯA thành mồ côi.
        choiceRepo.deleteAll();
        due(ArchiveKind.RELEASE, "1.0.0");
        due(ArchiveKind.RELEASE, "1.0.1");
        assertThat(runJob().path("releasesArchived").asInt()).isEqualTo(2);
        assertThat(itemRepo.findByKindAndItemRef(ArchiveKind.RELEASE, "1.0.0").orElseThrow().getState())
                .isEqualTo(ArchiveState.ARCHIVED);
        assertThat(item(own.get(0))).isNull();
        // Lượt sau: tệp chỉ bản đó dùng thành COUNTING, tệp dùng chung thì không.
        runJob();
        assertThat(item(own.get(0)).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(item(own.get(1)).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(item(own.get(2))).isNull();
        assertThat(item(SHARED)).isNull();
        // Bản đã lưu trữ vẫn nằm trong danh sách bản (JSON không rời DB) và vẫn đọc được.
        mvc.perform(get("/api/content/releases/1.0.0/chapters/CH01")).andExpect(status().isOk());

        // Tệp của 1.0.0 được lưu trữ, rồi khôi phục cả bản → bản về đếm, tệp đã lưu trữ thành RESTORING.
        due(ArchiveKind.IMAGE, own.get(0));
        List<JsonNode> work = claim(10);
        assertThat(work).hasSize(1);
        Files.delete(DIR.resolve(own.get(0)));
        worker(post("/api/archive-worker/items/" + work.get(0).path("id").asText() + "/archived")).andExpect(status().isOk());
        UUID releaseId = itemRepo.findByKindAndItemRef(ArchiveKind.RELEASE, "1.0.0").orElseThrow().getId();
        JsonNode restored = result(mvc.perform(post("/api/studio/archive/" + releaseId + "/restore").with(admin))
                .andExpect(status().isOk()));
        assertThat(restored.path("state").asText()).isEqualTo("COUNTING");
        assertThat(restored.path("label").asText()).isEqualTo("1.0.0");
        assertThat(restored.path("url").isNull()).isTrue();
        assertThat(item(own.get(0)).getState()).isEqualTo(ArchiveState.RESTORING);
        assertThat(item(own.get(1)).getState()).as("tệp của bản khác không đụng").isEqualTo(ArchiveState.COUNTING);
        // Bản đã khôi phục nhưng tệp còn chưa về máy chủ → chưa được đặt làm bản đang chạy (6011 kèm đường dẫn).
        mvc.perform(post("/api/studio/releases/1.0.0/current").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6011)).andExpect(jsonPath("$.result[0]").value(own.get(0)));
        // Mọi tệp đã về (máy lưu trữ báo restored) → đặt được.
        Files.write(DIR.resolve(own.get(0)), "tep".getBytes(StandardCharsets.UTF_8));
        worker(post("/api/archive-worker/items/" + item(own.get(0)).getId() + "/restored")).andExpect(status().isOk());
        mvc.perform(post("/api/studio/releases/1.0.0/current").with(admin)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 9. đối soát
    @Test
    void reconcileArchivedAndStuckRows() throws Exception {
        file("a", 0);
        String back = file("a", 1);       // ARCHIVED nhưng tệp có lại trên đĩa
        String stuckHere = file("a", 2);  // ARCHIVING quá 24 giờ, tệp vẫn còn
        String stuckGone = "a/" + "3".repeat(20) + ".webp";   // ARCHIVING quá 24 giờ, tệp đã rời máy chủ
        String inUse = file("a", 4);      // ARCHIVED, có trên đĩa và đang dùng → bỏ dòng
        String lost = "a/" + "5".repeat(20) + ".webp";   // COUNTING mà tệp bị gỡ ngoài BE → vào "đã lưu trữ"
        publishFresh(SHARED, inUse);
        setMode(ArchivePolicy.Mode.ON);
        seed(ArchiveKind.IMAGE, back, ArchiveState.ARCHIVED, null);
        ArchiveItem s1 = seed(ArchiveKind.IMAGE, stuckHere, ArchiveState.ARCHIVING, Instant.now().minus(Duration.ofDays(2)));
        s1.setClaimedAt(Instant.now().minus(Duration.ofHours(25)));
        itemRepo.save(s1);
        ArchiveItem s2 = seed(ArchiveKind.IMAGE, stuckGone, ArchiveState.ARCHIVING, null);
        s2.setClaimedAt(Instant.now().minus(Duration.ofHours(25)));
        itemRepo.save(s2);
        seed(ArchiveKind.IMAGE, inUse, ArchiveState.ARCHIVED, null);
        seed(ArchiveKind.IMAGE, lost, ArchiveState.COUNTING, Instant.now().plus(Duration.ofDays(5)));
        Instant dueBefore = item(stuckHere).getDueAt();

        JsonNode run = runJob();
        assertThat(run.path("reconciled").asInt()).isEqualTo(5);
        assertThat(item(lost).getState()).as("không xoá dòng").isEqualTo(ArchiveState.ARCHIVED);
        assertThat(result(mvc.perform(get("/api/studio/archive/archived").with(admin))).toString()).contains(lost);
        assertThat(item(back).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(item(back).getDueAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(item(stuckHere).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(item(stuckHere).getDueAt()).as("giữ dueAt").isEqualTo(dueBefore);
        assertThat(item(stuckGone).getState()).isEqualTo(ArchiveState.ARCHIVED);
        assertThat(item(inUse)).isNull();
        assertThat(logRepo.existsByAction(ArchiveAction.RECONCILED)).isTrue();

        // ARCHIVING chưa quá 24 giờ thì không đụng.
        ArchiveItem fresh = item(stuckHere);
        fresh.setState(ArchiveState.ARCHIVING);
        fresh.setClaimedAt(Instant.now().minus(Duration.ofHours(1)));
        itemRepo.save(fresh);
        runJob();
        assertThat(item(stuckHere).getState()).isEqualTo(ArchiveState.ARCHIVING);

        // Máy lưu trữ báo lỗi → về đếm, giữ dueAt.
        worker(post("/api/archive-worker/items/" + fresh.getId() + "/failed").contentType(MediaType.APPLICATION_JSON)
                .content("{\"error\":\"" + "x".repeat(300) + "\"}")).andExpect(status().isOk());
        ArchiveItem failed = item(stuckHere);
        assertThat(failed.getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(failed.getLastError()).hasSize(255);
        assertThat(failed.getDueAt()).isEqualTo(dueBefore);

        // Tải lên lại đúng ảnh đã lưu trữ → tệp được ghi lại, dòng về đếm ngay (không chờ job).
        JsonNode asset = result(mvc.perform(multipart("/api/studio/assets")
                .file(new MockMultipartFile("file", "y.webp", null, AssetImageTest.VP8)).with(admin))).path("asset");
        String path = asset.path("ref").asText().substring("{{ASSET_BASE}}/".length());
        seed(ArchiveKind.IMAGE, path, ArchiveState.ARCHIVED, null);
        Files.delete(DIR.resolve(path));
        mvc.perform(multipart("/api/studio/assets").file(new MockMultipartFile("file", "y.webp", null, AssetImageTest.VP8))
                .with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.result.duplicate").value(true));
        assertThat(Files.exists(DIR.resolve(path))).isTrue();
        assertThat(item(path).getState()).isEqualTo(ArchiveState.COUNTING);
    }

    // ------------------------------------------------------------------ 10. khoá máy lưu trữ
    @Test
    void workerKeyGuardsEveryRoute() throws Exception {
        UUID id = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> routes = List.of(
                post("/api/archive-worker/claim").contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"),
                post("/api/archive-worker/items/" + id + "/archived"),
                post("/api/archive-worker/items/" + id + "/failed").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/api/archive-worker/restores"),
                post("/api/archive-worker/items/" + id + "/restored"),
                post("/api/archive-worker/items/" + id + "/restore-failed").contentType(MediaType.APPLICATION_JSON).content("{}"));
        for (MockHttpServletRequestBuilder r : routes) {
            mvc.perform(r).andExpect(status().isForbidden());
            mvc.perform(r.header(HEADER, "sai-khoa")).andExpect(status().isForbidden());
        }
        ReflectionTestUtils.setField(target(), "workerKey", "");
        for (MockHttpServletRequestBuilder r : routes) {
            mvc.perform(r.header(HEADER, "")).andExpect(status().isForbidden());
        }
        ReflectionTestUtils.setField(target(), "workerKey", KEY);
        worker(get("/api/archive-worker/restores")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 11. quyền
    @Test
    void onlyInternalRolesSeeArchiveAndOnlyAdminRuns() throws Exception {
        setMode(ArchivePolicy.Mode.ON);
        RequestPostProcessor parent = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("TYP_ACCOUNT"), new SimpleGrantedAuthority("SCOPE_PARENT"));
        RequestPostProcessor child = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("TYP_SLOT"), new SimpleGrantedAuthority("SCOPE_CHILD"));
        for (RequestPostProcessor who : List.of(parent, child)) {
            for (String p : List.of("/summary", "/due-soon", "/archived", "/log")) {
                mvc.perform(get("/api/studio/archive" + p).with(who)).andExpect(status().isForbidden());
            }
            mvc.perform(post("/api/studio/archive/run").with(who)).andExpect(status().isForbidden());
            mvc.perform(post("/api/studio/archive/" + UUID.randomUUID() + "/keep").with(who)).andExpect(status().isForbidden());
        }
        for (Role r : List.of(Role.GAME_EDITOR, Role.GAME_REVIEWER, Role.GAME_MANAGER)) {
            RequestPostProcessor who = as(r);
            mvc.perform(get("/api/studio/archive/summary").with(who)).andExpect(status().isOk());
            mvc.perform(post("/api/studio/archive/run").with(who))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        }
    }

    // ------------------------------------------------------------------ 12. lastSync
    @Test
    void summaryReadsLastSyncFile() throws Exception {
        setMode(ArchivePolicy.Mode.DRY_RUN);
        assertThat(lastSync().isNull() || lastSync().isMissingNode()).as("chưa có tệp").isTrue();
        Files.writeString(STATUS, "{\"finishedAt\":\"2026-11-27T20:31:00Z\",\"ok\":true}");
        JsonNode s = lastSync();
        assertThat(s.path("finishedAt").asText()).isEqualTo("2026-11-27T20:31:00Z");
        assertThat(s.path("ok").asBoolean()).isTrue();
        Files.writeString(STATUS, "{hỏng");
        assertThat(lastSync().isNull() || lastSync().isMissingNode()).isTrue();
        Files.writeString(STATUS, "{\"finishedAt\":\"hôm qua\",\"ok\":true}");
        assertThat(lastSync().isNull() || lastSync().isMissingNode()).isTrue();
        Files.delete(STATUS);
        assertThat(lastSync().isNull() || lastSync().isMissingNode()).isTrue();
    }

    // ------------------------------------------------------------------ giọng của nháp (rà độc lập C1)
    @Test
    void draftVoicesNeededAfterComposeCountAsInUse() throws Exception {
        file("a", 0);
        publishFresh(SHARED);
        String content = """
                {"id":"chapter","title":"Chương 2",
                 "personalization":{"tokens":{"item":{"default":"chiếc tai nghe","interest:SPORT":"đôi giày"}}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/%s","speakerId":"AN","text":"Mua {{item}} nhé","nextSceneId":"end"},
                   {"id":"end","type":"ending"}]}
                """.formatted(SHARED);
        List<String> keys = voiceKeys.lines(json.readTree(content)).stream().map(l -> l.key()).distinct().toList();
        assertThat(keys).as("câu có token: một khoá mỗi tổ hợp").hasSizeGreaterThanOrEqualTo(2);
        List<com.doan.game.entity.VoiceClip> clips = keys.stream().map(this::doneClip).toList();
        try {
            for (String k : keys) {
                voiceFile(k);
            }
            mvc.perform(put("/api/studio/chapters/CH02/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(content)).andExpect(status().isOk());
            setMode(ArchivePolicy.Mode.ON);
            assertThat(runJob().path("aborted").asBoolean()).isFalse();
            for (String k : keys) {
                assertThat(item("v/" + k + ".mp3")).as("giọng nháp sẽ cần không phải mồ côi").isNull();
            }
        } finally {
            clipRepo.deleteAll(clips);
        }
    }

    @Test
    void archivedVoiceIsTreatedAsNotGenerated() throws Exception {
        file("a", 0);
        publishFresh(SHARED);
        String content = """
                {"id":"chapter","title":"Chương 2","scenes":[
                  {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/%s","speakerId":"AN","text":"Chào bạn nhỏ","nextSceneId":"end"},
                  {"id":"end","type":"ending"}]}
                """.formatted(SHARED);
        String key = voiceKeys.lines(json.readTree(content)).get(0).key();
        String voice = "v/" + key + ".mp3";
        com.doan.game.entity.VoiceClip clip = doneClip(key);
        try {
            seed(ArchiveKind.AUDIO, voice, ArchiveState.ARCHIVED, null);
            mvc.perform(put("/api/studio/chapters/CH02/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(content)).andExpect(status().isOk());
            assertThat(latestDraft("CH02")).as("không gắn voiceUrl tới tệp đã lưu trữ").doesNotContain(key);
            String version = publishNext("CH02", content);
            assertThat(version).isEqualTo("2.0.0");
            assertThat(releaseChapterRepo.findByRelease_IdAndChapter_Code(
                    releaseRepo.findByVersion(version).orElseThrow().getId(), "CH02").orElseThrow().getContent())
                    .doesNotContain(key);
            // Đối chứng: hết lưu trữ thì giọng được gắn lại như trước.
            itemRepo.deleteAll();
            mvc.perform(put("/api/studio/chapters/CH02/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(content)).andExpect(status().isOk());
            assertThat(latestDraft("CH02")).contains(voice);
        } finally {
            clipRepo.delete(clip);
        }
    }

    // ------------------------------------------------------------------ phanh về đĩa (rà độc lập N1)
    @Test
    void safetyBrakeWhenStoreLooksEmptyOrMissing() throws Exception {
        file("a", 0);
        String orphan = file("a", 1);
        publishFresh(SHARED);
        setMode(ArchivePolicy.Mode.ON);
        runJob();
        due(ArchiveKind.IMAGE, orphan);
        ArchiveItem stuck = seed(ArchiveKind.IMAGE, "a/" + "7".repeat(20) + ".webp", ArchiveState.ARCHIVING, null);
        stuck.setClaimedAt(Instant.now().minus(Duration.ofHours(30)));
        itemRepo.save(stuck);

        // Tệp bản đang chạy dùng không có trên đĩa → job và claim đều phanh.
        Files.delete(DIR.resolve(SHARED));
        JsonNode run = runJob();
        assertThat(run.path("aborted").asBoolean()).isTrue();
        assertThat(run.path("note").asText()).contains("không có trên máy chủ");
        assertThat(claim(10)).isEmpty();
        assertThat(item(orphan).getState()).isEqualTo(ArchiveState.COUNTING);

        // Kho trống hẳn → phanh, không xoá đồng hồ, không biến ARCHIVING kẹt thành ARCHIVED.
        Files.delete(DIR.resolve(orphan));
        run = runJob();
        assertThat(run.path("aborted").asBoolean()).isTrue();
        assertThat(run.path("note").asText()).contains("không có tệp nào");
        assertThat(item(orphan).getState()).isEqualTo(ArchiveState.COUNTING);
        assertThat(item(stuck.getItemRef()).getState()).isEqualTo(ArchiveState.ARCHIVING);
    }

    // ------------------------------------------------------------------ OFF sau khi đã bật (rà độc lập N3)
    @Test
    void offAfterArchivingStillShowsArchivedAndRestores() throws Exception {
        String gone = "a/" + "8".repeat(20) + ".webp";
        ArchiveItem row = seed(ArchiveKind.IMAGE, gone, ArchiveState.ARCHIVED, null);
        row.setSizeBytes(1234L);
        itemRepo.save(row);
        seed(ArchiveKind.IMAGE, file("a", 9), ArchiveState.COUNTING, Instant.now().plus(Duration.ofDays(1)));
        setMode(ArchivePolicy.Mode.OFF);

        JsonNode s = result(mvc.perform(get("/api/studio/archive/summary").with(admin)));
        assertThat(s.path("enabled").asBoolean()).isFalse();
        assertThat(s.path("archivedCount").asLong()).isEqualTo(1);
        assertThat(s.path("archivedBytes").asLong()).isEqualTo(1234);
        assertThat(s.path("countingCount").asLong()).isZero();
        assertThat(s.path("dueSoonCount").asLong()).isZero();
        assertThat(result(mvc.perform(get("/api/studio/archive/due-soon").with(admin))).size()).isZero();
        JsonNode archived = result(mvc.perform(get("/api/studio/archive/archived").with(admin)));
        assertThat(archived.get(0).path("ref").asText()).isEqualTo(gone);

        mvc.perform(post("/api/studio/archive/" + row.getId() + "/restore").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.state").value("RESTORING"));
        assertThat(result(mvc.perform(get("/api/studio/archive/summary").with(admin))).path("restoringCount").asLong())
                .isEqualTo(1);
        assertThat(result(mvc.perform(get("/api/studio/archive/log").with(admin))).get(0).path("action").asText())
                .isEqualTo("RESTORE_REQUEST");
        assertThat(result(worker(get("/api/archive-worker/restores"))).get(0).path("path").asText()).isEqualTo(gone);
    }

    // ------------------------------------------------------------------ tiện ích
    private com.doan.game.entity.VoiceClip doneClip(String key) {
        com.doan.game.entity.VoiceClip c = new com.doan.game.entity.VoiceClip();
        c.setClipKey(key);
        c.setProfile("adam");
        c.setModelVersion(voiceKeys.modelVersion());
        c.setReadText("thử");
        c.setStatus(com.doan.game.enums.VoiceClipStatus.DONE);
        c.setPath("v/" + key + ".mp3");
        c.setRequestedAt(Instant.now());
        c.setDoneAt(Instant.now());
        return clipRepo.save(c);
    }

    private String latestDraft(String code) {
        return draftRepo.findTopByChapter_IdOrderByRevisionDesc(chapterRepo.findByCode(code).orElseThrow().getId())
                .orElseThrow().getContent();
    }

    /** Tệp giọng giả v/<khoá>.mp3. */
    private static void voiceFile(String key) throws Exception {
        Path p = DIR.resolve("v").resolve(key + ".mp3");
        Files.createDirectories(p.getParent());
        Files.write(p, "giong".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ tiện ích
    private JsonNode lastSync() throws Exception {
        return result(mvc.perform(get("/api/studio/archive/summary").with(admin)).andExpect(status().isOk())).path("lastSync");
    }

    private ArchiveServiceImpl target() {
        return AopTestUtils.getTargetObject(archiveService);
    }

    private void setMode(ArchivePolicy.Mode mode) {
        ReflectionTestUtils.setField(policy, "mode", mode);
    }

    private JsonNode runJob() throws Exception {
        return result(mvc.perform(post("/api/studio/archive/run").with(admin)).andExpect(status().isOk()));
    }

    private List<JsonNode> claim(int limit) throws Exception {
        JsonNode r = result(worker(post("/api/archive-worker/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":" + limit + "}")).andExpect(status().isOk()));
        List<JsonNode> out = new java.util.ArrayList<>();
        r.forEach(out::add);
        return out;
    }

    private ResultActions worker(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header(HEADER, KEY));
    }

    /** Tệp kho giả: a/<20 chữ số n>.webp hoặc v/<…>.mp3. */
    private static String file(String sub, int n) throws Exception {
        String name = String.format("%020d", n) + (sub.equals("v") ? ".mp3" : ".webp");
        Path p = DIR.resolve(sub).resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, ("tep " + n).getBytes(StandardCharsets.UTF_8));
        return sub + "/" + name;
    }

    private ArchiveItem item(String path) {
        return itemRepo.findByKindAndItemRef(path.startsWith("v/") ? ArchiveKind.AUDIO : ArchiveKind.IMAGE, path).orElse(null);
    }

    private ArchiveItem seed(ArchiveKind kind, String ref, ArchiveState state, Instant dueAt) {
        ArchiveItem r = itemRepo.findByKindAndItemRef(kind, ref).orElseGet(ArchiveItem::new);
        r.setKind(kind);
        r.setItemRef(ref);
        r.setState(state);
        r.setCountdownFrom(Instant.now().minus(Duration.ofDays(40)));
        r.setDueAt(dueAt != null ? dueAt : Instant.now().minus(Duration.ofDays(10)));
        r.setArchivedAt(state == ArchiveState.ARCHIVED ? Instant.now() : null);
        return itemRepo.save(r);
    }

    private void due(ArchiveKind kind, String ref) {
        ArchiveItem r = itemRepo.findByKindAndItemRef(kind, ref).orElseThrow();
        r.setCountdownFrom(Instant.now().minus(Duration.ofDays(40)));
        r.setDueAt(Instant.now().minus(Duration.ofMinutes(1)));
        itemRepo.save(r);
    }

    private JsonNode assetRow(String path) throws Exception {
        for (JsonNode a : result(mvc.perform(get("/api/studio/assets").with(admin)))) {
            if (a.path("ref").asText().endsWith(path)) {
                return a;
            }
        }
        throw new AssertionError("không thấy " + path);
    }

    private void playBatch(String version, Instant receivedAt) {
        Account owner = new Account();
        owner.setEmail("owner-" + System.nanoTime() + "@test.local");
        owner.setDisplayName("Owner");
        owner.setCreatedAt(Instant.now());
        owner = accountRepo.save(owner);
        LearnerGroup g = new LearnerGroup();
        g.setOwner(owner);
        g.setContext(LearningContext.FAMILY);
        g.setName("Nha luu tru");
        g.setSlotLimit(4);
        g.setOpenedAt(Instant.now());
        g = groupRepo.save(g);
        LearnerSlot s = new LearnerSlot();
        s.setGroup(g);
        s.setCode("L" + (System.nanoTime() % 1_000_000_000L));
        s.setPinHash("x");
        s.setDisplayName("Be");
        s.setStatus(SlotStatus.ACTIVE);
        s.setCreatedAt(Instant.now());
        s = slotRepo.save(s);
        ChoiceEvent e = new ChoiceEvent();
        e.setSlot(s);
        e.setChapter(1);
        e.setAttemptIndex(1);
        e.setSceneId("intro");
        e.setChoiceId("a");
        e.setBuildVersion(version);
        e.setChosenAt(receivedAt);
        e.setReceivedAt(receivedAt);
        choiceRepo.save(e);
    }

    private void publishFresh(String... paths) throws Exception {
        String content = paths.length == 0 || paths[0] == null ? chapter("Chương 1", null)
                : paths.length == 1 ? chapter("Chương 1", paths[0]) : chapter2("Chương 1", paths[0], paths[1]);
        ContentTestSupport.publishFresh(mvc, admin, repos, Map.of("CH01", content), "archive test");
    }

    private String publishNext(String code, String content) throws Exception {
        var res = mvc.perform(put("/api/studio/chapters/" + code + "/draft").with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        ContentTestSupport.approve(repos, code, ChangeClass.PATCH, true);
        return result(mvc.perform(post("/api/studio/releases").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk())).path("version").asText();
    }

    private static String chapter(String title, String path) {
        String bg = path == null ? "" : ",\"background\":\"{{ASSET_BASE}}/" + path + "\"";
        return """
                {"id":"chapter","title":"%s","scenes":[
                  {"id":"intro","type":"dialogue"%s,"text":"Chào","nextSceneId":"end"},
                  {"id":"end","type":"ending"}]}
                """.formatted(title, bg);
    }

    private static String chapter2(String title, String path, String other) {
        return """
                {"id":"chapter","title":"%s","scenes":[
                  {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/%s","text":"Chào","nextSceneId":"two"},
                  {"id":"two","type":"dialogue","background":"{{ASSET_BASE}}/%s","text":"Tiếp","nextSceneId":"end"},
                  {"id":"end","type":"ending"}]}
                """.formatted(title, path, other);
    }

    private JsonNode result(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("result");
    }

    private RequestPostProcessor as(Role role) {
        Account a = new Account();
        a.setEmail(role.name().toLowerCase() + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(role.name());
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole r = new AccountRole();
        r.setAccount(a);
        r.setRole(role);
        r.setGrantedAt(Instant.now());
        accountRoleRepo.save(r);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_" + role.name()));
    }
}
