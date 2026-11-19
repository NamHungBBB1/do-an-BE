package com.doan.game.service.impl;

import com.doan.game.DTO.response.SlotReportResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Gói số liệu cho bài viết người lớn + bản viết theo luật (khung báo cáo AI mục 3, 4, 13.3 — Hưng chốt 19/11).
 *
 * Gói dựng theo WHITELIST: chỉ các record bên dưới, không bao giờ chép nguyên object của DB. Gói có khung (chương, mã lõi,
 * quyết định với chữ phương án của bản GỐC — không cá nhân hoá) và kết quả (đúng / tổng theo mã, câu sai, phương án đã chọn,
 * hậu trắc). KHÔNG có: tên, id / mã slot, nhóm, tài khoản, email, tuổi, giới tính, sở thích, giờ chơi, chữ bé tự gõ. Câu sai
 * mang nhãn câu + đáp án viết thành chữ (đều là chữ của nội dung); ô bé chọn chỉ đi kèm khi nó tra ra nhãn trong nội dung
 * (không gửi số bé tự nhập). Gói rời BE (AI tầng nào cũng vậy) chính là chuỗi {@link #canonicalJson}; sha-256 của chuỗi đó
 * là packageHash — cùng số liệu thì cùng hash, dùng lại bài cũ.
 *
 * 19/11 (đợt D.1): khái niệm mang tên tiếng Việt / mục tiêu / mức của khung v3 (KnowledgeUnits); mã khái niệm, mã cảnh, mã
 * phương án chỉ còn làm khoá để BE kiểm đầu ra — AI được dặn không viết mã vào câu văn, BE loại nếu lọt (RAW_CODE).
 * TODO (nội dung chưa có, khung mục 9.1): adultKeyPoints, situation / keyDecision / revision của quyết định, belowTargetAge.
 */
final class ReportPackage {

    static final String CHAPTER = "CHAPTER";
    static final String FINAL = "FINAL";

    /** Bỏ trường null để gói gọn và hash không đổi khi thêm trường tuỳ chọn về sau. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final List<String> STAT_KEYS = List.of("WEALTH", "SAVING", "HAPPINESS", "RISK", "GOAL");
    static final String STATS_NOTE = "SAVING, HAPPINESS, RISK, GOAL, WEALTH là chỉ số gameplay của FinTeen, không phải thang "
            + "đo năng lực tài chính; RISK cao = phơi nhiễm lớn";
    /** Ô bé tự nhập số — không gửi AI (số / chữ bé tự gõ nằm ngoài whitelist). */
    private static final Pattern TYPED_NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)*");
    static final Map<String, String> BANDS = new TreeMap<>(Map.of("NO_DATA", "dưới 3 câu", "PRACTICE", "< 50%",
            "FAMILIAR", "50–69%", "GOOD", "≥ 70%"));

    private ReportPackage() {
    }

    // ------------------------------------------------------------------ hình gói (whitelist)

    record Pkg(String kind, Frame frame, Result result) {}

    record Frame(List<ChapterFrame> chapters, List<ConceptFrame> concepts, List<DecisionFrame> decisions,
                 String statsNote, Map<String, String> bands) {}

    record ChapterFrame(int number, String title, String goal) {}

    /** name = tên tiếng Việt cho phụ huynh; TODO adultKeyPoints khi nội dung có. */
    record ConceptFrame(String id, String name, String objective, String level) {}

    record DecisionFrame(String scene, List<OptionFrame> options) {}

    record OptionFrame(String id, String label, Boolean deliberate) {}

    record Result(Integer chaptersDone, List<ConceptResult> concepts, List<String> noData, List<WrongItem> wrongItems,
                  List<DecisionResult> decisions, DecisionSummary decisionSummary, Count miniGames, Count postTest) {}

    record ConceptResult(String id, Count game, Count postTest, int correct, int total, String band) {}

    /** label = nhãn câu; chosen = ô bé chọn (chỉ khi là nhãn có trong nội dung); expected = đáp án viết thành chữ. */
    record WrongItem(String label, String chosen, String expected, String concept) {}

    record DecisionResult(String scene, String chosen, String label, Boolean deliberate,
                          Map<String, Integer> deltaStats) {}

    /** decisions = số lựa chọn; labelled = số có nhãn cân nhắc; deliberate = số có bước cân nhắc. */
    record DecisionSummary(int decisions, int labelled, int deliberate) {}

    record Count(int correct, int total) {}

    // ------------------------------------------------------------------ dựng gói

    /** Gói một chương (bé đã xong chương và hậu trắc đã nộp / không có). */
    static Pkg chapter(ReportServiceImpl.ChapterBlocks blocks, SlotReportResponse.ChapterRow row) {
        int ch = row.chapter();
        JsonNode json = blocks.chapterJson().get(ch);
        Set<String> core = coreConcepts(json);

        List<DecisionFrame> frames = new ArrayList<>();
        List<DecisionResult> decisions = new ArrayList<>();
        for (SlotReportResponse.ChoiceRow c : row.choices()) {
            JsonNode scene = json == null ? null : PlayServiceImpl.findScene(json.path("scenes"), c.sceneId());
            List<OptionFrame> options = new ArrayList<>();
            JsonNode chosen = null;
            if (scene != null && scene.path("options").isArray()) {
                for (JsonNode o : scene.path("options")) {
                    String id = o.path("id").asText(null);
                    options.add(new OptionFrame(id, label(o), ReportServiceImpl.deliberate(o)));
                    if (c.optionId().equals(id)) {
                        chosen = o;
                    }
                }
            }
            frames.add(new DecisionFrame(c.sceneId(), options));
            decisions.add(new DecisionResult(c.sceneId(), c.optionId(), c.label(), c.deliberate(), deltaStats(chosen)));
        }

        List<WrongItem> wrong = new ArrayList<>();
        for (SlotReportResponse.WrongItem w : row.miniGames().wrong()) {
            if (w.label() != null) {
                boolean typed = w.chosen() == null || TYPED_NUMBER.matcher(w.chosen().trim()).matches();
                wrong.add(new WrongItem(w.label(), typed ? null : w.chosenLabel(), w.expectedLabel(), w.concept()));
            }
        }
        Map<String, int[]> counts = blocks.chapterConcepts().getOrDefault(ch, Map.of());
        SlotReportResponse.PostTest pt = row.postTest();
        Count postTest = ReportServiceImpl.SUBMITTED.equals(pt.status()) ? new Count(pt.correct(), pt.total()) : null;

        Frame frame = new Frame(List.of(chapterFrame(ch, json)), conceptFrames(core), frames, STATS_NOTE, BANDS);
        Result result = new Result(null, conceptResults(counts, core), noData(counts, core), wrong, decisions,
                decisionSummary(row.choices()), new Count(row.miniGames().correct(), row.miniGames().total()), postTest);
        return new Pkg(CHAPTER, frame, result);
    }

    /** Gói tổng kết 8 chương: gộp mọi chương đã xong của lần chơi hiện tại; không kể từng quyết định. */
    static Pkg finalPackage(ReportServiceImpl.ChapterBlocks blocks) {
        Set<String> core = new TreeSet<>();
        List<ChapterFrame> chapters = new ArrayList<>();
        List<SlotReportResponse.ChoiceRow> choices = new ArrayList<>();
        int miniCorrect = 0;
        int miniTotal = 0;
        int postCorrect = 0;
        int postTotal = 0;
        boolean anyPost = false;
        for (SlotReportResponse.ChapterRow row : blocks.chapters()) {
            JsonNode json = blocks.chapterJson().get(row.chapter());
            core.addAll(coreConcepts(json));
            chapters.add(chapterFrame(row.chapter(), json));
            choices.addAll(row.choices());
            miniCorrect += row.miniGames().correct();
            miniTotal += row.miniGames().total();
            if (ReportServiceImpl.SUBMITTED.equals(row.postTest().status())) {
                anyPost = true;
                postCorrect += row.postTest().correct();
                postTotal += row.postTest().total();
            }
        }
        Map<String, int[]> counts = new TreeMap<>();
        for (SlotReportResponse.ConceptRow c : blocks.concepts()) {
            counts.put(c.concept(), new int[] {c.game().correct(), c.game().total(), c.postTest().correct(),
                    c.postTest().total()});
        }
        Frame frame = new Frame(chapters, conceptFrames(core), null, STATS_NOTE, BANDS);
        Result result = new Result(chapters.size(), conceptResults(counts, core), noData(counts, core), null, null,
                decisionSummary(choices), new Count(miniCorrect, miniTotal),
                anyPost ? new Count(postCorrect, postTotal) : null);
        return new Pkg(FINAL, frame, result);
    }

    private static ChapterFrame chapterFrame(int ch, JsonNode json) {
        if (json == null) {
            return new ChapterFrame(ch, null, null);
        }
        return new ChapterFrame(ch, json.path("title").asText(null),
                json.path("metadata").path("goalDescription").asText(null));
    }

    /** Mã lõi của chương (metadata.coreKnowledgeUnits) — báo cáo chỉ nói về mã lõi; chương không khai thì mọi mã. */
    private static Set<String> coreConcepts(JsonNode json) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode units = json == null ? null : json.path("metadata").path("coreKnowledgeUnits");
        if (units != null && units.isArray()) {
            units.forEach(u -> out.add(u.asText()));
        }
        return out;
    }

    private static List<ConceptFrame> conceptFrames(Set<String> core) {
        return core.stream().sorted().map(id -> {
            KnowledgeUnits.Unit u = KnowledgeUnits.get(id);
            return u == null ? new ConceptFrame(id, null, null, null)
                    : new ConceptFrame(id, u.nameVi(), u.objective(), u.level());
        }).toList();
    }

    private static List<ConceptResult> conceptResults(Map<String, int[]> counts, Set<String> core) {
        List<ConceptResult> out = new ArrayList<>();
        new TreeMap<>(counts).forEach((id, c) -> {
            if (core.isEmpty() || core.contains(id)) {
                out.add(new ConceptResult(id, new Count(c[0], c[1]), new Count(c[2], c[3]), c[0] + c[2], c[1] + c[3],
                        ReportServiceImpl.band(c[0] + c[2], c[1] + c[3])));
            }
        });
        return out;
    }

    private static List<String> noData(Map<String, int[]> counts, Set<String> core) {
        return core.stream().filter(id -> !counts.containsKey(id)).sorted().toList();
    }

    private static DecisionSummary decisionSummary(List<SlotReportResponse.ChoiceRow> choices) {
        int labelled = 0;
        int deliberate = 0;
        for (SlotReportResponse.ChoiceRow c : choices) {
            if (c.deliberate() != null) {
                labelled++;
                deliberate += c.deliberate() ? 1 : 0;
            }
        }
        return new DecisionSummary(choices.size(), labelled, deliberate);
    }

    private static String label(JsonNode option) {
        return option.path("text").asText(option.path("label").asText(null));
    }

    /** deltaStats của phương án trong JSON gốc, chỉ 5 khoá chỉ số (cùng luật Play); không có → null. */
    private static Map<String, Integer> deltaStats(JsonNode option) {
        JsonNode delta = option == null ? null : option.get("deltaStats");
        if (delta == null || !delta.isObject()) {
            return null;
        }
        Map<String, Integer> out = new TreeMap<>();
        for (String key : STAT_KEYS) {
            JsonNode v = delta.get(key);
            if (v != null && v.isNumber()) {
                out.put(key, v.asInt());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ JSON chuẩn + hash

    /** JSON chuẩn: khoá xếp theo bảng chữ cái ở mọi tầng, bỏ null — cùng gói luôn ra cùng chuỗi. */
    static String canonicalJson(Pkg pkg) {
        try {
            return MAPPER.writeValueAsString(sorted(MAPPER.valueToTree(pkg)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("khong ghi duoc goi bao cao", e);
        }
    }

    /** Đọc lại gói đã lưu (AiJob.input); hỏng → null. */
    static Pkg read(String json) {
        try {
            return json == null ? null : MAPPER.readerFor(Pkg.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            new TreeSet<>(iterable(node)).forEach(k -> out.set(k, sorted(node.get(k))));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(n -> out.add(sorted(n)));
            return out;
        }
        return node;
    }

    private static List<String> iterable(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ------------------------------------------------------------------ bản viết theo luật

    /**
     * Bản theo luật (khung mục 4 ý 4, BR-49): mẫu câu cố định, mọi con số lấy từ gói. Thứ tự: dòng quyết định, kết quả
     * mini-game / hậu trắc, khái niệm theo mức (làm tốt → đang làm quen → cần luyện thêm → chưa đủ dữ liệu), câu chưa đúng.
     * Khái niệm gọi bằng tên tiếng Việt, câu sai bằng nhãn — không bao giờ mã thô (19/11, đợt D.1). Không nói đúng sai về lựa
     * chọn truyện (BR-45). Không có dòng gợi ý: chưa mã nào có adultKeyPoints (khung 2.2).
     */
    static String ruleText(Pkg pkg) {
        Result r = pkg.result();
        List<String> lines = new ArrayList<>();
        if (FINAL.equals(pkg.kind())) {
            lines.add("Tổng kết " + r.chaptersDone() + " chương.");
        } else {
            ChapterFrame c = pkg.frame().chapters().get(0);
            lines.add("Chương " + c.number() + (c.title() == null ? "." : " — " + c.title() + "."));
        }
        DecisionSummary d = r.decisionSummary();
        if (d.labelled() > 0) {
            lines.add("Quyết định: bé chọn phương án có bước cân nhắc ở " + d.deliberate() + "/" + d.labelled()
                    + " lựa chọn có nhãn.");
        } else if (d.decisions() > 0) {
            lines.add("Quyết định: các lựa chọn của bé chưa được gắn nhãn cân nhắc.");
        }
        if (r.miniGames().total() > 0) {
            lines.add("Mini-game: bé làm đúng " + r.miniGames().correct() + "/" + r.miniGames().total() + " câu.");
        }
        if (r.postTest() != null) {
            lines.add("Bài sau chương: bé làm đúng " + r.postTest().correct() + "/" + r.postTest().total() + " câu.");
        }
        addBand(lines, r, "GOOD", "Làm tốt");
        addBand(lines, r, "FAMILIAR", "Đang làm quen");
        addBand(lines, r, "PRACTICE", "Cần luyện thêm");
        String few = noDataLine(r);
        if (few != null) {
            lines.add(few);
        }
        if (r.wrongItems() != null && !r.wrongItems().isEmpty()) {
            List<String> items = new ArrayList<>();
            for (WrongItem w : r.wrongItems()) {
                items.add("“" + w.label() + "”" + (w.chosen() != null ? " — bé chọn " + w.chosen()
                        + (w.expected() != null ? ", đúng là " + w.expected() : "")
                        : w.expected() != null ? " — đúng là " + w.expected() : ""));
            }
            int others = r.miniGames().total() - r.miniGames().correct() - items.size();
            lines.add("Câu chưa đúng: " + String.join("; ", items) + (others > 0 ? "; và " + others + " câu khác" : "")
                    + ".");
        }
        return String.join("\n", lines);
    }

    /**
     * "Chưa đủ bài để nhận xét: <tên>." — khái niệm NO_DATA (kèm x/y nếu có câu) và mã lõi chưa có câu nào; không có → null.
     * Bài AI cũng nối dòng này (BE viết, AI không được nói về các mã này — ReportAiOutput.compose).
     */
    static String noDataLine(Result r) {
        List<String> few = new ArrayList<>();
        for (ConceptResult c : r.concepts()) {
            if ("NO_DATA".equals(c.band())) {
                few.add(KnowledgeUnits.nameOf(c.id()) + " (" + c.correct() + "/" + c.total() + " câu)");
            }
        }
        r.noData().forEach(id -> few.add(KnowledgeUnits.nameOf(id)));
        return few.isEmpty() ? null : "Chưa đủ bài để nhận xét: " + String.join(", ", few) + ".";
    }

    private static void addBand(List<String> lines, Result r, String band, String title) {
        List<String> parts = new ArrayList<>();
        for (ConceptResult c : r.concepts()) {
            if (band.equals(c.band())) {
                parts.add(KnowledgeUnits.nameOf(c.id()) + " (đúng " + c.correct() + "/" + c.total() + " câu)");
            }
        }
        if (!parts.isEmpty()) {
            lines.add(title + ": " + String.join(", ", parts) + ".");
        }
    }
}
