package com.doan.game.service.impl;

import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bộ khuôn nội dung game (24/10 — spec build/spec-bo-khuon-noi-dung-2026-10-24.md). Hưng chốt: không gán cứng, BE quyết khung.
 * Một nguồn duy nhất: resources/content/scene-schema.json — phát cho Studio (GET /api/studio/scene-schema) và dùng để kiểm nháp.
 *
 * Ba mức: sai rõ ràng → 400 / 1001 kèm đường dẫn trường; dạng CŨ còn trong CH01–08 (điều kiện chuỗi, mini-game thiếu kind, requires,
 * onFailSceneId…) → cảnh báo, không chặn, cho tới khi chuyển xong; lỗi ĐƯỜNG ĐI (cảnh không ai tới được, thiếu "đi tiếp tới đâu", không tới
 * được Kết, kẹt ở chương chốt quyết định) → cảnh báo khi lưu nháp (đang soạn dở), CHẶN khi gửi duyệt ({@link #requireCompleteFlow}).
 * Không sửa nội dung người soạn (Hưng 24/10: tách rồi ghép phải y bản cũ).
 *
 * Đường đi tính đúng như game đang chạy (luồng thật đo 24/10, hub #/nhanh-truyen): lựa chọn → nextSceneId; rẽ tự động → ifTrue / ifFalse;
 * mini-game → nextSceneId || onPassSceneId (không có hướng "chưa đạt" — Hưng 24/10 bỏ hẳn onFailSceneId); nút xem lại → reviewAction.targetSceneId.
 */
@Component
public class ContentSchema {

    private final JsonNode schema;
    private final Map<String, String> typeByName = new HashMap<>();   // tên hoặc tên cũ → loại chuẩn
    private final Map<String, Map<String, JsonNode>> sceneFields = new HashMap<>();   // loại chuẩn → trường chung + trường riêng
    private final Map<String, JsonNode> optionFields = new LinkedHashMap<>();
    private final Set<String> stats = new HashSet<>();
    private final Set<String> variables = new HashSet<>();
    private final Set<String> variablePrefixes = new HashSet<>();   // "flag:*" → "flag:"
    private final Set<String> ops = new HashSet<>();
    private final Set<String> positions = new HashSet<>();
    private final Set<String> gameKinds = new HashSet<>();
    private final Map<String, String> kindByLegacyId = new HashMap<>();
    /** Cấu hình từng loại mini-game (minigameKinds.KIND.fields — sinh từ mini-game thật bằng ops/finteen-assets/sinh_khuon_minigame.py). */
    private final Map<String, Map<String, JsonNode>> kindFields = new HashMap<>();

    public ContentSchema(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("content/scene-schema.json").getInputStream()) {
            this.schema = objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được content/scene-schema.json", e);
        }
        // Dựng sẵn một lần: chương 150 cảnh không phải chép lại bảng trường 150 lần.
        Map<String, JsonNode> common = new LinkedHashMap<>();
        schema.path("commonSceneFields").fields().forEachRemaining(e -> common.put(e.getKey(), e.getValue()));
        schema.path("sceneTypes").fields().forEachRemaining(e -> {
            typeByName.put(e.getKey(), e.getKey());
            e.getValue().path("aliases").forEach(a -> typeByName.put(a.asText(), e.getKey()));
            Map<String, JsonNode> defs = new LinkedHashMap<>(common);
            e.getValue().path("fields").fields().forEachRemaining(f -> defs.put(f.getKey(), f.getValue()));
            sceneFields.put(e.getKey(), defs);
        });
        schema.path("optionFields").fields().forEachRemaining(e -> optionFields.put(e.getKey(), e.getValue()));
        schema.path("stats").forEach(s -> stats.add(s.path("key").asText()));
        schema.path("variables").forEach(v -> {
            String name = v.asText();
            if (name.endsWith(":*")) {
                variablePrefixes.add(name.substring(0, name.length() - 1));
            } else {
                variables.add(name);
            }
        });
        schema.path("conditionOps").forEach(o -> ops.add(o.asText()));
        schema.path("positions").forEach(p -> positions.add(p.asText()));
        for (String group : List.of("minigameKinds", "stepKinds")) {
            schema.path(group).fields().forEachRemaining(e -> {
                gameKinds.add(e.getKey());
                e.getValue().path("legacyIds").forEach(id -> kindByLegacyId.put(id.asText(), e.getKey()));
                if (e.getValue().path("fields").isObject()) {
                    kindFields.put(e.getKey(), defsOf(e.getValue().path("fields")));
                }
            });
        }
    }

    /** Bộ khuôn nguyên văn — Studio dựng form từ đây. */
    public JsonNode schema() {
        return schema;
    }

    /** Nhóm quyết định của một cảnh lựa chọn: choiceGroup, không có thì = mã cảnh. Một luật cho cả khuôn lẫn BE chấm lô. */
    public static String choiceGroupOf(JsonNode scene) {
        String group = scene.path("choiceGroup").asText("");
        return group.isBlank() ? scene.path("id").asText() : group;
    }

    /** Kiểm một chương khi LƯU NHÁP; ném 400 / 1001 khi sai, trả cảnh báo (dạng cũ + đường đi chưa trọn). */
    public List<String> validate(JsonNode root) {
        Check c = check(root);
        c.warnings.addAll(flowProblems(root));
        return c.warnings;
    }

    /** Khi GỬI DUYỆT: kiểm như lưu nháp, rồi chặn nếu đường đi chưa trọn — bản đưa người duyệt phải chơi được từ đầu tới Kết. */
    public void requireCompleteFlow(JsonNode root) {
        check(root);
        List<String> problems = flowProblems(root);
        if (!problems.isEmpty()) {
            throw fail("đường đi", String.join("; ", problems.subList(0, Math.min(5, problems.size())))
                    + (problems.size() > 5 ? "; … còn " + (problems.size() - 5) + " chỗ" : ""));
        }
    }

    private Check check(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw fail("content", "phải là JSON object của một chương");
        }
        JsonNode scenes = root.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            throw fail("scenes", "phải là mảng không rỗng");
        }
        Set<String> ids = new HashSet<>();
        Map<String, Set<String>> optionsByGroup = new HashMap<>();
        Set<String> inspectIds = new HashSet<>();
        for (int i = 0; i < scenes.size(); i++) {
            JsonNode s = scenes.get(i);
            String id = s.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw fail("scenes[" + i + "].id", "mỗi cảnh phải có id");
            }
            if (!ids.add(id)) {
                throw fail("scenes[" + i + "].id", "id trùng " + id);
            }
            if (s.path("options").isArray()) {
                Set<String> g = optionsByGroup.computeIfAbsent(choiceGroupOf(s), k -> new HashSet<>());
                s.path("options").forEach(o -> g.add(o.path("id").asText()));
            }
            String inspectId = s.path("inspect").path("id").asText("");
            if (!inspectId.isBlank()) {
                inspectIds.add(inspectId);
            }
        }
        Check c = new Check(ids, optionsByGroup, inspectIds);
        root.path("initialStats").fieldNames().forEachRemaining(k -> {
            if (!stats.contains(k)) {
                c.warn("initialStats." + k + ": chỉ số không có trong bộ khuôn (" + stats + ") — BE bỏ qua");
            }
        });
        for (int i = 0; i < scenes.size(); i++) {
            c.scene(scenes.get(i), "scenes[" + i + "]");
        }
        JsonNode endings = root.path("endingRules").path("endings");
        for (int i = 0; i < endings.size(); i++) {
            c.condition(endings.get(i).get("condition"), "endingRules.endings[" + i + "].condition");
            c.position(endings.get(i), "endingRules.endings[" + i + "]");
        }
        return c;
    }

    private static AppException fail(String path, String message) {
        return new AppException(ErrorCode.VALIDATION_FAILED, path + ": " + message);
    }

    // ------------------------------------------------------------------ đường đi

    /** Các hướng đi ra của một cảnh, đúng như game đang chạy. Thiếu đích thì ghi vào missing (game sẽ rơi sang cảnh kế trong mảng). */
    private static List<String> exits(JsonNode s, List<String> missing, String path) {
        List<String> out = new ArrayList<>();
        switch (s.path("type").asText()) {
            case "ending" -> { }
            case "choice" -> {
                JsonNode opts = s.path("options");
                for (int i = 0; i < opts.size(); i++) {
                    String t = opts.get(i).path("nextSceneId").asText("");
                    if (t.isBlank()) {
                        missing.add(path + ".options[" + i + "]: chưa chọn đi tiếp tới cảnh nào");
                    } else {
                        out.add(t);
                    }
                }
            }
            case "conditional" -> {
                out.add(s.path("ifTrueSceneId").asText(""));
                out.add(s.path("ifFalseSceneId").asText(""));
            }
            case "minigame" -> {
                String t = s.path("nextSceneId").asText(s.path("onPassSceneId").asText(""));
                if (t.isBlank()) {
                    missing.add(path + ": mini-game chưa chọn chơi xong thì sang cảnh nào");
                } else {
                    out.add(t);
                }
            }
            default -> {
                String t = s.path("nextSceneId").asText("");
                if (t.isBlank()) {
                    missing.add(path + ": chưa chọn cảnh kế tiếp");
                } else {
                    out.add(t);
                }
            }
        }
        String review = s.path("reviewAction").path("targetSceneId").asText("");
        if (!review.isBlank()) {
            out.add(review);
        }
        out.removeIf(String::isBlank);
        return out;
    }

    private List<String> flowProblems(JsonNode root) {
        JsonNode scenes = root.path("scenes");
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < scenes.size(); i++) {
            index.put(scenes.get(i).path("id").asText(), i);
        }
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> out = new HashMap<>();
        Map<String, List<String>> in = new HashMap<>();
        Set<String> endings = new HashSet<>();
        for (int i = 0; i < scenes.size(); i++) {
            JsonNode s = scenes.get(i);
            String id = s.path("id").asText();
            List<String> exits = exits(s, problems, "scenes[" + i + "]");
            out.put(id, exits);
            exits.forEach(t -> in.computeIfAbsent(t, k -> new ArrayList<>()).add(id));
            if ("ending".equals(s.path("type").asText())) {
                endings.add(id);
            }
        }
        if (endings.isEmpty()) {
            problems.add("chương chưa có cảnh Kết");
        }
        Set<String> fromStart = reach(List.of(scenes.get(0).path("id").asText()), out);
        Set<String> toEnding = reach(endings, in);
        for (int i = 0; i < scenes.size(); i++) {
            String id = scenes.get(i).path("id").asText();
            if (!fromStart.contains(id)) {
                problems.add("scenes[" + i + "] " + id + ": không đường nào từ cảnh đầu tới được");
            } else if (!toEnding.contains(id)) {
                problems.add("scenes[" + i + "] " + id + ": từ đây không tới được cảnh Kết (ngõ cụt hoặc vòng lặp không lối ra)");
            }
        }
        // Chương "chốt quyết định": đã chọn (có ghi sổ) thì quay lại câu hỏi đó cũng không chọn được nữa → trẻ kẹt.
        if (root.path("metadata").path("lockDecisions").asBoolean(false)) {
            for (int i = 0; i < scenes.size(); i++) {
                JsonNode s = scenes.get(i);
                if (!"choice".equals(s.path("type").asText())) {
                    continue;
                }
                String id = s.path("id").asText();
                JsonNode opts = s.path("options");
                for (int j = 0; j < opts.size(); j++) {
                    JsonNode o = opts.get(j);
                    String t = o.path("nextSceneId").asText("");
                    if (!o.path("deferLedger").asBoolean(false) && index.containsKey(t) && reach(List.of(t), out).contains(id)) {
                        problems.add("scenes[" + i + "].options[" + j + "]: chọn xong vẫn có đường quay lại câu hỏi này — chương chốt quyết định nên trẻ sẽ kẹt");
                    }
                }
            }
        }
        return problems;
    }

    private static Set<String> reach(Iterable<String> starts, Map<String, List<String>> edges) {
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        starts.forEach(q::add);
        while (!q.isEmpty()) {
            String x = q.poll();
            if (seen.add(x)) {
                q.addAll(edges.getOrDefault(x, List.of()));
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ từng cảnh

    /** Một lượt kiểm: tập id cảnh, nhóm quyết định → mã lựa chọn, mã thẻ xem, mã lựa chọn đã gặp và cảnh báo. */
    private final class Check {
        final Set<String> sceneIds;
        final Map<String, Set<String>> optionsByGroup;
        final Set<String> inspectIds;
        // id lựa chọn → (nhóm quyết định, cảnh đầu tiên dùng). Cùng nhóm (các phiên bản của một câu hỏi, vd CH04_RECEIPT) dùng chung
        // mã là đúng; khác nhóm mới nhắc — BE chơi tìm lựa chọn theo (cảnh, id) nên trùng chỉ nhắc.
        final Map<String, String[]> optionScenes = new HashMap<>();
        String currentGroup;
        final List<String> warnings = new ArrayList<>();

        Check(Set<String> sceneIds, Map<String, Set<String>> optionsByGroup, Set<String> inspectIds) {
            this.sceneIds = sceneIds;
            this.optionsByGroup = optionsByGroup;
            this.inspectIds = inspectIds;
        }

        void warn(String w) {
            warnings.add(w);
        }

        void scene(JsonNode s, String path) {
            String raw = s.path("type").asText("");
            String type = typeByName.get(raw);
            if (type == null) {
                throw fail(path + ".type", "loại cảnh '" + raw + "' không có trong bộ khuôn " + new TreeSet<>(typeByName.keySet()));
            }
            if (!type.equals(raw)) {
                warn(path + ".type: tên cũ '" + raw + "' — tên mới là '" + type + "'");
            }
            currentGroup = choiceGroupOf(s);
            fields(s, sceneFields.get(type), path);
            if (s.hasNonNull("onFailSceneId")) {
                warn(path + ".onFailSceneId: trường thừa — game không dùng, \"chưa đạt\" chỉ tính vào điểm (Hưng 24/10 bỏ hẳn)");
            }
            JsonNode review = s.path("reviewAction").path("targetSceneId");
            if (!review.isMissingNode() && !sceneIds.contains(review.asText())) {
                throw fail(path + ".reviewAction.targetSceneId", "cảnh '" + review.asText() + "' không tồn tại");
            }
        }

        void fields(JsonNode node, Map<String, JsonNode> defs, String path) {
            for (Map.Entry<String, JsonNode> e : defs.entrySet()) {
                String name = e.getKey();
                JsonNode def = e.getValue();
                JsonNode v = node.get(name);
                String p = path + "." + name;
                boolean missing = v == null || v.isNull() || (v.isTextual() && v.asText().isBlank());
                if (missing) {
                    if (def.path("required").asBoolean(false)) {
                        throw fail(p, "bắt buộc");
                    }
                    continue;
                }
                value(v, def, p);
            }
        }

        void value(JsonNode v, JsonNode def, String p) {
            switch (def.path("type").asText()) {
                case "string", "image" -> {
                    if (!v.isTextual()) {
                        throw fail(p, "phải là chuỗi");
                    }
                }
                case "sceneRef" -> {
                    if (!v.isTextual() || !sceneIds.contains(v.asText())) {
                        throw fail(p, "cảnh '" + v.asText() + "' không tồn tại");
                    }
                }
                case "array" -> {
                    if (!v.isArray()) {
                        throw fail(p, "phải là mảng");
                    }
                    if (def.has("items")) {
                        for (int i = 0; i < v.size(); i++) {
                            if (!v.get(i).isNull()) {
                                value(v.get(i), def.get("items"), p + "[" + i + "]");
                            }
                        }
                    }
                }
                case "object" -> {
                    if (!v.isObject()) {
                        throw fail(p, "phải là object");
                    }
                    if (def.has("fields")) {
                        fields(v, defsOf(def.get("fields")), p);
                    }
                }
                case "map" -> {   // object khoá là mã (A / B / "1"…), mọi giá trị cùng một kiểu
                    if (!v.isObject()) {
                        throw fail(p, "phải là object {mã: giá trị}");
                    }
                    if (def.has("values")) {
                        v.properties().forEach(e -> value(e.getValue(), def.get("values"), p + "." + e.getKey()));
                    }
                }
                case "number" -> {
                    if (!v.isNumber()) {
                        throw fail(p, "phải là số");
                    }
                }
                case "boolean" -> {
                    if (!v.isBoolean()) {
                        throw fail(p, "phải là true / false");
                    }
                }
                case "characters" -> characters(v, p);
                case "inspect" -> inspect(v, p);
                case "options" -> options(v, def, p);
                case "stats" -> statsDelta(v, p);
                case "flags" -> {
                    if (!v.isObject()) {
                        throw fail(p, "phải là object {tên cờ: true|false}");
                    }
                }
                case "condition" -> condition(v, p);
                case "game" -> game(v, p);
                default -> { /* kiểu chưa khai luật kiểm — để nguyên */ }
            }
        }

        void characters(JsonNode v, String p) {
            if (!v.isArray()) {
                throw fail(p, "phải là mảng");
            }
            for (int i = 0; i < v.size(); i++) {
                position(v.get(i), p + "[" + i + "]");
            }
        }

        /**
         * position giữ NGUYÊN chữ người soạn ghi, chỉ cảnh báo khi khác chữ thường. "LEFT" (19 chỗ CH01–02) FE không hiểu nên nhân vật
         * đứng sát mép; tự đổi sang "left" làm nhân vật dịch vào 8 % — đổi hình mà không ai quyết (Hưng 24/10: ghép lại phải y bản cũ).
         */
        void position(JsonNode c, String p) {
            JsonNode pos = c.get("position");
            if (pos == null || pos.isNull()) {
                return;
            }
            String norm = pos.asText().trim().toLowerCase(Locale.ROOT);
            if (!positions.contains(norm)) {
                throw fail(p + ".position", "phải là " + positions);
            }
            if (!norm.equals(pos.asText())) {
                warn(p + ".position: '" + pos.asText() + "' FE chưa hiểu (nhân vật đứng sát mép) — sửa thành '" + norm + "' sẽ dịch nhân vật vào");
            }
        }

        void inspect(JsonNode v, String p) {
            if (!v.isObject()) {
                throw fail(p, "phải là object");
            }
            JsonNode h = v.get("hotspot");
            if (h == null || h.isNull()) {
                return;
            }
            for (String k : List.of("x", "y", "width", "height")) {
                JsonNode n = h.get(k);
                if (n != null && !n.isNull() && (!n.isNumber() || n.asDouble() < 0 || n.asDouble() > 100)) {
                    throw fail(p + ".hotspot." + k, "phải là số từ 0 đến 100 (% khung hình)");
                }
            }
        }

        void options(JsonNode v, JsonNode def, String p) {
            if (!v.isArray()) {
                throw fail(p, "phải là mảng");
            }
            int min = def.path("minItems").asInt(0);
            if (v.size() < min) {
                throw fail(p, "cần ít nhất " + min + " lựa chọn");
            }
            // 1 lựa chọn là màn "Tiếp tục" có thật (CH05_SC04_RECAP) — cho qua, chỉ nhắc.
            if (v.size() < def.path("warnBelowItems").asInt(0)) {
                warn(p + ": chỉ " + v.size() + " lựa chọn — như nút tiếp tục");
            }
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < v.size(); i++) {
                JsonNode o = v.get(i);
                String q = p + "[" + i + "]";
                // Dữ liệu FE cũ có lựa chọn chỉ ghi "label" — chấp nhận, không sửa nội dung.
                if (!o.hasNonNull("text") && !o.hasNonNull("label")) {
                    throw fail(q + ".text", "bắt buộc");
                }
                fields(o, optionFields, q);
                String id = o.path("id").asText();
                if (!ids.add(id)) {
                    throw fail(q + ".id", "mã lựa chọn trùng trong cảnh: " + id);
                }
                String[] first = optionScenes.putIfAbsent(id, new String[] {currentGroup, p});
                if (first != null && !first[0].equals(currentGroup)) {
                    warn(q + ".id: mã '" + id + "' đã dùng ở " + first[1] + " (câu hỏi khác) — nên đặt mã riêng");
                }
                // Khoá lựa chọn: MỘT cơ chế = condition theo khuôn + câu nhắn cho trẻ (Hưng 24/10 "cách 2").
                if (isStructured(o.get("condition")) && o.path("lockedReason").asText("").isBlank()) {
                    throw fail(q + ".lockedReason", "bắt buộc khi lựa chọn có điều kiện mở — câu trẻ thấy lúc bị khoá");
                }
                if (o.has("requires")) {
                    warn(q + ".requires: khoá dạng cũ — chuyển sang condition + lockedReason");
                }
            }
        }

        void statsDelta(JsonNode v, String p) {
            if (!v.isObject()) {
                throw fail(p, "phải là object {chỉ số: số}");
            }
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isNumber()) {
                    throw fail(p + "." + e.getKey(), "phải là số");
                }
                if (!stats.contains(e.getKey())) {
                    warn(p + "." + e.getKey() + ": chỉ số không có trong bộ khuôn — BE bỏ qua khi tính điểm");
                }
            }
        }

        /** Điều kiện có cấu trúc {all|any:[…]} / {var, op, value}; chuỗi và dạng {expression|check|type} cũ → cảnh báo. */
        void condition(JsonNode v, String p) {
            if (v == null || v.isNull()) {
                return;
            }
            if (v.isTextual()) {
                warn(p + ": điều kiện dạng chuỗi (cũ) — chuyển sang {var, op, value}");
                return;
            }
            if (!v.isObject()) {
                throw fail(p, "điều kiện phải là object");
            }
            for (String group : List.of("all", "any")) {
                if (v.has(group)) {
                    JsonNode items = v.get(group);
                    if (!items.isArray() || items.isEmpty()) {
                        throw fail(p + "." + group, "phải là mảng điều kiện không rỗng");
                    }
                    for (int i = 0; i < items.size(); i++) {
                        condition(items.get(i), p + "." + group + "[" + i + "]");
                    }
                    return;
                }
            }
            if (v.has("var")) {
                variable(v, p);
                if (!ops.contains(v.path("op").asText())) {
                    throw fail(p + ".op", "phép so sánh phải là " + ops);
                }
                if (!v.has("value") || !(v.get("value").isNumber() || v.get("value").isBoolean() || v.get("value").isTextual())) {
                    throw fail(p + ".value", "phải là số, true / false hoặc chuỗi");
                }
                return;
            }
            if (v.has("expression") || v.has("check") || v.has("type")) {
                warn(p + ": điều kiện dạng cũ — chuyển sang {var, op, value}");
                return;
            }
            throw fail(p, "điều kiện không hiểu — dùng {all|any:[…]} hoặc {var, op, value}");
        }

        void variable(JsonNode v, String p) {
            String var = v.path("var").asText();
            if (variables.contains(var)) {
                return;
            }
            String prefix = variablePrefixes.stream().filter(x -> var.startsWith(x) && var.length() > x.length()).findFirst()
                    .orElseThrow(() -> fail(p + ".var", "biến '" + var + "' không có trong bộ khuôn"));
            String name = var.substring(prefix.length());
            switch (prefix) {
                case "choice:" -> {
                    Set<String> optionIds = optionsByGroup.get(name);
                    if (optionIds == null) {
                        throw fail(p + ".var", "không có câu hỏi / nhóm quyết định '" + name + "'");
                    }
                    if (!optionIds.contains(v.path("value").asText())) {
                        throw fail(p + ".value", "'" + v.path("value").asText() + "' không phải lựa chọn của '" + name + "' " + new TreeSet<>(optionIds));
                    }
                }
                case "inspected:" -> {
                    if (!inspectIds.contains(name)) {
                        warn(p + ".var: không cảnh nào có thẻ xem '" + name + "' — điều kiện sẽ không bao giờ đúng");
                    }
                }
                default -> { }
            }
        }

        void game(JsonNode v, String p) {
            if (!v.isObject()) {
                throw fail(p, "phải là object");
            }
            gradesOf(v, p, this);
            String kind = v.path("kind").asText("");
            if (kind.isEmpty()) {
                String guess = kindByLegacyId.get(v.path("id").asText());
                warn(p + ".kind: thiếu — FE đang chọn khung theo id" + (guess == null ? "" : "; nên là '" + guess + "'"));
                return;
            }
            if (!gameKinds.contains(kind)) {
                throw fail(p + ".kind", "loại mini-game '" + kind + "' không có trong bộ khuôn " + new TreeSet<>(gameKinds));
            }
            Map<String, JsonNode> config = kindFields.get(kind);
            if (config != null) {
                fields(v, config, p);
            }
        }
    }

    /**
     * Câu chấm điểm ({@code grade}) trong mini-game: có mã, mã không trùng, luật đúng ngữ pháp ({@link MiniGameGrader}).
     * Không có câu nào → cảnh báo "không vào điểm" (đúng với game chỉ để chơi như Đua tiết kiệm).
     */
    private void gradesOf(JsonNode game, String p, Check c) {
        Set<String> ids = new HashSet<>();
        List<String> problems = new ArrayList<>();
        MiniGameGrader.collect(game, (node, grade) -> {
            String id = grade.path("id").isValueNode() ? grade.path("id").asText("") : "";
            if (id.isBlank()) {
                id = node.path("id").isValueNode() ? node.path("id").asText("") : "";
            }
            if (id.isBlank()) {
                problems.add(p + ": câu chấm điểm thiếu mã (grade.id)");
                return;
            }
            if (!ids.add(id)) {
                problems.add(p + ": mã câu '" + id + "' trùng trong mini-game");
            }
            JsonNode part = grade.get("part");
            if (part != null && !part.isValueNode()) {
                problems.add(p + " câu '" + id + "'.grade.part: phải là số hoặc chữ");
            }
            problems.addAll(MiniGameGrader.ruleProblems(grade.get("rule"), p + " câu '" + id + "'.grade.rule"));
        });
        if (!problems.isEmpty()) {
            throw fail(problems.get(0).split(":")[0], problems.get(0).substring(problems.get(0).indexOf(':') + 1).trim());
        }
        boolean playOnly = !schema.path("minigameKinds").path(game.path("kind").asText()).path("graded").asBoolean(true);
        if (ids.isEmpty() && !playOnly) {   // loại khuôn khai graded:false (Đua tiết kiệm) thì không chấm là đúng
            c.warn(p + ": mini-game không có câu chấm điểm (grade) — không vào điểm báo cáo");
        }
    }

    private static Map<String, JsonNode> defsOf(JsonNode fields) {
        Map<String, JsonNode> defs = new LinkedHashMap<>();
        fields.properties().forEach(e -> defs.put(e.getKey(), e.getValue()));
        return defs;
    }

    /** Điều kiện kiểu khuôn ({all|any} / {var}) — khác dạng cũ (chuỗi, {type: ALWAYS_UNLOCKED}…). */
    private static boolean isStructured(JsonNode c) {
        return c != null && c.isObject() && (c.has("all") || c.has("any") || c.has("var"));
    }
}
