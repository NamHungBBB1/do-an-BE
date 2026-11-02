package com.doan.game.service.impl;

import com.doan.game.entity.VoiceClip;
import com.doan.game.enums.VoiceClipStatus;
import com.doan.game.repository.VoiceClipRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Khoá giọng đọc (spec build/spec-kho-anh-giong-2026-10-24.md mục 4.2, Hưng 03/11): một câu thoại có người nói →
 * {@code readText = clean(spokenText ?? text)}, {@code key = sha256(engine|modelVersion|profile|readText)[:20]} = tên tệp
 * {@code v/<key>.mp3}. Cùng giọng + cùng chữ đọc → cùng khoá → dùng lại tệp cũ, không sinh lại.
 *
 * LUẬT PHẢI KHỚP TỪNG KÝ TỰ với ops/voice/render_voice.py ({@code clean}, {@code profile_for}, {@code clip_hash}) và
 * voice/worker.py — test {@code VoiceKeysTest} đọc src/test/resources/voice/key-vectors.json do Python sinh.
 * Cấu hình giọng: src/main/resources/voice/voices.json (nhân vật → giọng, engine, modelVersion).
 */
@Component
public class VoiceKeys {

    static final String PLACEHOLDER = "{{ASSET_BASE}}";
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{2728}\\x{2B50}\\x{FE0F}]+");
    // Python 3: \d, \s, \b của str là Unicode → Java phải bật UNICODE_CHARACTER_CLASS mới khớp.
    private static final Pattern DONG = Pattern.compile("(\\d)\\s*đ\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Một câu cần đọc. {@code profile} không null. */
    public record Line(String sceneId, String speakerId, String profile, String readText, String key) {
        public String url() {
            return PLACEHOLDER + "/v/" + key + ".mp3";
        }
    }

    private final VoiceClipRepository clipRepo;
    private final ObjectMapper objectMapper;
    private final JsonNode config;

    public VoiceKeys(VoiceClipRepository clipRepo, ObjectMapper objectMapper) {
        this.clipRepo = clipRepo;
        this.objectMapper = objectMapper;
        try (InputStream in = new ClassPathResource("voice/voices.json").getInputStream()) {
            this.config = objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("thiếu voice/voices.json", e);
        }
    }

    public JsonNode config() {
        return config;
    }

    public String modelVersion() {
        return config.path("modelVersion").asText();
    }

    /** = clean() của render_voice.py: bỏ emoji, gạch dài → phẩy, … → ..., "250.000đ" → "250.000 đồng", gộp khoảng trắng. */
    public static String clean(String text) {
        String t = EMOJI.matcher(text).replaceAll(" ");
        t = t.replace("—", ",").replace("–", ",").replace("…", "...");
        t = DONG.matcher(t).replaceAll("$1 đồng");
        return SPACES.matcher(t).replaceAll(" ").strip();
    }

    /** null = không đọc. Ghi đè theo cảnh ("off" tắt) > giọng nhân vật > giọng mặc định. */
    public String profileFor(JsonNode scene) {
        String v = scene.path("voice").asText("");
        if ("off".equals(v)) {
            return null;
        }
        if (!v.isEmpty()) {
            return config.path("profiles").has(v) ? v : null;
        }
        JsonNode p = config.path("characters").path(scene.path("speakerId").asText(""));
        if (p.isTextual()) {
            return p.asText();
        }
        JsonNode d = config.path("defaultProfile");
        return d.isTextual() ? d.asText() : null;
    }

    public String key(String profile, String readText) {
        String raw = String.join("|", config.path("engine").asText(), modelVersion(), profile, readText);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Câu cần đọc của một cảnh, hoặc null (không có chữ / người nói / giọng). */
    public Line lineOf(JsonNode scene) {
        String text = scene.path("text").asText("");
        String speaker = scene.path("speakerId").asText("");
        if (text.isEmpty() || speaker.isEmpty()) {
            return null;
        }
        String profile = profileFor(scene);
        if (profile == null) {
            return null;
        }
        String spoken = scene.path("spokenText").asText("");
        String readText = clean(spoken.isBlank() ? text : spoken);
        if (readText.isEmpty()) {
            return null;
        }
        return new Line(scene.path("id").asText(""), speaker, profile, readText, key(profile, readText));
    }

    public List<Line> lines(JsonNode content) {
        List<Line> out = new ArrayList<>();
        for (JsonNode s : content.path("scenes")) {
            Line l = lineOf(s);
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    /** Khoá đã có tệp: VoiceClip DONE, hoặc nháp đang trỏ đúng tệp {@code v/<key>.mp3} (tệp có từ trước khi có bảng). */
    public Set<String> readyKeys(JsonNode content) {
        List<Line> ls = lines(content);
        Set<String> ready = clipRepo.findByClipKeyIn(ls.stream().map(Line::key).toList()).stream()
                .filter(c -> c.getStatus() == VoiceClipStatus.DONE).map(VoiceClip::getClipKey)
                .collect(Collectors.toSet());
        for (JsonNode s : content.path("scenes")) {
            Line l = lineOf(s);
            if (l != null && s.path("voiceUrl").asText("").endsWith("/v/" + l.key() + ".mp3")) {
                ready.add(l.key());
            }
        }
        return ready;
    }

    /**
     * BE tự gắn / gỡ giọng (spec 4.3): câu có tệp khớp khoá → voiceUrl = tệp đó (sửa về câu cũ = tự gắn lại giọng cũ);
     * không có → gỡ voiceUrl (thà im còn hơn đọc sai). Editor không tự ghi được voiceUrl. Sửa tại chỗ, trả lại chính content.
     */
    public JsonNode attach(JsonNode content) {
        Set<String> ready = readyKeys(content);
        for (JsonNode s : content.path("scenes")) {
            if (!(s instanceof ObjectNode o)) {
                continue;
            }
            Line l = lineOf(s);
            if (l != null && ready.contains(l.key())) {
                o.put("voiceUrl", l.url());
            } else {
                o.remove("voiceUrl");
            }
        }
        return content;
    }

    public String attachJson(String json) {
        try {
            return objectMapper.writeValueAsString(attach(objectMapper.readTree(json)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON chương hỏng", e);
        }
    }
}
