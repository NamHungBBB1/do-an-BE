package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Chữ cho một câu mini-game bé làm chưa đúng (báo cáo người lớn, đề xuất FE HA / Triệu, #chung 19/11): nhãn câu, ô bé chọn và
 * đáp án viết thành chữ, lời giải thích. Tra trong JSON ĐÓNG BĂNG của bản bé đã chơi (buildVersion, bản gốc không cá nhân
 * hoá) — không bao giờ bản hiện hành. Không tra được thì null (FE có chữ dự phòng); không bao giờ trả mã thô.
 *
 * Câu có grade: luật của câu (MiniGameGrader). Mini-game kiểu cũ: object có id = itemId, đáp án ở category / correctId /
 * expectedValue. Giá trị dạng mã (NEEDS, a, amt_600…) → nhãn: trước hết options của chính câu, rồi object cùng id ở bất kỳ
 * đâu trong game (zones, trays, draggables…). Luật không phân loại (min / max / has / sets / count / at) → câu mô tả ngắn.
 */
final class MiniGameLabels {

    record Labels(String label, String chosenLabel, String expected, String expectedLabel, String explanation) {}

    static final Labels NONE = new Labels(null, null, null, null, null);

    private static final List<String> LABEL_KEYS = List.of("label", "title", "name", "prompt", "text");
    private static final List<String> EXPLAIN_KEYS = List.of("explanation", "explain", "feedback", "hint");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final ObjectMapper JSON = new ObjectMapper();

    private MiniGameLabels() {
    }

    static Labels of(JsonNode scene, String itemId, String chosen) {
        if (scene == null || itemId == null) {
            return NONE;
        }
        JsonNode game = scene.path("game");
        JsonNode node = null;
        JsonNode rule = null;
        String input = itemId;
        for (MiniGameGrader.Question q : MiniGameGrader.questions(scene)) {
            if (itemId.equals(q.id())) {
                node = q.node();
                rule = q.rule();
                input = q.input();
                break;
            }
        }
        if (node == null) {
            node = findById(game, itemId);
            if (node == null) {
                return NONE;
            }
            JsonNode answer = firstValue(node, List.of("category", "correctId", "expectedValue"));
            rule = answer == null ? null : JsonNodeFactory.instance.objectNode().set("eq", answer);
        }
        Index idx = new Index(game, node);
        JsonNode eq = rule == null ? null : rule.get("eq");
        String expected = rule == null || rule.isMissingNode() ? null
                : eq != null && eq.isValueNode() ? eq.asText() : rule.toString();
        return new Labels(text(node, LABEL_KEYS), idx.chosen(chosen), expected, idx.describe(rule, input),
                text(node, EXPLAIN_KEYS));
    }

    /** Nhãn của các giá trị theo một câu: options của câu trước, rồi mọi object có id trong game. */
    private static final class Index {
        private final Map<String, String> labels = new HashMap<>();
        private final JsonNode options;

        Index(JsonNode game, JsonNode node) {
            this.options = node.path("options");
            collect(game);
        }

        private void collect(JsonNode n) {
            if (n.isObject()) {
                JsonNode id = n.get("id");
                String label = text(n, List.of("label", "title", "name", "text"));
                if (id != null && id.isValueNode() && label != null) {
                    labels.putIfAbsent(id.asText(), label);
                }
                n.forEach(this::collect);
            } else if (n.isArray()) {
                n.forEach(this::collect);
            }
        }

        /** Ô bé chọn: giá trị đơn, hoặc mảng (mọi phần tử phải tra được); object nhiều ô → null. */
        String chosen(String chosen) {
            if (chosen == null || chosen.isBlank()) {
                return null;
            }
            String t = chosen.trim();
            if (t.startsWith("[") || t.startsWith("{")) {
                JsonNode j = read(t);
                return j != null && j.isArray() ? list(j, ", ") : null;
            }
            return value(TextNode.valueOf(t));
        }

        String value(JsonNode v) {
            if (v == null || v.isNull() || v.isMissingNode() || v.isContainerNode() || v.isBoolean()) {
                return null;
            }
            if (v.isNumber()) {
                return money(v.decimalValue());
            }
            String s = v.asText();
            for (JsonNode o : options) {
                if (o.isValueNode() && s.equals(o.asText())) {
                    return s;
                }
                if (o.isObject() && s.equals(o.path("id").asText(null))) {
                    return text(o, LABEL_KEYS);
                }
            }
            String l = labels.get(s);
            if (l != null) {
                return l;
            }
            BigDecimal n = number(s);
            return n == null ? null : money(n);
        }

        private String list(JsonNode arr, String sep) {
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                return null;
            }
            Set<String> out = new LinkedHashSet<>();   // hai thẻ cùng nhãn ("200.000đ" và thẻ dự phòng 200.000đ) chỉ nói một lần
            for (JsonNode v : arr) {
                String l = value(v);
                if (l == null) {
                    return null;
                }
                out.add(l);
            }
            return String.join(sep, out);
        }

        /** Đáp án viết thành chữ; phép nào không mô tả được (sum / sumOf, ô hay trường không có nhãn) → null cả câu. */
        String describe(JsonNode rule, String input) {
            if (rule == null || !rule.isObject() || rule.isEmpty()) {
                return null;
            }
            for (String group : List.of("all", "any")) {
                if (rule.has(group)) {
                    List<String> parts = new ArrayList<>();
                    for (JsonNode r : rule.get(group)) {
                        String d = describe(r, input);
                        if (d == null) {
                            return null;
                        }
                        parts.add(d);
                    }
                    return parts.isEmpty() ? null : String.join("all".equals(group) ? "; " : " hoặc ", parts);
                }
            }
            if (rule.has("sum") || rule.has("sumOf")) {
                return null;
            }
            String subject = null;
            String leafInput = rule.path("input").asText(input);
            if (!Objects.equals(leafInput, input)) {
                subject = labels.get(leafInput);
                if (subject == null) {
                    return null;
                }
            }
            if (rule.has("field")) {
                String f = labels.get(rule.get("field").asText());
                if (f == null) {
                    return null;
                }
                subject = subject == null ? f : subject + " — " + f;
            }
            List<String> parts = new ArrayList<>();
            if (rule.has("eq")) {
                parts.add(value(rule.get("eq")));
            }
            if (rule.has("in")) {
                parts.add(list(rule.get("in"), " hoặc "));
            }
            if (rule.has("min") && rule.has("max")) {
                parts.add("từ " + value(rule.get("min")) + " đến " + value(rule.get("max")));
            } else if (rule.has("min")) {
                parts.add("≥ " + value(rule.get("min")));
            } else if (rule.has("max")) {
                parts.add("≤ " + value(rule.get("max")));
            }
            if (rule.has("has")) {
                String l = list(rule.get("has"), ", ");
                parts.add(l == null ? null : "có " + l);
            }
            if (rule.has("sets")) {
                List<String> sets = new ArrayList<>();
                for (JsonNode set : rule.get("sets")) {
                    String l = list(set, ", ");
                    sets.add(l == null ? null : "đúng " + set.size() + " mục: " + l);
                }
                parts.add(sets.contains(null) ? null : String.join(" hoặc ", sets));
            }
            if (rule.has("count")) {
                JsonNode c = rule.get("count");
                parts.add(c.has("min") && c.has("max") ? "chọn từ " + c.get("min").asInt() + " đến " + c.get("max").asInt()
                        + " mục" : c.has("min") ? "chọn ít nhất " + c.get("min").asInt() + " mục"
                        : "chọn nhiều nhất " + c.path("max").asInt() + " mục");
            }
            if (rule.has("at")) {
                JsonNode at = rule.get("at");
                String item = value(at.get("item"));
                List<String> pos = new ArrayList<>();
                at.path("in").forEach(p -> pos.add(String.valueOf(p.asInt() + 1)));
                parts.add(item == null ? null : "“" + item + "” ở vị trí " + String.join(" hoặc ", pos));
            }
            if (parts.isEmpty() || parts.contains(null)) {
                return null;
            }
            String s = String.join(", ", parts);
            return subject == null ? s : subject + ": " + s;
        }
    }

    // ponytail: số ≥ 1000 coi là tiền (thêm "đ") — nội dung hiện không có số lớn nào khác; cần thì thêm đơn vị vào grade.
    static String money(BigDecimal n) {
        BigDecimal v = n.stripTrailingZeros();
        String s;
        if (v.scale() <= 0) {
            String digits = v.abs().toBigInteger().toString();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0 && (digits.length() - i) % 3 == 0) {
                    b.append('.');
                }
                b.append(digits.charAt(i));
            }
            s = (v.signum() < 0 ? "-" : "") + b;
        } else {
            s = v.toPlainString().replace('.', ',');
        }
        return v.abs().compareTo(THOUSAND) >= 0 ? s + "đ" : s;
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, List<String> keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    private static JsonNode firstValue(JsonNode node, List<String> keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isValueNode() && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static JsonNode findById(JsonNode n, String id) {
        if (n.isObject()) {
            if (id.equals(n.path("id").asText(null))) {
                return n;
            }
            for (JsonNode c : n) {
                JsonNode hit = findById(c, id);
                if (hit != null) {
                    return hit;
                }
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) {
                JsonNode hit = findById(c, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
