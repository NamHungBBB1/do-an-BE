package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.AssetRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.service.impl.AssetImageTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kho ảnh (24/10, đợt 2a): tải lên WebP / PNG, trùng thì trả bản cũ, trạng thái tính từ JSON (mồ côi → mới → đang dùng),
 * xem được cả tệp chưa đăng ký mà nội dung đang trỏ, chỉ xoá được tệp mồ côi, Reviewer chỉ xem.
 */
@SpringBootTest(properties = "app.assets.dir=target/test-assets")
@AutoConfigureMockMvc
class AssetFlowTest {

    private static final Path DIR = Path.of("target/test-assets");
    private static final String LEGACY = "{{ASSET_BASE}}/a/0123456789abcdef0123.webp";   // tệp "chuyển nhà" chưa đăng ký

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired AssetRepository assetRepo;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() throws Exception {
        assetRepo.deleteAll();
        if (Files.exists(DIR)) {
            try (var files = Files.walk(DIR)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void uploadDedupeStatusAndDelete() throws Exception {
        RequestPostProcessor editor = as(Role.GAME_EDITOR);
        RequestPostProcessor reviewer = as(Role.GAME_REVIEWER);
        RequestPostProcessor admin = as(Role.ADMIN);
        var repos = new ContentTestSupport.Repos(releaseChapterRepo, releaseRepo, draftRepo, chapterRepo);
        ContentTestSupport.publishFresh(mvc, admin, repos, Map.of("CH01", chapter("Chương 1", LEGACY)), "asset test");

        // Tệp nội dung đang trỏ mà chưa đăng ký vẫn hiện, đúng chương / cảnh / bản đang chạy.
        mvc.perform(get("/api/studio/assets").param("chapter", "CH01").with(reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].registered").value(false))
                .andExpect(jsonPath("$.result[0].status").value("IN_USE"))
                .andExpect(jsonPath("$.result[0].usage[0].where").value("CURRENT"))
                .andExpect(jsonPath("$.result[0].usage[0].sceneIds[0]").value("intro"));

        // Tải lên: Reviewer không được; sai loại → 6008; WebP giữ nguyên, kích thước đọc từ header, tệp nằm trên đĩa.
        upload(reviewer, AssetImageTest.VP8, "x.webp").andExpect(status().isForbidden());
        upload(editor, "<?php ?>".getBytes(), "x.php").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(6008));
        JsonNode first = result(upload(editor, AssetImageTest.VP8, "phong-khach.webp").andExpect(status().isOk()));
        assertThat(first.path("duplicate").asBoolean()).isFalse();
        JsonNode asset = first.path("asset");
        assertThat(asset.path("label").asText()).isEqualTo("phong-khach");
        assertThat(asset.path("width").asInt()).isEqualTo(5);
        assertThat(asset.path("height").asInt()).isEqualTo(3);
        assertThat(asset.path("status").asText()).isEqualTo("ORPHAN");
        String ref = asset.path("ref").asText();
        assertThat(Files.exists(DIR.resolve(ref.substring("{{ASSET_BASE}}/".length())))).isTrue();

        // Tải lại đúng tệp đó → bản cũ, không thêm dòng, nhãn cũ giữ nguyên.
        JsonNode again = result(upload(editor, AssetImageTest.VP8, "ten-khac.webp").andExpect(status().isOk()));
        assertThat(again.path("duplicate").asBoolean()).isTrue();
        assertThat(again.path("asset").path("id").asText()).isEqualTo(asset.path("id").asText());
        assertThat(again.path("asset").path("label").asText()).isEqualTo("phong-khach");
        assertThat(assetRepo.count()).isEqualTo(1);

        // Dán vào nháp → NEW, không xoá được; bỏ khỏi nháp → mồ côi, xoá được (cả tệp trên đĩa).
        mvc.perform(put("/api/studio/chapters/CH02/draft").with(editor).contentType(MediaType.APPLICATION_JSON)
                .content(chapter("Chương 2", ref))).andExpect(status().isOk());
        String id = asset.path("id").asText();
        mvc.perform(get("/api/studio/assets/" + id).with(reviewer))
                .andExpect(jsonPath("$.result.status").value("NEW"))
                .andExpect(jsonPath("$.result.usage[0].chapterCode").value("CH02"))
                .andExpect(jsonPath("$.result.usage[0].where").value("DRAFT"));
        mvc.perform(delete("/api/studio/assets/" + id).with(editor))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6009));
        mvc.perform(put("/api/studio/chapters/CH02/draft").with(editor).contentType(MediaType.APPLICATION_JSON)
                .content(chapter("Chương 2", LEGACY))).andExpect(status().isOk());
        mvc.perform(put("/api/studio/assets/" + id).with(editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Phòng khách\",\"chapterCode\":\"ch2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.chapterCode").value("CH02"))
                .andExpect(jsonPath("$.result.status").value("ORPHAN"));
        mvc.perform(delete("/api/studio/assets/" + id).with(editor)).andExpect(status().isOk());
        assertThat(assetRepo.count()).isZero();
        assertThat(Files.exists(DIR.resolve(ref.substring("{{ASSET_BASE}}/".length())))).isFalse();
    }

    /** PNG có nền trong suốt → WebP vẫn giữ kênh trong suốt (VP8X cờ alpha). Cần ffmpeg có libwebp. */
    @Test
    void pngWithTransparencyStaysTransparent() throws Exception {
        assumeThat(ffmpegAvailable()).as("cần ffmpeg trong PATH").isTrue();
        JsonNode r = result(upload(as(Role.GAME_EDITOR), AssetImageTest.PNG_ALPHA, "an.png").andExpect(status().isOk()));
        byte[] webp = Files.readAllBytes(DIR.resolve(r.path("asset").path("ref").asText().substring("{{ASSET_BASE}}/".length())));
        assertThat(new String(webp, 12, 4, StandardCharsets.US_ASCII)).isEqualTo("VP8X");
        assertThat(webp[20] & 0x10).as("cờ alpha").isNotZero();
        assertThat(r.path("asset").path("width").asInt()).isEqualTo(4);
        assertThat(r.path("asset").path("height").asInt()).isEqualTo(6);
    }

    private static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private ResultActions upload(RequestPostProcessor who, byte[] bytes, String name) throws Exception {
        return mvc.perform(multipart("/api/studio/assets").file(new MockMultipartFile("file", name, null, bytes))
                .param("chapterCode", "CH01").with(who));
    }

    private JsonNode result(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("result");
    }

    private static String chapter(String title, String background) {
        return """
                {"id":"chapter","title":"%s","scenes":[
                  {"id":"intro","type":"dialogue","background":"%s","text":"Chào","nextSceneId":"end"},
                  {"id":"end","type":"ending"}]}
                """.formatted(title, background);
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
