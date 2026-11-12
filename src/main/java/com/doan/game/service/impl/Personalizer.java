package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cá nhân hoá nội dung theo từng trẻ (spec build/spec-ca-nhan-hoa-2026-11-11.md, Hưng chốt 11/11).
 * Một chương đóng băng + nhãn của trẻ → chương đã ghép. Hàm thuần, xác định: cùng đầu vào luôn ra cùng một chương,
 * nên lượt chơi tái hiện được mà không phải lưu từng bản.
 *
 * <p>Nội dung khai báo hai thứ, đều nằm trong JSON chương (đi qua nháp → duyệt → phát hành như cũ):
 * <ul>
 *   <li>{@code personalization.tokens}: {@code {"item": {"default": "chiếc tai nghe", "interest:SPORT": "đôi giày"}}} → mọi chỗ
 *       {@code {{item}}} trong chữ. Mỗi token chỉ theo MỘT chiều.</li>
 *   <li>{@code variants} trên object bất kỳ (cảnh, lựa chọn, thẻ mini-game): {@code {"age:8_10": {"text": "…"}}} → ghi đè nông các
 *       trường bề mặt (mọi trường chữ trừ {@link #STRUCTURAL}).</li>
 * </ul>
 * Luật cứng: biến thể chỉ đổi bề mặt; tiền, điểm, đáp án, cây cảnh giữ nguyên ở MỌI tổ hợp — {@link #check} chặn.
 */
@Component
public class Personalizer {

    /**
     * Trường CẤU TRÚC / CHẤM ĐIỂM — biến thể không được đổi, token không được nằm trong. Mọi trường CHỮ khác là "bề mặt" (chương thật có
     * hơn 100 loại trường chữ: text, dataText, facts, feedback, instruction… — danh sách cho phép sẽ luôn thiếu, demo 11/11 lộ
     * inspect.dataText). Danh sách này lấy từ các khoá PlayServiceImpl / MiniGameGrader / ContentSchema đọc để chấm và đi đường;
     * thêm luật chấm mới đọc khoá chữ mới thì phải thêm vào đây. Số, boolean không bao giờ là bề mặt.
     */
    static final Set<String> STRUCTURAL = Set.of("id", "type", "kind", "mechanic", "code", "chapterId", "version", "schemaVersion",
            "releaseVersion", "nextSceneId", "targetSceneId", "ifTrueSceneId", "ifFalseSceneId", "onPassSceneId", "onFailSceneId",
            "speakerId", "backgroundId", "characterId", "gameId", "itemId", "choiceGroup", "category", "value", "values",
            "expectedValue", "correctId", "answer", "eq", "in", "all", "any", "op", "var", "expression", "condition", "requires",
            "when", "field", "sumOf", "rule", "grade", "concept", "knowledgeUnits", "position", "mode", "part", "from", "key",
            "check", "options", "deltaStats", "stats", "initialStats", "lockDecisions", "deferLedger", "legacyIds", "voiceUrl",
            "variants", "personalization", "flag");

    static boolean isSurface(String key) {
        return key != null && !STRUCTURAL.contains(key);
    }

    static final String BASE_KEY = "base";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*");
    private static final String RESERVED_TOKEN = "ASSET_BASE";
    private static final String RESERVED_TOKEN_FULL = "{{ASSET_BASE}}";   // ContentServiceImpl.ASSET_PLACEHOLDER — thay lúc trả, không phải token
    private static final int MAX_COMBOS = 500;
    private static final int MAX_PROBLEMS = 30;

    /** chiều (chữ thường) → các giá trị hợp lệ, theo thứ tự trong catalog. */
    private final Map<String, List<String>> catalog;

    public Personalizer(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("personalization/catalog.json").getInputStream()) {
            Map<String, List<String>> c = new LinkedHashMap<>();
            objectMapper.readTree(in).path("dimensions").fields().forEachRemaining(e -> {
                List<String> vals = new ArrayList<>();
                e.getValue().forEach(v -> vals.add(v.asText()));
                c.put(e.getKey(), List.copyOf(vals));
            });
            this.catalog = Collections.unmodifiableMap(c);
        } catch (IOException e) {
            throw new IllegalStateException("không đọc được personalization/catalog.json", e);
        }
    }

    public Map<String, List<String>> catalog() {
        return catalog;
    }

    // ------------------------------------------------------------------ nhãn cuối của trẻ

    /**
     * Nhãn cuối từ câu trả lời theo nguồn ("PARENT", "CHILD", "GAME" → chiều → giá trị). Luật Hưng 11/11 (Q6):
     * phụ huynh và trẻ cùng trả lời mà KHÁC nhau → coi như chưa biết; chưa biết thì lấy game chọn phụ; vẫn không có → không có nhãn
     * (chơi bản gốc ở chiều đó). Game chọn phụ không bao giờ đè giá trị phụ huynh + trẻ đã thống nhất. Giá trị lạ (không có trong
     * catalog) coi như không trả lời.
     */
    public Map<String, String> resolve(Map<String, Map<String, String>> answersBySource) {
        Map<String, String> out = new TreeMap<>();
        for (String dim : catalog.keySet()) {
            String p = valid(dim, answersBySource, "PARENT");
            String c = valid(dim, answersBySource, "CHILD");
            String v = (p != null && c != null) ? (p.equals(c) ? p : null) : (p != null ? p : c);
            if (v == null) {
                v = valid(dim, answersBySource, "GAME");
            }
            if (v != null) {
                out.put(dim, v);
            }
        }
        return out;
    }

    private String valid(String dim, Map<String, Map<String, String>> answers, String source) {
        Map<String, String> m = answers == null ? null : answers.get(source);
        String v = m == null ? null : m.get(dim);
        return v != null && catalog.get(dim).contains(v) ? v : null;
    }

    // ------------------------------------------------------------------ ghép chương

    /** Chương có khai báo cá nhân hoá không (không có → mọi hàm trả nguyên bản, không tốn công ghép). */
    public boolean isPersonalized(JsonNode chapter) {
        return chapter.has("personalization") || !dimensionsUsed(chapter).isEmpty();
    }

    /** Các chiều chương này thật sự dùng (theo token + variants), sắp xếp. */
    public Set<String> dimensionsUsed(JsonNode chapter) {
        Set<String> dims = new TreeSet<>();
        chapter.path("personalization").path("tokens").forEach(t -> t.fieldNames().forEachRemaining(k -> addDim(dims, k)));
        walk(chapter, o -> {
            JsonNode vars = o.get("variants");
            if (vars != null && vars.isObject()) {
                vars.fieldNames().forEachRemaining(k -> addDim(dims, k));
            }
        });
        return dims;
    }

    private void addDim(Set<String> dims, String label) {
        int i = label.indexOf(':');
        if (i > 0 && catalog.containsKey(label.substring(0, i))) {
            dims.add(label.substring(0, i));
        }
    }

    /** Khoá bản ghép: chỉ các chiều chương dùng, vd "gender:FEMALE|interest:SPORT"; không chiều nào khớp → "base". */
    public String variantKey(JsonNode chapter, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String dim : dimensionsUsed(chapter)) {
            String v = labels == null ? null : labels.get(dim);
            if (v != null) {
                sb.append(sb.isEmpty() ? "" : "|").append(dim).append(':').append(v);
            }
        }
        return sb.isEmpty() ? BASE_KEY : sb.toString();
    }

    /** Chương đã ghép theo nhãn (nhãn rỗng / null = bản gốc). Không sửa {@code chapter}. Không đụng {@code {{ASSET_BASE}}}. */
    public JsonNode compose(JsonNode chapter, Map<String, String> labels) {
        Map<String, String> lb = labels == null ? Map.of() : labels;
        JsonNode root = chapter.deepCopy();
        Map<String, String> values = new HashMap<>();
        if (root instanceof ObjectNode ro) {
            JsonNode tokens = ro.path("personalization").path("tokens");
            tokens.fields().forEachRemaining(t -> values.put(t.getKey(), tokenValue(t.getValue(), lb)));
            ro.remove("personalization");
        }
        apply(root, lb, values);
        return root;
    }

    private static String tokenValue(JsonNode def, Map<String, String> labels) {
        Iterator<Map.Entry<String, JsonNode>> it = def.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (matches(e.getKey(), labels)) {
                return e.getValue().asText();
            }
        }
        return def.path("default").asText("");
    }

    private static boolean matches(String label, Map<String, String> labels) {
        int i = label.indexOf(':');
        return i > 0 && label.substring(i + 1).equals(labels.get(label.substring(0, i)));
    }

    private static void apply(JsonNode node, Map<String, String> labels, Map<String, String> values) {
        if (node instanceof ObjectNode o) {
            JsonNode vars = o.remove("variants");
            if (vars != null && vars.isObject()) {
                // Thứ tự khoá theo tên → xác định; luật "mỗi trường một chiều" ({@link #check}) nên không có hai biến thể cùng ghi một trường.
                for (String key : new TreeSet<>(iterable(vars.fieldNames()))) {
                    if (matches(key, labels)) {
                        vars.get(key).fields().forEachRemaining(f -> {
                            if (isSurface(f.getKey()) && f.getValue().isTextual()) {
                                o.set(f.getKey(), f.getValue().deepCopy());
                            }
                        });
                    }
                }
            }
            for (String k : iterable(o.fieldNames())) {
                JsonNode v = o.get(k);
                if (v.isTextual()) {
                    o.set(k, TextNode.valueOf(fill(v.asText(), values)));
                } else {
                    apply(v, labels, values);
                }
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                if (v.isTextual()) {
                    a.set(i, TextNode.valueOf(fill(v.asText(), values)));
                } else {
                    apply(v, labels, values);
                }
            }
        }
    }

    private static String fill(String s, Map<String, String> values) {
        if (s.indexOf("{{") < 0) {
            return s;
        }
        Matcher m = TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ mọi tổ hợp + kiểm

    /** Mọi tổ hợp nhãn chương dùng, kể cả "chưa biết" ở từng chiều (= bản gốc chiều đó). Phần tử đầu luôn là nhãn rỗng. */
    public List<Map<String, String>> combos(JsonNode chapter) {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of());
        for (String dim : dimensionsUsed(chapter)) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> c : out) {
                next.add(c);
                for (String v : catalog.get(dim)) {
                    Map<String, String> m = new TreeMap<>(c);
                    m.put(dim, v);
                    next.add(m);
                }
            }
            out = next;
        }
        return out;
    }

    /**
     * Kiểm phần cá nhân hoá của một chương. Rỗng = đạt. Ghép MỌI tổ hợp rồi so với bản gốc:
     * khai báo hợp lệ; không còn {@code {{x}}} chưa thay; phần không phải bề mặt giống hệt; số trong chữ giữ nguyên.
     */
    public List<String> check(JsonNode chapter) {
        Set<String> problems = new LinkedHashSet<>();
        checkDeclarations(chapter, problems);
        List<Map<String, String>> all = combos(chapter);
        if (all.size() > MAX_COMBOS) {
            problems.add("quá nhiều tổ hợp cá nhân hoá (" + all.size() + " > " + MAX_COMBOS + ") — bớt chiều trong chương");
            return List.copyOf(problems);
        }
        JsonNode base = compose(chapter, Map.of());
        JsonNode baseSkeleton = skeleton(base);
        Map<String, List<String>> baseNumbers = new TreeMap<>();
        surfaceTexts(base, "", (p, s) -> baseNumbers.put(p, numbers(s)));
        for (Map<String, String> labels : all) {
            if (problems.size() >= MAX_PROBLEMS) {
                break;
            }
            String key = labels.isEmpty() ? BASE_KEY : variantKey(chapter, labels);
            JsonNode c = labels.isEmpty() ? base : compose(chapter, labels);
            texts(c, "", (p, s) -> {
                Matcher m = TOKEN.matcher(s);
                while (m.find()) {
                    if (!RESERVED_TOKEN.equals(m.group(1))) {
                        problems.add(p + ": còn {{" + m.group(1) + "}} chưa có giá trị (" + key + ")");
                    }
                }
            });
            if (!skeleton(c).equals(baseSkeleton)) {
                problems.add(key + ": biến thể làm đổi phần không phải chữ / ảnh (id, đường đi, tiền, điểm, đáp án)");
            }
            surfaceTexts(c, "", (p, s) -> {
                List<String> want = baseNumbers.getOrDefault(p, List.of());   // biến thể THÊM chữ mới cũng không được thêm số
                if (!want.equals(numbers(s))) {
                    problems.add(p + ": số trong chữ khác bản gốc " + want + " → " + numbers(s) + " (" + key + ")");
                }
            });
        }
        return problems.stream().limit(MAX_PROBLEMS).toList();
    }

    private void checkDeclarations(JsonNode chapter, Set<String> problems) {
        // BE chấm trên JSON THÔ (PlayServiceImpl, MiniGameGrader — không ghép), nên token chỉ được nằm ở trường bề mặt. Token ở
        // id / nextSceneId / options của quiz / grade… thì mọi tổ hợp ghép ra giống nhau (check dưới không thấy) mà chấm vẫn sai.
        texts(chapter, "", (p, s) -> {
            if (p.startsWith("personalization.") || s.indexOf("{{") < 0) {
                return;
            }
            String last = lastField(p);
            Matcher m = TOKEN.matcher(s);
            while (m.find()) {
                if (!RESERVED_TOKEN.equals(m.group(1)) && !isSurface(last)) {
                    problems.add(p + ": {{" + m.group(1) + "}} không được nằm ở trường cấu trúc / chấm điểm \"" + last + "\"");
                }
            }
        });
        chapter.path("personalization").path("tokens").fields().forEachRemaining(t -> {
            String name = t.getKey();
            String at = "personalization.tokens." + name;
            if (RESERVED_TOKEN.equals(name) || !TOKEN.matcher("{{" + name + "}}").matches()) {
                problems.add(at + ": tên token không hợp lệ");
            }
            if (!t.getValue().isObject() || !t.getValue().path("default").isTextual()) {
                problems.add(at + ": thiếu \"default\" (chữ)");
                return;
            }
            Set<String> dims = new TreeSet<>();
            t.getValue().fields().forEachRemaining(e -> {
                if (!"default".equals(e.getKey())) {
                    checkLabel(e.getKey(), at, problems);
                    dims.add(e.getKey().split(":", 2)[0]);
                    if (!e.getValue().isTextual()) {
                        problems.add(at + "." + e.getKey() + ": giá trị phải là chữ");
                    }
                }
            });
            if (dims.size() > 1) {
                problems.add(at + ": một token chỉ được theo một chiều, đang có " + dims);
            }
        });
        walkPath(chapter, "", (path, o) -> {
            JsonNode vars = o.get("variants");
            if (vars == null) {
                return;
            }
            if (!vars.isObject()) {
                problems.add(path + ".variants: phải là object");
                return;
            }
            Map<String, String> fieldDim = new HashMap<>();
            vars.fields().forEachRemaining(e -> {
                checkLabel(e.getKey(), path + ".variants", problems);
                String dim = e.getKey().split(":", 2)[0];
                e.getValue().fields().forEachRemaining(f -> {
                    if (!isSurface(f.getKey())) {
                        problems.add(path + ".variants." + e.getKey() + "." + f.getKey()
                                + ": trường cấu trúc / chấm điểm, biến thể không được đổi");
                    } else if (!f.getValue().isTextual()) {
                        problems.add(path + ".variants." + e.getKey() + "." + f.getKey() + ": phải là chữ");
                    }
                    String prev = fieldDim.putIfAbsent(f.getKey(), dim);
                    if (prev != null && !prev.equals(dim)) {
                        problems.add(path + "." + f.getKey() + ": bị hai chiều cùng đổi (" + prev + ", " + dim + ") — mỗi trường một chiều");
                    }
                });
            });
        });
    }

    private void checkLabel(String label, String at, Set<String> problems) {
        String[] p = label.split(":", 2);
        if (p.length != 2 || !catalog.containsKey(p[0]) || !catalog.get(p[0]).contains(p[1])) {
            problems.add(at + ": nhãn \"" + label + "\" không có trong danh mục " + catalog);
        }
    }

    /** Bản sao với mọi chữ ở trường bề mặt thay bằng "" (kể cả phần tử mảng chữ như facts[]): phần còn lại phải giống hệt giữa mọi tổ hợp. */
    private static JsonNode skeleton(JsonNode n) {
        return blank(n.deepCopy(), null);
    }

    private static JsonNode blank(JsonNode n, String key) {
        if (n.isTextual()) {
            return isSurface(key) ? TextNode.valueOf("") : n;
        }
        if (n instanceof ObjectNode o) {
            for (String k : iterable(o.fieldNames())) {
                if (isSurface(k) && o.get(k).isTextual()) {
                    o.remove(k);   // biến thể được THÊM trường chữ (vd speaker) → bỏ hẳn khoá, không chỉ xoá chữ
                } else {
                    o.set(k, blank(o.get(k), k));
                }
            }
        } else if (n instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                a.set(i, blank(a.get(i), key));
            }
        }
        return n;
    }

    private static List<String> numbers(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(s.replace(RESERVED_TOKEN, ""));
        while (m.find()) {
            out.add(m.group().replaceAll("[.,]+$", ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ duyệt cây

    private interface TextVisitor {
        void visit(String path, String text);
    }

    private interface ObjectVisitor {
        void visit(String path, ObjectNode o);
    }

    private static void walk(JsonNode n, java.util.function.Consumer<ObjectNode> f) {
        walkPath(n, "", (p, o) -> f.accept(o));
    }

    private static void walkPath(JsonNode n, String path, ObjectVisitor f) {
        if (n instanceof ObjectNode o) {
            f.visit(path, o);
            for (String k : iterable(o.fieldNames())) {
                walkPath(o.get(k), path.isEmpty() ? k : path + "." + k, f);
            }
        } else if (n instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                walkPath(a.get(i), path + "[" + i + "]", f);
            }
        }
    }

    /** Mọi chuỗi trong cây kèm đường dẫn. */
    private static void texts(JsonNode n, String path, TextVisitor f) {
        if (n.isTextual()) {
            f.visit(path, n.asText());
        } else if (n.isObject()) {
            n.fields().forEachRemaining(e -> texts(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), f));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                texts(n.get(i), path + "[" + i + "]", f);
            }
        }
    }

    /** Chuỗi ở trường bề mặt (chữ người đọc), kèm đường dẫn. */
    private static void surfaceTexts(JsonNode n, String path, TextVisitor f) {
        texts(n, path, (p, s) -> {
            String last = lastField(p);
            // Ảnh / giọng là đường dẫn (hash có chữ số) — không kiểm số; còn lại là chữ người đọc.
            if (isSurface(last) && !s.startsWith("http") && !s.startsWith(RESERVED_TOKEN_FULL)
                    && !Set.of("image", "src", "background", "voice").contains(last)) {
                f.visit(p, s);
            }
        });
    }

    /** "scenes[2].options[0].text" → "text"; "scenes[5].options[1]" → "options". */
    private static String lastField(String path) {
        String p = path.replaceAll("\\[\\d+]$", "");
        return p.substring(p.lastIndexOf('.') + 1);
    }

    private static <T> List<T> iterable(Iterator<T> it) {
        List<T> l = new ArrayList<>();
        it.forEachRemaining(l::add);
        return l;
    }
}
