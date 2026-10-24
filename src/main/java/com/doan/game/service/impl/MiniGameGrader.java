package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Hàm chấm mini-game DUY NHẤT (Hưng 24/10: "ta sẽ có hàm chấm ở BE — FE chỉ gửi"; spec
 * build/spec-cham-minigame-be-2026-10-24.md). Câu được chấm = đối tượng có trường {@code grade} nằm trong
 * {@code game} của cảnh; đáp án đặt tại câu, bằng một tập phép đóng (mục 2). FE gửi trẻ nhập gì ở từng ô nhập;
 * ở đây không biết gì về từng loại mini-game.
 */
public final class MiniGameGrader {

    /** Phép so của một lá; lá phải có ít nhất một phép. */
    private static final Set<String> COMPARATORS = Set.of("eq", "in", "sets", "has", "at", "count", "min", "max");
    /** Bổ trợ đổi giá trị trước khi so. */
    private static final Set<String> MODIFIERS = Set.of("input", "field", "sum", "sumOf");

    private MiniGameGrader() {
    }

    /** Một câu được chấm: mã câu, ô nhập mặc định, khái niệm, luật. */
    public record Question(String id, String input, String concept, JsonNode rule) {
    }

    /** Kết quả một câu: {@code chosen} = giá trị các ô câu đó đọc (chuỗi gọn, null khi trẻ không gửi). */
    public record Graded(String questionId, String concept, boolean correct, String chosen) {
    }

    /** Mọi câu {@code grade} của cảnh mini-game, đã lọc theo phần cảnh dùng ({@code game.rounds} / {@code config.mode}). */
    public static List<Question> questions(JsonNode scene) {
        JsonNode game = scene.path("game");
        Set<String> parts = activeParts(game);
        String sceneConcept = singleConcept(scene);
        List<Question> out = new ArrayList<>();
        collect(game, (node, grade) -> {
            JsonNode part = grade.get("part");
            if (part != null && parts != null && !parts.contains(part.asText())) {
                return;
            }
            String id = text(grade.get("id"), text(node.get("id"), null));
            String concept = text(grade.get("concept"), sceneConcept);
            out.add(new Question(id, text(grade.get("input"), id), concept, grade.path("rule")));
        });
        return out;
    }

    /** Chấm mọi câu của cảnh; {@code inputs} = ô nhập → giá trị trẻ gửi (đã bỏ trùng, giữ lần đầu). */
    public static List<Graded> grade(JsonNode scene, Map<String, JsonNode> inputs) {
        JsonNode game = scene.path("game");
        List<Graded> out = new ArrayList<>();
        for (Question q : questions(scene)) {
            Set<String> used = new LinkedHashSet<>();
            inputsOf(q.rule(), q.input(), used);
            boolean correct = check(q.rule(), q.input(), inputs, game);
            out.add(new Graded(q.id(), q.concept(), correct, chosenOf(used, inputs)));
        }
        return out;
    }

    /** Ô nhập mọi câu của cảnh đọc tới — ô FE gửi mà không nằm đây thì bỏ qua (log ở nơi gọi). */
    public static Set<String> inputsUsed(JsonNode scene) {
        Set<String> used = new HashSet<>();
        questions(scene).forEach(q -> inputsOf(q.rule(), q.input(), used));
        return used;
    }

    // ---------------------------------------------------------------- chấm

    static boolean check(JsonNode rule, String input, Map<String, JsonNode> inputs, JsonNode game) {
        if (rule == null || !rule.isObject()) {
            return false;
        }
        if (rule.has("all")) {
            for (JsonNode r : rule.get("all")) {
                if (!check(r, input, inputs, game)) {
                    return false;
                }
            }
            return true;
        }
        if (rule.has("any")) {
            for (JsonNode r : rule.get("any")) {
                if (check(r, input, inputs, game)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode value = inputs.get(text(rule.get("input"), input));
        value = transform(rule, value, game);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        boolean any = false;
        for (String op : COMPARATORS) {
            JsonNode arg = rule.get(op);
            if (arg == null) {
                continue;
            }
            any = true;
            if (!compare(op, arg, value)) {
                return false;
            }
        }
        return any;
    }

    /** field / sum / sumOf: đổi giá trị trước khi so; không áp được → null (lá sai). */
    private static JsonNode transform(JsonNode rule, JsonNode value, JsonNode game) {
        if (value == null) {
            return null;
        }
        if (rule.has("field")) {
            value = value.isObject() ? value.get(rule.get("field").asText()) : null;
        }
        if (value != null && rule.has("sum")) {
            if (!value.isObject()) {
                return null;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode f : rule.get("sum")) {
                BigDecimal n = number(value.get(f.asText()));
                if (n == null) {
                    return null;
                }
                total = total.add(n);
            }
            value = DecimalNode.valueOf(total);
        }
        if (value != null && rule.has("sumOf")) {
            if (!value.isArray()) {
                return null;
            }
            JsonNode spec = rule.get("sumOf");
            JsonNode items = at(game, spec.path("from").asText());
            String field = spec.path("field").asText();
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode id : value) {
                JsonNode item = byId(items, id.asText());
                BigDecimal n = item == null ? null : number(item.get(field));
                if (n == null) {
                    return null;
                }
                total = total.add(n);
            }
            value = DecimalNode.valueOf(total);
        }
        return value;
    }

    private static boolean compare(String op, JsonNode arg, JsonNode value) {
        return switch (op) {
            case "eq" -> same(arg, value);
            case "in" -> {
                for (JsonNode a : arg) {
                    if (same(a, value)) {
                        yield true;
                    }
                }
                yield false;
            }
            case "sets" -> {
                if (!value.isArray()) {
                    yield false;
                }
                Set<String> chosen = textSet(value);
                if (chosen.size() != value.size()) {
                    yield false; // chọn trùng một thẻ hai lần không phải một tập hợp lệ
                }
                for (JsonNode set : arg) {
                    if (textSet(set).equals(chosen)) {
                        yield true;
                    }
                }
                yield false;
            }
            case "has" -> value.isArray() && textSet(value).containsAll(textSet(arg));
            case "at" -> {
                if (!value.isArray()) {
                    yield false;
                }
                int index = -1;
                for (int i = 0; i < value.size(); i++) {
                    if (value.get(i).asText().equals(arg.path("item").asText())) {
                        index = i;
                        break;
                    }
                }
                for (JsonNode p : arg.path("in")) {
                    if (p.asInt() == index) {
                        yield true;
                    }
                }
                yield false;
            }
            case "count" -> value.isArray()
                    && (!arg.has("min") || value.size() >= arg.get("min").asInt())
                    && (!arg.has("max") || value.size() <= arg.get("max").asInt());
            case "min", "max" -> {
                BigDecimal n = number(value);
                BigDecimal limit = number(arg);
                yield n != null && limit != null && ("min".equals(op) ? n.compareTo(limit) >= 0 : n.compareTo(limit) <= 0);
            }
            default -> false;
        };
    }

    /** Bằng nhau: đáp án là số → so theo giá trị ("15" = 15, chuỗi rỗng sai); còn lại so chữ của giá trị đơn. */
    private static boolean same(JsonNode expected, JsonNode value) {
        if (value.isContainerNode()) {
            return false;
        }
        if (expected.isNumber()) {
            BigDecimal n = number(value);
            return n != null && n.compareTo(expected.decimalValue()) == 0;
        }
        if (expected.isBoolean()) {
            return value.isBoolean() ? value.asBoolean() == expected.asBoolean() : value.asText().equals(expected.asText());
        }
        return value.asText().equals(expected.asText());
    }

    private static BigDecimal number(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            String s = node.asText().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------- kiểm ngữ pháp (ContentSchema dùng)

    /** Lỗi ngữ pháp của một {@code grade.rule}; rỗng = hợp lệ. */
    public static List<String> ruleProblems(JsonNode rule, String path) {
        List<String> out = new ArrayList<>();
        ruleProblems(rule, path, out);
        return out;
    }

    private static void ruleProblems(JsonNode rule, String path, List<String> out) {
        if (rule == null || !rule.isObject() || rule.isEmpty()) {
            out.add(path + ": cần một luật (object) — xem mục grading của bộ khuôn");
            return;
        }
        for (String group : List.of("all", "any")) {
            if (rule.has(group)) {
                JsonNode list = rule.get(group);
                if (rule.size() != 1) {
                    out.add(path + ": '" + group + "' không đi chung với khoá khác");
                }
                if (!list.isArray() || list.isEmpty()) {
                    out.add(path + "." + group + ": cần một mảng luật không rỗng");
                    return;
                }
                for (int i = 0; i < list.size(); i++) {
                    ruleProblems(list.get(i), path + "." + group + "[" + i + "]", out);
                }
                return;
            }
        }
        boolean hasComparator = false;
        for (var e : rule.properties()) {
            String key = e.getKey();
            JsonNode v = e.getValue();
            if (COMPARATORS.contains(key)) {
                hasComparator = true;
            } else if (!MODIFIERS.contains(key)) {
                out.add(path + ": phép '" + key + "' không có trong bộ khuôn");
                continue;
            }
            switch (key) {
                case "eq" -> {
                    if (v.isContainerNode() || v.isNull()) {
                        out.add(path + ".eq: cần một giá trị đơn (chữ / số / đúng-sai)");
                    }
                }
                case "in", "has" -> {
                    if (!v.isArray() || v.isEmpty()) {
                        out.add(path + "." + key + ": cần mảng không rỗng");
                    }
                }
                case "sets" -> {
                    if (!v.isArray() || v.isEmpty() || !allArrays(v)) {
                        out.add(path + ".sets: cần mảng các tập (mảng) không rỗng");
                    }
                }
                case "at" -> {
                    if (!v.isObject() || !v.has("item") || !v.path("in").isArray() || v.path("in").isEmpty()) {
                        out.add(path + ".at: cần {item, in: [vị trí…]}");
                    }
                }
                case "count" -> {
                    if (!v.isObject() || (!v.path("min").isNumber() && !v.path("max").isNumber())) {
                        out.add(path + ".count: cần {min} và / hoặc {max} là số");
                    }
                }
                case "min", "max" -> {
                    if (!v.isNumber()) {
                        out.add(path + "." + key + ": cần một số");
                    }
                }
                case "input", "field" -> {
                    if (!v.isTextual() || v.asText().isBlank()) {
                        out.add(path + "." + key + ": cần tên (chữ)");
                    }
                }
                case "sum" -> {
                    if (!v.isArray() || v.isEmpty()) {
                        out.add(path + ".sum: cần mảng tên trường");
                    }
                }
                case "sumOf" -> {
                    if (!v.isObject() || !v.path("from").isTextual() || !v.path("field").isTextual()) {
                        out.add(path + ".sumOf: cần {from: đường dẫn trong game, field}");
                    }
                }
                default -> {
                }
            }
        }
        if (!hasComparator) {
            out.add(path + ": thiếu phép so (eq / in / sets / has / at / count / min / max)");
        }
    }

    private static boolean allArrays(JsonNode v) {
        for (JsonNode x : v) {
            if (!x.isArray()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------- tiện ích

    /** Duyệt cây game, gọi {@code visit} cho mọi object có {@code grade} (object). */
    static void collect(JsonNode node, BiConsumer<JsonNode, JsonNode> visit) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode grade = node.get("grade");
            if (grade != null && grade.isObject()) {
                visit.accept(node, grade);
            }
            node.properties().forEach(e -> {
                if (!"grade".equals(e.getKey())) {
                    collect(e.getValue(), visit);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, visit));
        }
    }

    /** Phần cảnh dùng: {@code game.rounds} (mảng) hoặc {@code game.config.mode}; null = mọi phần. */
    private static Set<String> activeParts(JsonNode game) {
        JsonNode rounds = game.path("rounds");
        if (rounds.isArray()) {
            Set<String> s = new HashSet<>();
            rounds.forEach(r -> s.add(r.asText()));
            return s;
        }
        JsonNode mode = game.path("config").path("mode");
        return mode.isTextual() ? Set.of(mode.asText()) : null;
    }

    /** Khái niệm mặc định theo cảnh: đúng 1 mã thì lấy, không thì null (cùng luật chấm cũ). */
    static String singleConcept(JsonNode scene) {
        JsonNode units = scene.path("knowledgeUnits");
        return units.isArray() && units.size() == 1 ? units.get(0).asText() : null;
    }

    private static void inputsOf(JsonNode rule, String input, Set<String> out) {
        if (rule == null || !rule.isObject()) {
            return;
        }
        for (String group : List.of("all", "any")) {
            if (rule.has(group)) {
                rule.get(group).forEach(r -> inputsOf(r, input, out));
                return;
            }
        }
        out.add(text(rule.get("input"), input));
    }

    /** Giá trị các ô câu đọc: một ô → chữ (chuỗi) hoặc JSON; nhiều ô → object; không gửi gì → null. */
    private static String chosenOf(Set<String> used, Map<String, JsonNode> inputs) {
        if (used.size() == 1) {
            JsonNode v = inputs.get(used.iterator().next());
            return v == null || v.isNull() ? null : v.isTextual() ? v.asText() : v.toString();
        }
        Map<String, JsonNode> picked = new LinkedHashMap<>();
        used.forEach(k -> {
            if (inputs.get(k) != null) {
                picked.put(k, inputs.get(k));
            }
        });
        if (picked.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        picked.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(TextNode.valueOf(k)).append(':').append(v);
        });
        return sb.append('}').toString();
    }

    private static JsonNode at(JsonNode root, String path) {
        JsonNode n = root;
        for (String part : path.split("\\.")) {
            if (!part.isEmpty()) {
                n = n.path(part);
            }
        }
        return n;
    }

    private static JsonNode byId(JsonNode list, String id) {
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode n : list) {
            if (id.equals(n.path("id").asText(null))) {
                return n;
            }
        }
        return null;
    }

    private static Set<String> textSet(JsonNode arr) {
        Set<String> s = new HashSet<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> s.add(x.asText()));
        }
        return s;
    }

    private static String text(JsonNode n, String fallback) {
        return n != null && n.isValueNode() && !n.isNull() && !n.asText().isBlank() ? n.asText() : fallback;
    }
}
