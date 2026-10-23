package com.doan.game.service.impl;

import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bộ khuôn nội dung game (24/10, K1 — spec build/spec-bo-khuon-noi-dung-2026-10-24.md). Hưng chốt: không gán cứng, BE quyết khung.
 * Một nguồn duy nhất: resources/content/scene-schema.json — phát cho Studio (GET /api/studio/scene-schema) và dùng để kiểm nháp.
 *
 * Hai mức: sai rõ ràng → 400 / 1001 kèm đường dẫn trường; dạng CŨ còn trong CH01–08 (điều kiện chuỗi, mini-game thiếu kind, chỉ số FIQ…)
 * → cảnh báo, không chặn, cho tới khi chuyển xong (spec mục 9–10). Chuẩn hoá tại chỗ: characters[].position về chữ thường.
 */
@Component
public class ContentSchema {

    private final JsonNode schema;
    private final Map<String, String> typeByName = new HashMap<>();   // tên hoặc tên cũ → loại chuẩn
    private final Set<String> stats = new HashSet<>();
    private final Set<String> ops = new HashSet<>();
    private final Set<String> positions = new HashSet<>();
    private final Set<String> gameKinds = new HashSet<>();
    private final Map<String, String> kindByLegacyId = new HashMap<>();

    public ContentSchema(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("content/scene-schema.json").getInputStream()) {
            this.schema = objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được content/scene-schema.json", e);
        }
        schema.path("sceneTypes").fields().forEachRemaining(e -> {
            typeByName.put(e.getKey(), e.getKey());
            e.getValue().path("aliases").forEach(a -> typeByName.put(a.asText(), e.getKey()));
        });
        schema.path("stats").forEach(s -> stats.add(s.path("key").asText()));
        schema.path("conditionOps").forEach(o -> ops.add(o.asText()));
        schema.path("positions").forEach(p -> positions.add(p.asText()));
        for (String group : List.of("minigameKinds", "stepKinds")) {
            schema.path(group).fields().forEachRemaining(e -> {
                gameKinds.add(e.getKey());
                e.getValue().path("legacyIds").forEach(id -> kindByLegacyId.put(id.asText(), e.getKey()));
            });
        }
    }

    /** Bộ khuôn nguyên văn — Studio dựng form từ đây. */
    public JsonNode schema() {
        return schema;
    }

    /** Kiểm một chương; ném 400 / 1001 khi sai, trả danh sách cảnh báo dạng cũ. Sửa tại chỗ phần chuẩn hoá được. */
    public List<String> validate(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw fail("content", "phải là JSON object của một chương");
        }
        JsonNode scenes = root.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) {
            throw fail("scenes", "phải là mảng không rỗng");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < scenes.size(); i++) {
            String id = scenes.get(i).path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw fail("scenes[" + i + "].id", "mỗi cảnh phải có id");
            }
            if (!ids.add(id)) {
                throw fail("scenes[" + i + "].id", "id trùng " + id);
            }
        }
        Check c = new Check(ids);
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
        return c.warnings;
    }

    private static AppException fail(String path, String message) {
        return new AppException(ErrorCode.VALIDATION_FAILED, path + ": " + message);
    }

    /** Một lượt kiểm: giữ tập id cảnh, id lựa chọn đã gặp và danh sách cảnh báo. */
    private final class Check {
        final Set<String> sceneIds;
        final Set<String> optionIds = new HashSet<>();
        final List<String> warnings = new ArrayList<>();

        Check(Set<String> sceneIds) {
            this.sceneIds = sceneIds;
        }

        void warn(String w) {
            warnings.add(w);
        }

        void scene(JsonNode s, String path) {
            String raw = s.path("type").asText("");
            String type = typeByName.get(raw);
            if (type == null) {
                throw fail(path + ".type", "loại cảnh '" + raw + "' không có trong bộ khuôn " + new java.util.TreeSet<>(typeByName.keySet()));
            }
            if (!type.equals(raw)) {
                warn(path + ".type: tên cũ '" + raw + "' — tên mới là '" + type + "'");
            }
            Map<String, JsonNode> defs = new LinkedHashMap<>();
            schema.path("commonSceneFields").fields().forEachRemaining(e -> defs.put(e.getKey(), e.getValue()));
            schema.path("sceneTypes").path(type).path("fields").fields().forEachRemaining(e -> defs.put(e.getKey(), e.getValue()));
            fields(s, defs, path);
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
                value(node, name, v, def, p);
            }
        }

        void value(JsonNode parent, String name, JsonNode v, JsonNode def, String p) {
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
                }
                case "object" -> {
                    if (!v.isObject()) {
                        throw fail(p, "phải là object");
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
            Map<String, JsonNode> defs = new LinkedHashMap<>();
            schema.path("optionFields").fields().forEachRemaining(e -> defs.put(e.getKey(), e.getValue()));
            for (int i = 0; i < v.size(); i++) {
                JsonNode o = v.get(i);
                // Dữ liệu FE cũ có lựa chọn chỉ ghi "label" — chấp nhận, không sửa nội dung.
                if (!o.hasNonNull("text") && !o.hasNonNull("label")) {
                    throw fail(p + "[" + i + "].text", "bắt buộc");
                }
                fields(o, defs, p + "[" + i + "]");
                String id = o.path("id").asText();
                if (!optionIds.add(id)) {
                    throw fail(p + "[" + i + "].id", "mã lựa chọn trùng trong chương: " + id);
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
                String var = v.path("var").asText();
                boolean known = var.startsWith("flag:") || containsText(schema.path("variables"), var);
                if (!known) {
                    throw fail(p + ".var", "biến '" + var + "' không có trong bộ khuôn");
                }
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

        void game(JsonNode v, String p) {
            if (!v.isObject()) {
                throw fail(p, "phải là object");
            }
            String kind = v.path("kind").asText("");
            if (kind.isEmpty()) {
                String guess = kindByLegacyId.get(v.path("id").asText());
                warn(p + ".kind: thiếu — FE đang chọn khung theo id" + (guess == null ? "" : "; nên là '" + guess + "'"));
                return;
            }
            if (!gameKinds.contains(kind)) {
                throw fail(p + ".kind", "loại mini-game '" + kind + "' không có trong bộ khuôn " + new java.util.TreeSet<>(gameKinds));
            }
        }
    }

    private static boolean containsText(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (n.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
