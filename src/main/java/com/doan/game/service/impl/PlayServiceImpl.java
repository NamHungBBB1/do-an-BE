package com.doan.game.service.impl;

import com.doan.game.DTO.request.ChapterBatchRequest;
import com.doan.game.DTO.request.ChoiceDto;
import com.doan.game.DTO.request.MiniGameDto;
import com.doan.game.DTO.response.ChapterBatchResponse;
import com.doan.game.DTO.response.RunStateResponse;
import com.doan.game.entity.ChoiceEvent;
import com.doan.game.entity.GameRelease;
import com.doan.game.entity.MiniGameResult;
import com.doan.game.entity.ReleaseChapter;
import com.doan.game.entity.RunState;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.RunStateMapper;
import com.doan.game.repository.ChoiceEventRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.MiniGameResultRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.doan.game.repository.RunStateRepository;
import com.doan.game.service.PlayService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ruột lưu tiến độ chơi, đợt 1 (12/10 Hưng chốt): cuối chương FE gửi đúng MỘT lô, BE chấm tất cả
 * — mở JSON chương trong ReleaseChapter của đúng releaseVersion để kiểm id và tra deltaStats, không
 * tin số client gửi. Một @Transactional; gửi lại vô hại (chương đã trong chaptersDone thì không ghi).
 * 5 chỉ số ẩn chỉ nằm ở RunState, không bao giờ lọt ra response cho token SLOT.
 */
@Service
@RequiredArgsConstructor
public class PlayServiceImpl implements PlayService {

    /** 5 chỉ số khoá; khoá khác trong deltaStats (vd FIQ) bỏ qua; cộng dồn kẹp 0–100. */
    private static final Set<String> STAT_KEYS = Set.of("SAVING", "HAPPINESS", "RISK", "GOAL", "WEALTH");
    /** initialStats mặc định khi chương 1 của bản đó không khai đủ 5 khoá. */
    private static final Map<String, Integer> DEFAULT_STATS = Map.of(
            "SAVING", 50, "HAPPINESS", 50, "RISK", 30, "GOAL", 50, "WEALTH", 0);
    /** Trần một mảng trong lô — quá thì 413/2003 (body 64KB chặn ở controller). */
    private static final int MAX_ROWS = 100;
    private static final int MAX_CHAPTER = 99;

    private final LearnerSlotRepository slotRepo;
    private final GameReleaseRepository releaseRepo;
    private final ReleaseChapterRepository releaseChapterRepo;
    private final RunStateRepository runStateRepo;
    private final ChoiceEventRepository choiceEventRepo;
    private final MiniGameResultRepository miniGameResultRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ---------------------------------------------------------- POST /api/play/chapter
    @Override
    @Transactional
    public ChapterBatchResponse submitChapterBatch(UUID slotId, ChapterBatchRequest req) {
        validateShape(req);
        // Slot ACTIVE: WIPED / không có → 3007, ARCHIVED → 3009 (token SLOT sống qua decoder nên
        // chặn ở service). Gói hết hạn vẫn nhận lô. Khoá dòng slot (FOR UPDATE): hai lô gửi trùng
        // ĐỒNG THỜI (FE gửi lại khi timeout) tuần tự hoá theo slot, lô sau thấy chaptersDone đã
        // có chương này rồi đi thẳng vào gửi lại vô hại thay vì đụng UNIQUE mà 500.
        var slot = slotRepo.lockById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }

        // Bước 1: bản phát hành và chương phải có (7001) — bản cũ vẫn nhận.
        GameRelease release = releaseRepo.findByVersion(req.releaseVersion())
                .orElseThrow(() -> new AppException(ErrorCode.RELEASE_CHAPTER_NOT_FOUND,
                        "khong co ban " + req.releaseVersion()));
        ReleaseChapter rc = releaseChapterRepo
                .findByRelease_IdAndChapter_ChapterNumber(release.getId(), req.chapter())
                .orElseThrow(() -> new AppException(ErrorCode.RELEASE_CHAPTER_NOT_FOUND,
                        "ban " + req.releaseVersion() + " khong co chuong " + req.chapter()));
        JsonNode chapterJson = parse(rc.getContent(), "chuong " + req.chapter());

        // Bước 2: kiểm từng dòng lô trong JSON chương (7002 / 1001), chấm mini-game.
        JsonNode scenes = chapterJson.path("scenes");
        List<ChoiceRow> choiceRows = validateChoices(scenes, req.choices());
        List<MiniRow> miniRows = validateMiniGames(scenes, req.miniGames());

        // Bước 3: RunState — thứ tự chương, lần chơi, gửi lại.
        Instant now = Instant.now(clock);
        Integer total = totalChaptersCurrent();
        RunState run = runStateRepo.findBySlot_Id(slotId).orElse(null);
        int attempt;
        List<Integer> done;
        if (run == null) {
            if (req.attemptIndex() != 1) {
                throw new AppException(ErrorCode.CHAPTER_NOT_UNLOCKED,
                        "chua tung cho ma loi gui attemptIndex " + req.attemptIndex());
            }
            attempt = 1;
            done = new ArrayList<>();
            run = new RunState();
            run.setSlot(slot);
            applyStats(run, initialStats(release));
        } else {
            attempt = run.getAttemptIndex() == null ? 1 : run.getAttemptIndex();
            done = parseChapters(run.getChaptersDone());
            if (req.attemptIndex() == attempt) {
                // Gửi lại chương đã xong: không ghi gì, trả response hiện tại (bước 2 vẫn chạy
                // trước — lô hỏng vẫn 7002, đúng thứ tự contract).
                if (done.contains(req.chapter())) {
                    return toBatchResponse(attempt, done, total);
                }
            } else if (req.attemptIndex() == attempt + 1 && isAllDone(done, total)) {
                // Mở lần chơi mới: lần cũ giữ trong DB, chỉ số về initialStats chương 1 bản đó.
                attempt = req.attemptIndex();
                done = new ArrayList<>();
                applyStats(run, initialStats(release));
            } else {
                throw new AppException(ErrorCode.CHAPTER_NOT_UNLOCKED,
                        "loi gui attemptIndex " + req.attemptIndex() + ", dang la " + attempt);
            }
        }
        // Chương kế tiếp = chaptersDone.size() + 1 (mọi nhánh, kể cả lần chơi đầu).
        if (req.chapter() != done.size() + 1) {
            throw new AppException(ErrorCode.CHAPTER_NOT_UNLOCKED,
                    "phai la chuong ke tiep — da xong " + done.size() + " chuong, loi gui chuong "
                            + req.chapter());
        }

        // Bước 4–5: ghi sự kiện rồi cộng chỉ số — một transaction.
        for (ChoiceRow row : choiceRows) {
            ChoiceEvent e = new ChoiceEvent();
            e.setSlot(slot);
            e.setChapter(req.chapter());
            e.setAttemptIndex(attempt);
            e.setSceneId(row.sceneId());
            e.setChoiceId(row.optionId());
            e.setEffects(row.deltaStats() == null ? null : writeJson(row.deltaStats()));
            e.setBuildVersion(req.releaseVersion());
            e.setChosenAt(row.dto().chosenAt());
            e.setReceivedAt(now);
            choiceEventRepo.save(e);
            applyDelta(run, row.deltaStats());
        }
        for (MiniRow row : miniRows) {
            MiniGameResult m = new MiniGameResult();
            m.setSlot(slot);
            m.setChapter(req.chapter());
            m.setAttemptIndex(attempt);
            m.setGameId(row.dto().gameId());
            m.setItemId(row.dto().itemId());
            m.setConcept(row.concept());
            m.setChosen(row.dto().chosen());
            m.setCorrect(row.correct());
            m.setBuildVersion(req.releaseVersion());
            m.setPlayedAt(row.dto().playedAt());
            m.setReceivedAt(now);
            miniGameResultRepo.save(m);
        }
        done.add(req.chapter());
        run.setAttemptIndex(attempt);
        run.setChaptersDone(writeJson(done));
        run.setUpdatedAt(now);
        runStateRepo.save(run);

        // Bước 6: đợt 1 chưa có luật cấp thành tựu → luôn rỗng.
        return toBatchResponse(attempt, done, total);
    }

    // ---------------------------------------------------------- GET /api/play/state
    @Override
    @Transactional(readOnly = true)
    public RunStateResponse getRunState(UUID slotId) {
        var slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        if (slot.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }
        // releaseVersion / totalChapters lấy bản HIỆN TẠI — cùng nguồn với dashboard.
        GameRelease current = releaseRepo.findFirstByIsCurrentTrue().orElse(null);
        Integer total = current == null ? null
                : releaseChapterRepo.findByRelease_IdOrderByChapter_ChapterNumberAsc(current.getId()).size();
        RunState run = runStateRepo.findBySlot_Id(slotId).orElse(null);
        List<Integer> done = run == null ? List.of() : parseChapters(run.getChaptersDone());
        return RunStateMapper.toResponse(run, current == null ? null : current.getVersion(), total, done);
    }

    // ---------------------------------------------------------- kiểm lô
    private static void validateShape(ChapterBatchRequest req) {
        if (req == null || req.chapter() < 1 || req.chapter() > MAX_CHAPTER || req.attemptIndex() < 1
                || req.releaseVersion() == null || req.releaseVersion().isBlank()
                || req.choices() == null || req.miniGames() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "thieu truong hoac chapter ngoai 1-99 / attemptIndex < 1");
        }
        if (req.choices().size() > MAX_ROWS || req.miniGames().size() > MAX_ROWS) {
            throw new AppException(ErrorCode.BATCH_TOO_LARGE);
        }
    }

    private record ChoiceRow(ChoiceDto dto, String sceneId, String optionId, JsonNode deltaStats) {}

    private record MiniRow(MiniGameDto dto, boolean correct, String concept) {}

    /** Mỗi dòng: cảnh phải type=choice, option phải có trong cảnh; hai dòng cùng nhóm → 1001. */
    private List<ChoiceRow> validateChoices(JsonNode scenes, List<ChoiceDto> choices) {
        List<ChoiceRow> rows = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        for (ChoiceDto c : choices) {
            if (c == null || c.sceneId() == null || c.sceneId().isBlank()
                    || c.optionId() == null || c.optionId().isBlank()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, "choices[]: thieu sceneId / optionId");
            }
            JsonNode scene = findScene(scenes, c.sceneId());
            if (scene == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "canh '" + c.sceneId() + "' khong co trong chuong nay");
            }
            if (!"choice".equals(scene.path("type").asText())) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "canh '" + c.sceneId() + "' khong phai canh lua chon");
            }
            JsonNode option = byId(scene.path("options"), c.optionId());
            if (option == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "lua chon '" + c.optionId() + "' khong co trong canh '" + c.sceneId() + "'");
            }
            // Một dòng cho mỗi nhóm quyết định — cùng luật với bộ khuôn.
            String group = ContentSchema.choiceGroupOf(scene);
            if (!groups.add(group)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "hai dong cung nhom lua chon '" + group + "'");
            }
            JsonNode delta = option.get("deltaStats");
            rows.add(new ChoiceRow(c, c.sceneId(), c.optionId(), delta == null || delta.isNull() ? null : delta));
        }
        return rows;
    }

    /**
     * Tìm game theo id trong scenes[].game rồi chấm theo hình dạng JSON (bảng chấm play.md 12/10).
     * Cơ chế mới (chương 4 trở đi) thêm nhánh tại đây, FE không đổi contract.
     */
    private List<MiniRow> validateMiniGames(JsonNode scenes, List<MiniGameDto> miniGames) {
        List<MiniRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MiniGameDto m : miniGames) {
            if (m == null || m.gameId() == null || m.gameId().isBlank()
                    || m.itemId() == null || m.itemId().isBlank()
                    || m.chosen() == null || m.chosen().isBlank()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "miniGames[]: thieu gameId / itemId / chosen");
            }
            // Câu trả lời đầu tiên trẻ bấm Kiểm tra thắng — gửi lại (trùng id) bỏ qua dòng đó.
            if (!seen.add(m.gameId() + "|" + m.itemId())) {
                continue;
            }
            JsonNode scene = findGameScene(scenes, m.gameId());
            if (scene == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "mini-game '" + m.gameId() + "' khong co trong chuong nay");
            }
            boolean correct = score(scene.path("game"), m);
            rows.add(new MiniRow(m, correct, conceptOf(scene)));
        }
        return rows;
    }

    /** BE chấm đúng / sai từ JSON chương — client gửi correct gì cũng bị bỏ qua. */
    private static boolean score(JsonNode game, MiniGameDto m) {
        // DRAG_AND_DROP có dropZones[].expectedValue (Phong bì, CH02): itemId = ô, chosen = thẻ đã thả.
        JsonNode zones = game.path("dropZones");
        if (zones.isArray() && !zones.isEmpty()) {
            JsonNode zone = byId(zones, m.itemId());
            if (zone == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "o phong bi '" + m.itemId() + "' khong co trong game '" + m.gameId() + "'");
            }
            JsonNode draggable = byId(game.path("draggables"), m.chosen());
            if (draggable == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "chosen '" + m.chosen() + "' khong phai the hop le cua game '" + m.gameId() + "'");
            }
            return draggable.path("value").asText().equals(zone.path("expectedValue").asText());
        }
        // DRAG_AND_DROP có items[].category (Cần / Muốn, CH01): chosen lạ → false, không lỗi.
        JsonNode items = game.path("items");
        if (items.isArray() && !items.isEmpty()) {
            JsonNode item = byId(items, m.itemId());
            if (item == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "the '" + m.itemId() + "' khong co trong game '" + m.gameId() + "'");
            }
            if (item.has("category")) {
                return item.path("category").asText().equals(m.chosen());
            }
            throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                    "game '" + m.gameId() + "' khong co dap an cho the '" + m.itemId() + "'");
        }
        // MATCH_AND_CALCULATE (CH03, CAREER_MATCH): game có THỂ có cả jobs lẫn questions — itemId
        // là việc hay là câu hỏi đều chấm được (Kidz review 12/10: itemId câu hỏi rơi vào nhánh
        // jobs trước thì byId null và 7002 oan).
        JsonNode jobs = game.path("jobs");
        JsonNode questions = game.path("questions");
        if (isNonEmptyArray(jobs) || isNonEmptyArray(questions)) {
            // Bước ghép việc: chosen fit/over so với weeklyLimit; chosen lạ → false, không lỗi.
            JsonNode job = byId(jobs, m.itemId());
            if (job != null) {
                if (!"fit".equals(m.chosen()) && !"over".equals(m.chosen())) {
                    return false;
                }
                boolean fits = job.path("hours").asInt() <= game.path("weeklyLimit").asInt();
                return ("fit".equals(m.chosen())) == fits;
            }
            // Bước tính: chosen phải là option của câu, đúng = correctId.
            JsonNode question = byId(questions, m.itemId());
            if (question == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "viec/cau '" + m.itemId() + "' khong co trong game '" + m.gameId() + "'");
            }
            if (byId(question.path("options"), m.chosen()) == null) {
                throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                        "chosen '" + m.chosen() + "' khong phai dap an cua cau '" + m.itemId() + "'");
            }
            return question.path("correctId").asText().equals(m.chosen());
        }
        // TIMELINE (Savings Race) không chấm — FE không gửi; cơ chế lạ cũng rơi vào đây → 7002.
        throw new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER,
                "game '" + m.gameId() + "' khong co itemId '" + m.itemId() + "' de cham");
    }

    private static boolean isNonEmptyArray(JsonNode node) {
        return node != null && node.isArray() && !node.isEmpty();
    }

    /** Mã khái niệm theo CẢNH: đúng 1 phần thì lấy, nhiều / không khai → null. */
    private static String conceptOf(JsonNode scene) {
        JsonNode units = scene.path("knowledgeUnits");
        if (units.isArray() && units.size() == 1) {
            return units.get(0).asText();
        }
        return null;
    }

    // ---------------------------------------------------------- chỉ số / chaptersDone
    /** 5 chỉ số = initialStats của CHƯƠNG 1 đúng bản đang chơi; thiếu khoá thì mặc định. */
    private Map<String, Integer> initialStats(GameRelease release) {
        Map<String, Integer> stats = new HashMap<>(DEFAULT_STATS);
        releaseChapterRepo.findByRelease_IdAndChapter_ChapterNumber(release.getId(), 1)
                .map(rc -> parse(rc.getContent(), "chuong 1"))
                .map(json -> json.path("initialStats"))
                .ifPresent(init -> STAT_KEYS.forEach(key -> {
                    JsonNode v = init.get(key);
                    if (v != null && v.isNumber()) {
                        stats.put(key, v.asInt());
                    }
                }));
        return stats;
    }

    /** Cộng deltaStats của MỘT lựa chọn: chỉ 5 khoá, kẹp 0–100, cộng dồn suốt lần chơi. */
    private static void applyDelta(RunState run, JsonNode delta) {
        if (delta == null) {
            return;
        }
        delta.properties().forEach(entry -> {
            if (!STAT_KEYS.contains(entry.getKey()) || !entry.getValue().isNumber()) {
                return;
            }
            int current = statOf(run, entry.getKey()) + entry.getValue().asInt();
            statTo(run, entry.getKey(), Math.max(0, Math.min(100, current)));
        });
    }

    private static void applyStats(RunState run, Map<String, Integer> stats) {
        run.setSaving(stats.get("SAVING"));
        run.setHappiness(stats.get("HAPPINESS"));
        run.setRisk(stats.get("RISK"));
        run.setGoal(stats.get("GOAL"));
        run.setWealth(stats.get("WEALTH"));
    }

    private static int statOf(RunState run, String key) {
        Integer v = switch (key) {
            case "SAVING" -> run.getSaving();
            case "HAPPINESS" -> run.getHappiness();
            case "RISK" -> run.getRisk();
            case "GOAL" -> run.getGoal();
            default -> run.getWealth();
        };
        return v == null ? 0 : v;
    }

    private static void statTo(RunState run, String key, int value) {
        switch (key) {
            case "SAVING" -> run.setSaving(value);
            case "HAPPINESS" -> run.setHappiness(value);
            case "RISK" -> run.setRisk(value);
            case "GOAL" -> run.setGoal(value);
            default -> run.setWealth(value);
        }
    }

    /** chaptersDone lưu dạng mảng JSON text ("[1,2]"); hỏng / null → mảng rỗng. */
    private List<Integer> parseChapters(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<Integer> out = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asInt()));
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "chaptersDone hong trong DB");
        }
    }

    // ---------------------------------------------------------- dựng response
    private ChapterBatchResponse toBatchResponse(int attempt, List<Integer> done, Integer total) {
        Integer continueChapter = (total != null && done.size() >= total) ? null : done.size() + 1;
        return new ChapterBatchResponse(attempt, total, List.copyOf(done), continueChapter, List.of());
    }

    /** Số chương của bản ĐANG HIỆN TẠI — cùng nguồn với dashboard; chưa phát hành → null. */
    private Integer totalChaptersCurrent() {
        return releaseRepo.findFirstByIsCurrentTrue()
                .map(r -> releaseChapterRepo.findByRelease_IdOrderByChapter_ChapterNumberAsc(r.getId()).size())
                .orElse(null);
    }

    private static boolean isAllDone(List<Integer> done, Integer total) {
        return total != null && done.size() >= total;
    }

    // ---------------------------------------------------------- JSON chương
    private JsonNode parse(String content, String where) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "JSON " + where + " hong trong DB");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "khong ghi duoc JSON");
        }
    }

    private static JsonNode findScene(JsonNode scenes, String sceneId) {
        if (!scenes.isArray()) {
            return null;
        }
        for (JsonNode s : scenes) {
            if (sceneId.equals(s.path("id").asText(null))) {
                return s;
            }
        }
        return null;
    }

    /** scenes[].game — một chương nhiều mini-game, tìm theo game.id. */
    private static JsonNode findGameScene(JsonNode scenes, String gameId) {
        if (!scenes.isArray()) {
            return null;
        }
        for (JsonNode s : scenes) {
            JsonNode game = s.get("game");
            if (game != null && game.isObject() && gameId.equals(game.path("id").asText(null))) {
                return s;
            }
        }
        return null;
    }

    private static JsonNode byId(JsonNode array, String id) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode n : array) {
            if (id.equals(n.path("id").asText(null))) {
                return n;
            }
        }
        return null;
    }

}
