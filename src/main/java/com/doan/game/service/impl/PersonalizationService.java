package com.doan.game.service.impl;

import com.doan.game.DTO.response.PersonalizationResponse;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.LearningContext;
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
 * <p>Lưu ở {@code learner_slot.personalization}: {@code {"PARENT":{…},"CHILD":{…},"GAME":{…}}} — có khoá nguồn nghĩa là nguồn đó
 * ĐÃ gửi (map rỗng = bỏ trống / bỏ qua hết). Nhãn cuối và trạng thái TÍNH RA bằng {@link Personalizer#resolve}, không lưu.
 * Wipe slot → NULL (SlotServiceImpl).
 *
 * <p>Ai hỏi gì: trẻ được hỏi những chiều phụ huynh để trống (Hưng: "phần nào không biết ta hỏi trực tiếp trẻ"); còn chiều nào chưa
 * ra nhãn thì game chọn phụ hỏi — chỉ giới tính và sở thích, không hỏi tuổi trong game.
 */
@Service
public class PersonalizationService {

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

    // ------------------------------------------------------------------ phụ huynh (chủ nhóm)

    @Transactional(readOnly = true)
    public PersonalizationResponse forParent(UUID callerId, UUID slotId) {
        LearnerSlot slot = requireOwned(callerId, slotId, slotRepo.findById(slotId));
        Map<String, Map<String, String>> a = read(slot);
        PersonalizationResponse v = view(a, a.getOrDefault(PARENT, Map.of()));
        if (slot.getGroup().getContext() != LearningContext.FAMILY) {
            // Giáo viên chỉ thấy trạng thái (spec mục 8): nhãn của học sinh là dữ liệu cá nhân trẻ, giáo viên không cần để dạy.
            return new PersonalizationResponse(v.status(), null, null, List.of(), List.of(), personalizer.catalog());
        }
        return v;
    }

    /** Ghi đè toàn bộ câu trả lời của phụ huynh. Giá trị null / rỗng = "không biết, để bé chọn". Chỉ nhóm gia đình (H2 chờ Hưng). */
    @Transactional
    public PersonalizationResponse saveParent(UUID callerId, UUID slotId, Map<String, String> answers) {
        LearnerSlot slot = requireOwned(callerId, slotId, slotRepo.lockById(slotId));
        if (slot.getGroup().getContext() != LearningContext.FAMILY) {
            throw new AppException(ErrorCode.FORBIDDEN, "nhóm lớp: học sinh tự trả lời khi vào game, giáo viên không trả lời thay");
        }
        Map<String, Map<String, String>> a = read(slot);
        a.put(PARENT, clean(answers, personalizer.catalog().keySet().stream().toList()));
        write(slot, a);
        return view(a, a.get(PARENT));
    }

    // ------------------------------------------------------------------ trẻ (token SLOT)

    @Transactional(readOnly = true)
    public PersonalizationResponse forChild(UUID slotId) {
        return view(read(requireActive(slotRepo.findById(slotId))), null);
    }

    /** Trẻ trả lời ({@code CHILD}) hoặc kết quả game chọn phụ ({@code GAME}). Ghi đè câu trước của cùng nguồn. */
    @Transactional
    public PersonalizationResponse saveChild(UUID slotId, String source, Map<String, String> answers) {
        if (!CHILD.equals(source) && !GAME.equals(source)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "source: CHILD hoặc GAME");
        }
        LearnerSlot slot = requireActive(slotRepo.lockById(slotId));
        Map<String, Map<String, String>> a = read(slot);
        a.put(source, clean(answers, GAME.equals(source) ? GAME_DIMENSIONS : personalizer.catalog().keySet().stream().toList()));
        write(slot, a);
        return view(a, null);
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
     * Trạng thái + câu cần hỏi. {@code parent} = câu của phụ huynh (chỉ trả cho phụ huynh; trẻ không thấy).
     * NOT_STARTED: chưa nguồn nào gửi. COMPLETED: mọi chiều có nhãn. WAITING_CHILD: trẻ chưa trả lời mà còn câu cần hỏi trẻ.
     * WAITING_GAME: còn giới tính / sở thích chưa có nhãn và game chọn phụ chưa chạy. SKIPPED: đã qua đủ bước, vẫn thiếu → bản gốc.
     */
    PersonalizationResponse view(Map<String, Map<String, String>> a, Map<String, String> parent) {
        Map<String, String> labels = personalizer.resolve(a);
        Map<String, String> p = a.getOrDefault(PARENT, Map.of());
        List<String> askChild = new ArrayList<>();
        if (!a.containsKey(CHILD)) {
            personalizer.catalog().keySet().stream().filter(d -> !p.containsKey(d) && !labels.containsKey(d)).forEach(askChild::add);
        }
        List<String> askInGame = new ArrayList<>();
        if (!a.containsKey(GAME)) {
            GAME_DIMENSIONS.stream().filter(d -> !labels.containsKey(d)).forEach(askInGame::add);
        }
        String status;
        if (a.isEmpty()) {
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
        return new PersonalizationResponse(status, labels, parent, askChild, askInGame, personalizer.catalog());
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
