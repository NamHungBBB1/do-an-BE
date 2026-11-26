package com.doan.game.service.impl;

import com.doan.game.DTO.request.ChapterBatchV2Request;
import com.doan.game.DTO.request.ChapterBatchV2Request.Inspection;
import com.doan.game.DTO.request.ChapterBatchV2Request.MiniGameRow;
import com.doan.game.DTO.request.ChapterBatchV2Request.Pick;
import com.doan.game.DTO.request.ChapterBatchV2Request.Reflection;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Lô bản 2 (build/api-docs/play-v2.md): kiểm hình dạng (mục 2, bảng mục 9 điều 2 và 8), kiểm từng dòng với JSON chương đã đóng băng
 * (điều 3–6) và diễn giải dãy picks (mục 3). Không đụng DB — PlayServiceImpl ghi. Thông báo lỗi luôn kèm mảng[chỉ số].trường.
 */
final class PlayBatchV2 {

    static final int MAX_PICKS = 500;
    static final int MAX_INSPECTIONS = 500;
    static final int MAX_MINI_GAMES = 100;
    static final int MAX_REFLECTIONS = 10;
    private static final int MAX_CHAPTER = 99;
    /** scene-schema.json: "activity" là tên cũ của "reflection" — JSON đóng băng có thể còn tên cũ. */
    private static final Set<String> REFLECTION_TYPES = Set.of("reflection", "activity");

    private static final Set<String> BATCH_FIELDS = Set.of("schema", "chapter", "attemptIndex", "releaseVersion", "picks",
            "inspections", "miniGames", "reflections", "finishedAt");
    private static final Set<String> PICK_FIELDS = Set.of("seq", "sceneId", "optionId", "at");
    private static final Set<String> INSPECTION_FIELDS = Set.of("seq", "sceneId", "inspectId", "at");
    private static final Set<String> MINI_GAME_FIELDS = Set.of("seq", "gameId", "itemId", "chosen", "at");
    private static final Set<String> REFLECTION_FIELDS = Set.of("seq", "sceneId", "answer", "at");

    private PlayBatchV2() {
    }

    /** Một dòng picks đã diễn giải. */
    record PickRow(Pick pick, String groupId, boolean decision, boolean effective, boolean firstDecision) {
    }

    /** Một nhóm quyết định: dòng có hiệu lực (ghi ChoiceEvent) và deltaStats được cộng (lần chọn đầu khi preserveFirstChoiceStats). */
    record GroupChoice(Pick effective, JsonNode delta) {
    }

    /** Một dòng MiniGameResult sẽ ghi: itemId = mã câu; seq / at của ô nhập đầu tiên câu đó đọc (null khi trẻ không gửi ô nào). */
    record MiniRow(String gameId, String questionId, String chosen, boolean correct, String concept, Integer seq, Instant at) {
    }

    record Plan(List<PickRow> picks, List<GroupChoice> choices, List<MiniRow> miniGames) {
    }

    // ------------------------------------------------------------------ hình dạng (400 / 1001, 413 / 2003)

    static ChapterBatchV2Request parse(JsonNode body) {
        only(body, "", BATCH_FIELDS);
        JsonNode schema = body.get("schema");
        if (!schema.isIntegralNumber() || schema.asLong() != 2) {
            throw bad("schema", "chi nhan so 2");
        }
        int chapter = integer(body, "chapter", "chapter", 1, MAX_CHAPTER);
        int attemptIndex = integer(body, "attemptIndex", "attemptIndex", 1, Integer.MAX_VALUE);
        String releaseVersion = text(body, "releaseVersion", "releaseVersion");
        JsonNode picks = array(body, "picks", MAX_PICKS);
        JsonNode inspections = array(body, "inspections", MAX_INSPECTIONS);
        JsonNode miniGames = array(body, "miniGames", MAX_MINI_GAMES);
        JsonNode reflections = array(body, "reflections", MAX_REFLECTIONS);
        Instant finishedAt = instant(body, "finishedAt", "finishedAt");

        Seqs seqs = new Seqs();
        List<Pick> pickList = rows(picks, "picks", PICK_FIELDS, (n, p) -> new Pick(seqs.claim(n, p, null),
                text(n, "sceneId", p), text(n, "optionId", p), instant(n, "at", p)));
        List<Inspection> inspectionList = rows(inspections, "inspections", INSPECTION_FIELDS, (n, p) -> new Inspection(
                seqs.claim(n, p, null), text(n, "sceneId", p), text(n, "inspectId", p), instant(n, "at", p)));
        Map<String, String> gameItems = new HashMap<>();
        List<MiniGameRow> miniList = rows(miniGames, "miniGames", MINI_GAME_FIELDS, (n, p) -> {
            String gameId = text(n, "gameId", p);
            String itemId = text(n, "itemId", p);
            JsonNode chosen = n.get("chosen");
            if (chosen == null || chosen.isNull() || (chosen.isTextual() && chosen.asText().isEmpty())) {
                throw bad(p + ".chosen", "bat buoc, khong duoc null / chuoi rong");
            }
            String earlier = gameItems.putIfAbsent(gameId + "\u0000" + itemId, p);
            if (earlier != null) {
                throw bad(p + ".itemId", "trung (gameId, itemId) voi " + earlier);
            }
            // Các ô cùng một lần chụp dùng chung seq — chung được trong cùng gameId, không được với dòng khác.
            return new MiniGameRow(seqs.claim(n, p, "miniGames:" + gameId), gameId, itemId, chosen, instant(n, "at", p));
        });
        Map<String, String> reflectionScenes = new HashMap<>();
        List<Reflection> reflectionList = rows(reflections, "reflections", REFLECTION_FIELDS, (n, p) -> {
            String sceneId = text(n, "sceneId", p);
            String earlier = reflectionScenes.putIfAbsent(sceneId, p);
            if (earlier != null) {
                throw bad(p + ".sceneId", "moi canh phan tu nop mot lan — trung voi " + earlier);
            }
            JsonNode answer = n.get("answer");
            if (answer == null || !answer.isObject()) {
                throw bad(p + ".answer", "phai la object {maO: ma | [ma]}");
            }
            answer.properties().forEach(e -> {
                JsonNode v = e.getValue();
                boolean ok = v.isTextual() || (v.isArray() && allTextual(v));
                if (!ok) {
                    throw bad(p + ".answer." + e.getKey(), "phai la chuoi hoac mang chuoi");
                }
            });
            return new Reflection(seqs.claim(n, p, null), sceneId, answer, instant(n, "at", p));
        });
        return new ChapterBatchV2Request(2, chapter, attemptIndex, releaseVersion, pickList, inspectionList, miniList,
                reflectionList, finishedAt);
    }

    /** seq dùng chung bốn mảng: không trùng, trừ các dòng miniGames cùng gameId (play-v2 mục 2). */
    private static final class Seqs {
        private record Owner(String group, String path) {
        }

        private final Map<Integer, Owner> owners = new HashMap<>();

        int claim(JsonNode row, String path, String group) {
            int seq = integer(row, "seq", path, 1, Integer.MAX_VALUE);
            Owner earlier = owners.putIfAbsent(seq, new Owner(group, path));
            if (earlier != null && (group == null || !group.equals(earlier.group()))) {
                throw bad(path + ".seq", "seq " + seq + " trung voi " + earlier.path());
            }
            return seq;
        }
    }

    // ------------------------------------------------------------------ đối chiếu JSON chương (400 / 7002) + diễn giải

    static Plan interpret(JsonNode chapterJson, ChapterBatchV2Request req) {
        JsonNode scenes = chapterJson.path("scenes");
        List<PickRow> pickRows = interpretPicks(scenes, req.picks());
        checkInspections(scenes, req.inspections());
        checkReflections(scenes, req.reflections());
        List<MiniRow> miniRows = gradeMiniGames(scenes, req.miniGames());

        // Nhóm quyết định: lựa chọn có hiệu lực = dòng quyết định seq lớn nhất; chỉ số theo nó, hoặc theo lần chọn đầu khi chương khai
        // metadata.preserveFirstChoiceStats (play-v2 mục 3).
        boolean preserveFirst = chapterJson.path("metadata").path("preserveFirstChoiceStats").asBoolean(false);
        Map<String, List<PickRow>> decisions = new LinkedHashMap<>();
        for (PickRow r : pickRows) {
            if (r.decision()) {
                decisions.computeIfAbsent(r.groupId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<PickRow> marked = new ArrayList<>();
        Set<Pick> effective = new HashSet<>();
        Set<Pick> first = new HashSet<>();
        record Counted(Pick effective, Pick counted) {
        }
        List<Counted> counted = new ArrayList<>();
        decisions.values().forEach(rows -> {
            Pick f = rows.get(0).pick();
            Pick e = rows.get(rows.size() - 1).pick();
            first.add(f);
            effective.add(e);
            counted.add(new Counted(e, preserveFirst ? f : e));
        });
        for (PickRow r : pickRows) {
            marked.add(new PickRow(r.pick(), r.groupId(), r.decision(), effective.contains(r.pick()), first.contains(r.pick())));
        }
        // Kẹp 0–100 sau mỗi lần cộng nên thứ tự có nghĩa: cộng theo seq của dòng được tính.
        counted.sort(Comparator.comparingInt(c -> c.counted().seq()));
        List<GroupChoice> choices = new ArrayList<>();
        for (Counted c : counted) {
            choices.add(new GroupChoice(c.effective(), deltaOf(scenes, c.counted())));
        }
        return new Plan(marked, choices, miniRows);
    }

    /** Điều 3: cảnh type = choice, optionId thuộc options[] của ĐÚNG cảnh đó. Trả dòng theo thứ tự seq. */
    private static List<PickRow> interpretPicks(JsonNode scenes, List<Pick> picks) {
        List<PickRow> rows = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            Pick p = picks.get(i);
            String path = "picks[" + i + "]";
            JsonNode scene = PlayServiceImpl.findScene(scenes, p.sceneId());
            if (scene == null || !"choice".equals(scene.path("type").asText())) {
                throw notInChapter(path + ".sceneId", "'" + p.sceneId() + "' khong phai canh lua chon cua chuong nay");
            }
            JsonNode option = PlayServiceImpl.byId(scene.path("options"), p.optionId());
            if (option == null) {
                throw notInChapter(path + ".optionId", "'" + p.optionId() + "' khong co trong canh '" + p.sceneId() + "'");
            }
            rows.add(new PickRow(p, ContentSchema.choiceGroupOf(scene), !option.path("deferLedger").asBoolean(false),
                    false, false));
        }
        rows.sort(Comparator.comparingInt(r -> r.pick().seq()));
        return rows;
    }

    private static JsonNode deltaOf(JsonNode scenes, Pick p) {
        JsonNode option = PlayServiceImpl.byId(PlayServiceImpl.findScene(scenes, p.sceneId()).path("options"), p.optionId());
        JsonNode delta = option.get("deltaStats");
        return delta == null || delta.isNull() ? null : delta;
    }

    /** Điều 4: cảnh sceneId có inspect.id == inspectId. */
    private static void checkInspections(JsonNode scenes, List<Inspection> inspections) {
        for (int i = 0; i < inspections.size(); i++) {
            Inspection x = inspections.get(i);
            JsonNode scene = PlayServiceImpl.findScene(scenes, x.sceneId());
            if (scene == null) {
                throw notInChapter("inspections[" + i + "].sceneId", "canh '" + x.sceneId() + "' khong co trong chuong nay");
            }
            if (!x.inspectId().equals(scene.path("inspect").path("id").asText(null))) {
                throw notInChapter("inspections[" + i + "].inspectId",
                        "'" + x.inspectId() + "' khong phai the cua canh '" + x.sceneId() + "'");
            }
        }
    }

    /** Điều 6: cảnh phản tư có khai inputs; khoá là inputs[].id; giá trị là mã phương án của ô đó, kiểu khớp kind. */
    private static void checkReflections(JsonNode scenes, List<Reflection> reflections) {
        for (int i = 0; i < reflections.size(); i++) {
            Reflection r = reflections.get(i);
            String path = "reflections[" + i + "]";
            JsonNode scene = PlayServiceImpl.findScene(scenes, r.sceneId());
            if (scene == null || !REFLECTION_TYPES.contains(scene.path("type").asText())) {
                throw notInChapter(path + ".sceneId", "'" + r.sceneId() + "' khong phai canh phan tu cua chuong nay");
            }
            JsonNode inputs = scene.path("inputs");
            if (!inputs.isArray() || inputs.isEmpty()) {
                throw notInChapter(path + ".sceneId", "canh '" + r.sceneId() + "' chua khai inputs — khong nhan phan tu");
            }
            r.answer().properties().forEach(e -> {
                String p = path + ".answer." + e.getKey();
                JsonNode input = PlayServiceImpl.byId(inputs, e.getKey());
                if (input == null) {
                    throw notInChapter(p, "o '" + e.getKey() + "' khong co trong inputs cua canh '" + r.sceneId() + "'");
                }
                Set<String> options = new HashSet<>();
                input.path("options").forEach(o -> options.add(o.path("id").asText()));
                JsonNode v = e.getValue();
                boolean ok = switch (input.path("kind").asText()) {
                    case "single" -> v.isTextual() && options.contains(v.asText());
                    case "multi" -> v.isArray() && allIn(v, options);
                    default -> false;
                };
                if (!ok) {
                    throw notInChapter(p, "gia tri khong phai ma phuong an cua o '" + e.getKey() + "' (kind "
                            + input.path("kind").asText("?") + ")");
                }
            });
        }
    }

    /**
     * Điều 5: gameId có trong chương, itemId được một câu grade đọc. Chấm bằng hàm chung như bản 1 (khuôn v3); mini-game không có câu
     * grade thì không ô nào hợp lệ — FE không gửi dòng nào cho nó (play-v2 mục 5).
     */
    private static List<MiniRow> gradeMiniGames(JsonNode scenes, List<MiniGameRow> rows) {
        Map<String, List<MiniGameRow>> byGame = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            MiniGameRow m = rows.get(i);
            String path = "miniGames[" + i + "]";
            JsonNode scene = PlayServiceImpl.findGameScene(scenes, m.gameId());
            if (scene == null) {
                throw notInChapter(path + ".gameId", "mini-game '" + m.gameId() + "' khong co trong chuong nay");
            }
            if (!MiniGameGrader.inputsUsed(scene).contains(m.itemId())) {
                throw notInChapter(path + ".itemId", "khong cau grade nao cua '" + m.gameId() + "' doc o '" + m.itemId() + "'");
            }
            byGame.computeIfAbsent(m.gameId(), k -> new ArrayList<>()).add(m);
        }
        List<MiniRow> out = new ArrayList<>();
        byGame.forEach((gameId, items) -> {
            JsonNode scene = PlayServiceImpl.findGameScene(scenes, gameId);
            Map<String, JsonNode> inputs = new LinkedHashMap<>();
            items.forEach(m -> inputs.put(m.itemId(), m.chosen()));
            List<MiniGameGrader.Question> questions = MiniGameGrader.questions(scene);
            List<MiniGameGrader.Graded> graded = MiniGameGrader.grade(scene, inputs);
            for (int q = 0; q < graded.size(); q++) {
                Set<String> read = MiniGameGrader.inputsOf(questions.get(q));
                MiniGameRow source = items.stream().filter(m -> read.contains(m.itemId()))
                        .min(Comparator.comparingInt(MiniGameRow::seq)).orElse(null);
                MiniGameGrader.Graded g = graded.get(q);
                out.add(new MiniRow(gameId, g.questionId(), g.chosen(), g.correct(), g.concept(),
                        source == null ? null : source.seq(), source == null ? null : source.at()));
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ tiện ích

    private static <T> List<T> rows(JsonNode array, String name, Set<String> fields, BiFunction<JsonNode, String, T> read) {
        List<T> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String path = name + "[" + i + "]";
            only(array.get(i), path, fields);
            out.add(read.apply(array.get(i), path));
        }
        return out;
    }

    private static void only(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw bad(path.isEmpty() ? "body" : path, "phai la object");
        }
        node.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) {
                throw bad(path.isEmpty() ? f : path + "." + f, "truong ngoai hop dong");
            }
        });
    }

    private static int integer(JsonNode node, String field, String path, int min, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min || v.asInt() > max) {
            throw bad(path.equals(field) ? field : path + "." + field, "phai la so nguyen " + min + ".." + max);
        }
        return v.asInt();
    }

    private static String text(JsonNode node, String field, String path) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw bad(path.equals(field) ? field : path + "." + field, "bat buoc, phai la chuoi khac rong");
        }
        return v.asText();
    }

    private static JsonNode array(JsonNode node, String field, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            throw bad(field, "bat buoc, phai la mang (duoc rong)");
        }
        if (v.size() > max) {
            throw new AppException(ErrorCode.BATCH_TOO_LARGE, field + ": toi da " + max + " dong");
        }
        return v;
    }

    /** Trường giờ không bắt buộc: thiếu hoặc null coi như không gửi; có thì phải là ISO-8601. */
    private static Instant instant(JsonNode node, String field, String path) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String where = path.equals(field) ? field : path + "." + field;
        if (!v.isTextual()) {
            throw bad(where, "phai la chuoi ISO-8601");
        }
        try {
            return Instant.parse(v.asText());
        } catch (DateTimeParseException e) {
            throw bad(where, "phai la chuoi ISO-8601");
        }
    }

    private static boolean allTextual(JsonNode array) {
        for (JsonNode x : array) {
            if (!x.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static boolean allIn(JsonNode array, Set<String> options) {
        for (JsonNode x : array) {
            if (!x.isTextual() || !options.contains(x.asText())) {
                return false;
            }
        }
        return true;
    }

    private static AppException bad(String path, String message) {
        return new AppException(ErrorCode.VALIDATION_FAILED, path + ": " + message);
    }

    private static AppException notInChapter(String path, String message) {
        return new AppException(ErrorCode.SCENE_ITEM_NOT_IN_CHAPTER, path + ": " + message);
    }
}
