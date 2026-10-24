package com.doan.game.service.impl;

import com.doan.game.DTO.response.VoiceSuggestResponse;
import com.doan.game.entity.AiKey;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AiKeyRepository;
import com.doan.game.service.AiTextClient;
import com.doan.game.service.ContentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gợi ý cách đọc (Hưng 24/10 "nối luôn phần AI sinh giọng từ thoại"; spec build/spec-kho-anh-giong-2026-10-24.md mục 4.7).
 * Câu thoại có người nói của nháp mới nhất → LỌC bằng luật (chỉ câu có chữ số, ký hiệu, chữ viết tắt in hoa — thứ máy đọc dễ sai) → gửi AI MỘT lần cho cả chương (Hưng 24/10: gom lại gọi 1 lần); AI trả hỏng / bị cắt thì chia đôi gọi
 * lại. Không lưu gì — người soạn chốt. Khoá: khoá AI đang bật + thử đạt, ưu tiên nhỏ trước; hết lượt → nghỉ 1 giờ, thử khoá kế.
 * Nội dung game công khai (không có dữ liệu trẻ) nên gửi nguyên văn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceSuggestService {

    private static final Duration QUOTA_REST = Duration.ofHours(1);
    /** Dễ đọc sai: chữ số, ký hiệu, chữ viết tắt in hoa ≥ 2 chữ (OTP, ATM). Câu thuần chữ Việt máy đọc đúng → không gửi AI. */
    private static final Pattern RISKY = Pattern.compile("\\d|[%/+&@#=<>]|\\b[A-Z]{2,}\\b");

    private final ContentService contentService;
    private final AiKeyRepository keyRepo;
    private final AiKeyServiceImpl keys;
    private final AiTextClient ai;
    private final ObjectMapper json;
    private final Clock clock;

    public VoiceSuggestResponse suggest(String code) {
        Map<String, String> lines = voiceLines(contentService.draftContent(code).content());
        List<Map.Entry<String, String>> risky = lines.entrySet().stream().filter(e -> needsAi(e.getValue())).toList();
        if (risky.isEmpty()) {
            return new VoiceSuggestResponse(null, null, lines.size(), 0, 0, List.of());
        }
        List<AiKey> usable = usableKeys();
        if (usable.isEmpty()) {
            throw new AppException(ErrorCode.AI_KEY_MISSING);
        }
        for (AiKey key : usable) {
            try {
                int[] calls = {0};
                List<VoiceSuggestResponse.Item> out = ask(key, risky, lines, calls, 0);
                return new VoiceSuggestResponse(key.getProvider().name(), ai.model(key.getProvider()), lines.size(),
                        risky.size(), calls[0], out);
            } catch (AiTextClient.AiCallException e) {
                log.warn("Gợi ý cách đọc: khoá {} lỗi {}", key.getAlias(), e.getMessage());
                if (e.quota()) {
                    key.setCoolDownUntil(Instant.now(clock).plus(QUOTA_REST));
                    keyRepo.save(key);
                }
            }
        }
        throw new AppException(ErrorCode.AI_UNAVAILABLE);
    }

    /** Câu thoại có người nói (cảnh có text + speaker / speakerId, không tắt giọng, chưa chốt spokenText) — sceneId → chữ. */
    static Map<String, String> voiceLines(JsonNode content) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (JsonNode s : content.path("scenes")) {
            String text = s.path("text").asText("").trim();
            boolean speaker = !s.path("speaker").asText("").isBlank() || !s.path("speakerId").asText("").isBlank();
            if (!text.isEmpty() && speaker && !"off".equals(s.path("voice").asText()) && !s.hasNonNull("spokenText")) {
                lines.put(s.path("id").asText(), text);
            }
        }
        return lines;
    }

    static boolean needsAi(String text) {
        return RISKY.matcher(text).find();
    }

    private List<AiKey> usableKeys() {
        Instant now = Instant.now(clock);
        return keyRepo.findAll().stream()
                .filter(k -> k.isEnabled() && k.isLastTestOk() && (k.getCoolDownUntil() == null || k.getCoolDownUntil().isBefore(now)))
                .sorted(Comparator.comparing((AiKey k) -> k.getPriority() == null ? Integer.MAX_VALUE : k.getPriority())
                        .thenComparing(AiKey::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Một lần gọi cho cả nhóm câu; JSON hỏng / bị cắt → chia đôi gọi lại (tối đa 3 tầng). */
    private List<VoiceSuggestResponse.Item> ask(AiKey key, List<Map.Entry<String, String>> batch, Map<String, String> lines,
                                                int[] calls, int depth) {
        StringBuilder list = new StringBuilder();
        batch.forEach(e -> list.append(e.getKey()).append(" | ").append(e.getValue().replace('\n', ' ')).append('\n'));
        calls[0]++;
        String raw = ai.generateJson(key.getProvider(), keys.decrypt(key.getSecretEnc()), PROMPT + list);
        JsonNode items;
        try {
            items = json.readTree(raw).path("items");
        } catch (Exception e) {
            if (batch.size() > 1 && depth < 3) {
                int mid = batch.size() / 2;
                List<VoiceSuggestResponse.Item> out = new ArrayList<>(ask(key, batch.subList(0, mid), lines, calls, depth + 1));
                out.addAll(ask(key, batch.subList(mid, batch.size()), lines, calls, depth + 1));
                return out;
            }
            throw new AiTextClient.AiCallException("AI trả JSON hỏng", false);
        }
        List<VoiceSuggestResponse.Item> out = new ArrayList<>();
        for (JsonNode it : items) {
            String id = it.path("sceneId").asText();
            String text = lines.get(id);
            String suggestion = it.path("spokenText").asText("").trim();
            if (text == null || suggestion.isEmpty() || suggestion.equals(text)) {
                continue;   // AI bịa mã cảnh / không đổi gì → bỏ
            }
            List<String> reasons = new ArrayList<>();
            it.path("reasons").forEach(r -> reasons.add(r.asText()));
            out.add(new VoiceSuggestResponse.Item(id, text, suggestion, reasons));
        }
        return out;
    }

    private static final String PROMPT = """
            Bạn chuẩn bị lời thoại tiếng Việt cho máy đọc (text-to-speech) trong game dạy tài chính cho trẻ 10–15 tuổi.
            Với mỗi câu dưới đây (dạng "mã cảnh | câu"), nếu máy đọc có thể đọc sai thì viết lại CÁCH ĐỌC bằng chữ:
            số và tiền (500.000đ → năm trăm nghìn đồng, 1,5 triệu → một triệu rưỡi), phần trăm, ngày giờ, phân số, ký hiệu (/, %, +, -),
            chữ viết tắt, từ / tên nước ngoài (viết theo cách người Việt đọc). Giữ nguyên ý, không thêm bớt từ, không đổi giọng văn.
            Câu đọc đúng rồi thì BỎ QUA, không đưa vào kết quả.
            Chỉ trả JSON: {"items":[{"sceneId":"…","spokenText":"…","reasons":["số tiền"]}]}
            Các câu:
            """;
}
