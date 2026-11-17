package com.doan.game.service.impl;

import com.doan.game.DTO.response.PersonalizationResponse;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.ContentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Khảo sát cá nhân hoá của một trẻ (spec build/spec-ca-nhan-hoa-2026-11-11.md, Hưng chốt 11/11).
 *
 * <p>Lưu ở {@code learner_slot.personalization}: {@code {"CHILD":{…},"GAME":{…}}} — có khoá nguồn nghĩa là nguồn đó ĐÃ gửi (map
 * rỗng = bỏ qua hết). Nhãn cuối và trạng thái TÍNH RA bằng {@link Personalizer#resolve}, không lưu. Wipe slot → NULL (SlotServiceImpl).
 *
 * <p>Chỉ bé trả lời (Hưng 17/11): phụ huynh và giáo viên không trả lời thay, cùng chỉ thấy trạng thái. Bé được hỏi cả 3 chiều; chiều
 * nào chưa ra nhãn thì game chọn phụ hỏi — chỉ giới tính và sở thích, không hỏi tuổi trong game. Khoá "PARENT" cũ bị bỏ qua và xoá
 * ở lần ghi kế tiếp.
 */
@Service
public class PersonalizationService {

    /** Nguồn cũ trước 17/11 — chỉ còn để dọn khỏi dữ liệu đã lưu. */
    static final String PARENT = "PARENT";
    static final String CHILD = "CHILD";
    static final String GAME = "GAME";
    /** Game chọn phụ không hỏi tuổi (spec mục 4). */
    static final List<String> GAME_DIMENSIONS = List.of("gender", "interest");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PersonalizationService.class);
    private static final TypeReference<Map<String, Map<String, String>>> SHAPE = new TypeReference<>() {
    };

    private final LearnerSlotRepository slotRepo;
    private final Personalizer personalizer;
    private final ContentService contentService;
    private final ObjectMapper objectMapper;

    public PersonalizationService(LearnerSlotRepository slotRepo, Personalizer personalizer, ContentService contentService,
                                  ObjectMapper objectMapper) {
        this.slotRepo = slotRepo;
        this.personalizer = personalizer;
        this.contentService = contentService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ người lớn (chủ nhóm: phụ huynh / giáo viên)

    /** Chỉ trạng thái (Hưng 17/11): nhãn là dữ liệu cá nhân của trẻ, người lớn không trả lời thay nên không cần xem. */
    @Transactional(readOnly = true)
    public PersonalizationResponse forOwner(UUID callerId, UUID slotId) {
        LearnerSlot slot = requireOwned(callerId, slotId, slotRepo.findById(slotId));
        return new PersonalizationResponse(view(read(slot)).status(), null, List.of(), List.of(), personalizer.catalog());
    }

    // ------------------------------------------------------------------ trẻ (token SLOT)

    @Transactional(readOnly = true)
    public PersonalizationResponse forChild(UUID slotId) {
        return view(read(requireActive(slotRepo.findById(slotId))));
    }

    /**
     * Trẻ trả lời ({@code CHILD}) hoặc kết quả game chọn phụ ({@code GAME}). GỘP vào câu trước của cùng nguồn (17/11): chiều có giá trị
     * → cập nhật, chiều gửi null / rỗng → bỏ câu đó, chiều không gửi → giữ nguyên. Trước đây ghi đè cả nguồn nên bé đổi một câu là mất
     * các câu còn lại.
     */
    @Transactional
    public PersonalizationResponse saveChild(UUID slotId, String source, Map<String, String> answers) {
        if (!CHILD.equals(source) && !GAME.equals(source)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "source: CHILD hoặc GAME");
        }
        LearnerSlot slot = requireActive(slotRepo.lockById(slotId));
        Map<String, Map<String, String>> a = read(slot);
        Map<String, String> merged = new TreeMap<>(a.getOrDefault(source, Map.of()));
        if (answers != null) {
            answers.forEach((dim, value) -> {
                if (value == null || value.isBlank()) {
                    merged.remove(dim);
                }
            });
        }
        merged.putAll(clean(answers, GAME.equals(source) ? GAME_DIMENSIONS : personalizer.catalog().keySet().stream().toList()));
        a.put(source, merged);
        a.remove(PARENT);   // câu phụ huynh trước 17/11: không còn dùng, không giữ dữ liệu trẻ thừa
        write(slot, a);
        return view(a);
    }

    /** Chương của bản {@code version} đã ghép theo nhãn hiện tại của trẻ. FE giữ {@code variantKey} + bản tới hết chương. */
    @Transactional(readOnly = true)
    public JsonNode chapterForChild(UUID slotId, String version, String code) {
        LearnerSlot slot = requireActive(slotRepo.findById(slotId));
        Map<String, Map<String, String>> a;
        try {
            a = read(slot);
        } catch (AppException e) {
            // Khảo sát hỏng không được chặn trẻ chơi: về bản gốc.
            log.warn("personalization hỏng ở slot {} — trả bản gốc", slotId);
            a = Map.of();
        }
        return contentService.releaseChapterFor(version, code, personalizer.resolve(a));
    }

    // ------------------------------------------------------------------ luật

    /**
     * Trạng thái + câu cần hỏi. NOT_STARTED: bé chưa trả lời, game chưa chạy. COMPLETED: mọi chiều có nhãn. WAITING_CHILD: bé chưa
     * trả lời. WAITING_GAME: còn giới tính / sở thích chưa có nhãn và game chọn phụ chưa chạy. SKIPPED: đã qua đủ bước, vẫn thiếu → bản gốc.
     */
    PersonalizationResponse view(Map<String, Map<String, String>> a) {
        Map<String, String> labels = personalizer.resolve(a);
        List<String> askChild = new ArrayList<>();
        if (!a.containsKey(CHILD)) {
            personalizer.catalog().keySet().stream().filter(d -> !labels.containsKey(d)).forEach(askChild::add);
        }
        List<String> askInGame = new ArrayList<>();
        if (!a.containsKey(GAME)) {
            GAME_DIMENSIONS.stream().filter(d -> !labels.containsKey(d)).forEach(askInGame::add);
        }
        String status;
        if (!a.containsKey(CHILD) && !a.containsKey(GAME)) {
            status = "NOT_STARTED";
        } else if (labels.size() == personalizer.catalog().size()) {
            status = "COMPLETED";
        } else if (!askChild.isEmpty()) {
            status = "WAITING_CHILD";
        } else if (!askInGame.isEmpty()) {
            status = "WAITING_GAME";
        } else {
            status = "SKIPPED";
        }
        if ("COMPLETED".equals(status)) {
            askChild.clear();
            askInGame.clear();
        }
        return new PersonalizationResponse(status, labels, askChild, askInGame, personalizer.catalog());
    }

    /** Chỉ nhận chiều trong {@code allowed}, giá trị trong danh mục; null / rỗng bỏ qua (= chưa biết). Sai → 1001. */
    private Map<String, String> clean(Map<String, String> answers, List<String> allowed) {
        Map<String, String> out = new TreeMap<>();
        if (answers == null) {
            return out;
        }
        answers.forEach((dim, value) -> {
            if (!allowed.contains(dim)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, dim + ": không hỏi chiều này (được: " + allowed + ")");
            }
            if (value == null || value.isBlank()) {
                return;
            }
            if (!personalizer.catalog().get(dim).contains(value)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, dim + ": " + value + " không có trong " + personalizer.catalog().get(dim));
            }
            out.put(dim, value);
        });
        return out;
    }

    private Map<String, Map<String, String>> read(LearnerSlot slot) {
        String json = slot.getPersonalization();
        if (json == null || json.isBlank()) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(objectMapper.readValue(json, SHAPE));
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "personalization hỏng ở slot " + slot.getId());
        }
    }

    private void write(LearnerSlot slot, Map<String, Map<String, String>> a) {
        try {
            slot.setPersonalization(objectMapper.writeValueAsString(a));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        slotRepo.save(slot);
    }

    /** Như SlotServiceImpl.requireOwned: không có → 3007, không phải chủ nhóm → 3004; đã xoá sạch → 5004. */
    private static LearnerSlot requireOwned(UUID callerId, UUID slotId, java.util.Optional<LearnerSlot> found) {
        LearnerSlot slot = found.orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        var owner = slot.getGroup().getOwner();
        if (callerId == null || owner == null || !callerId.equals(owner.getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_ACTIVE);
        }
        return slot;
    }

    private static LearnerSlot requireActive(java.util.Optional<LearnerSlot> found) {
        LearnerSlot slot = found.orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        if (slot.getStatus() != SlotStatus.ACTIVE) {
            throw new AppException(ErrorCode.SLOT_NOT_ACTIVE);
        }
        return slot;
    }
}
