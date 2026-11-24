package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lớp 3 của gói báo cáo người lớn (khung báo cáo AI mục 4): yêu cầu viết gửi AI, schema đầu ra, BE kiểm đầu ra trước khi lưu
 * (BR-51) và ghép bài hiện cho người lớn. Hàm thuần — test gọi thẳng.
 *
 * Đầu ra AI: {overview, decisions[{scene,text}], doingWell[{concept,text}], needsPractice[{concept,text}],
 * tips[{concept,keyPoint,text}]}. Bị loại khi: sai hình; câu văn có mã thô (RAW_CODE — D01.02, CH01_SC02_CHOICE, ITEM_…);
 * mã doingWell không ở mức GOOD; mã needsPractice không ở mức PRACTICE / FAMILIAR; cảnh không có ở đâu trong gói; tips không
 * dựa vào adultKeyPoints; con số (kể cả số viết bằng chữ) không có trong gói; quá số chữ; có từ cấm.
 *
 * 19/11 (đợt D.1, sau lần chạy thật trên production): khái niệm chưa đủ dữ liệu (NO_DATA / noData) KHÔNG còn nằm trong mục
 * nào của AI — BE tự nối dòng "Chưa đủ bài để nhận xét: <tên>" khi ghép bài ({@link #compose}). Prompt nhắm ~150 chữ (giới
 * hạn kiểm vẫn 180) và cấm viết mã; cảnh được chấp nhận nếu xuất hiện bất kỳ đâu trong gói (khung quyết định, lựa chọn).
 */
final class ReportAiOutput {

    static final String PROMPT_VERSION = "report-v3";
    static final int MAX_WORDS_CHAPTER = 180;
    static final int MAX_WORDS_SUMMARY = 250;
    /** Độ dài nhắm trong prompt — thấp hơn giới hạn kiểm để AI có chỗ hụt (19/11: 2/4 bài bị loại vì 188–214 chữ). */
    static final int TARGET_WORDS_CHAPTER = 150;
    static final int TARGET_WORDS_SUMMARY = 200;
    /** Mã thô không được lọt vào câu văn cho phụ huynh (RAW_CODE). */
    static final Pattern RAW_CODE = Pattern.compile("(?<![\\p{L}\\p{N}])(?:CH\\d{2}_|D\\d{2}\\.\\d{2}|ITEM_)|_CH\\d{2}"
            + "|(?<![\\p{L}\\p{N}])[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+(?![\\p{L}\\p{N}])");

    /**
     * Từ cấm (khung mục 4 ý 3, khung v3 mục 7–8): dán nhãn tính cách / hoàn cảnh của bé hay gia đình, khuyên sản phẩm tài
     * chính thật. So theo nguyên từ (không khớp giữa chữ), không phân biệt hoa thường.
     */
    static final List<String> FORBIDDEN = List.of(
            "kém", "lười", "lười biếng", "dốt", "ngu", "ngốc", "chậm hiểu", "hư hỏng", "bất cẩn", "cẩu thả",
            "vô trách nhiệm", "tiêu hoang", "hoang phí", "phung phí", "keo kiệt", "bủn xỉn", "tham lam",
            "ham chơi", "nghèo", "nhà nghèo", "khó khăn về tiền",
            "cổ phiếu", "chứng khoán", "trái phiếu", "tiền ảo", "tiền điện tử", "bitcoin", "crypto", "thẻ tín dụng",
            "vay tiêu dùng", "vay nóng", "quỹ đầu tư");
    /** Lựa chọn trong truyện không có đúng sai (BR-45) — chỉ cấm trong mục decisions và overview. */
    static final List<String> FORBIDDEN_FOR_CHOICES = List.of("sai", "sai lầm", "nhầm", "dại", "dở", "tệ", "đúng đắn");
    /** Số lớn viết bằng chữ: số tiền phải chép chữ số từ gói. */
    static final List<String> NUMBER_WORDS = List.of("mười", "mươi", "trăm", "nghìn", "ngàn", "triệu");
    /** Chữ số đếm đứng trước danh từ đếm được ("năm câu") → đổi ra số rồi kiểm có trong gói. */
    private static final Map<String, String> DIGIT_WORDS = Map.of("một", "1", "hai", "2", "ba", "3", "bốn", "4", "tư",
            "4", "năm", "5", "sáu", "6", "bảy", "7", "tám", "8", "chín", "9");
    private static final Pattern COUNTED = Pattern.compile(
            "(?<!\\p{L})(một|hai|ba|bốn|tư|năm|sáu|bảy|tám|chín)\\s+(câu|lần|chương|lựa chọn|phương án|bài|mã|quyết định|"
                    + "khái niệm|mini-game|trò chơi)(?!\\p{L})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+");
    private static final Set<String> LOW_BANDS = Set.of("PRACTICE", "FAMILIAR");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportAiOutput() {
    }

    // ------------------------------------------------------------------ gửi đi

    static int maxWords(JsonNode pkg) {
        return shortKind(pkg) ? MAX_WORDS_CHAPTER : MAX_WORDS_SUMMARY;
    }

    static int targetWords(JsonNode pkg) {
        return shortKind(pkg) ? TARGET_WORDS_CHAPTER : TARGET_WORDS_SUMMARY;
    }

    /** Bài một chương (của em hoặc của lớp) dùng giới hạn ngắn; tổng kết 8 chương dùng giới hạn dài. */
    private static boolean shortKind(JsonNode pkg) {
        String kind = pkg.path("kind").asText();
        return ReportPackage.CHAPTER.equals(kind) || ReportPackage.CLASS.equals(kind);
    }

    /** Số chữ viết bằng lời trong prompt (chỉ các mốc độ dài đang dùng). */
    private static String inWords(int n) {
        return switch (n) {
            case 150 -> "một trăm năm mươi";
            case 180 -> "một trăm tám mươi";
            case 200 -> "hai trăm";
            case 250 -> "hai trăm năm mươi";
            default -> String.valueOf(n);
        };
    }

    /** Lớp 3 (systemInstruction). Người đọc PARENT (gói CLASS: TEACHER); gói đi riêng ở tin người dùng. */
    static String systemPrompt(JsonNode pkg) {
        if (ReportPackage.CLASS.equals(pkg.path("kind").asText())) {
            return classSystemPrompt(pkg);
        }
        int target = targetWords(pkg);
        int max = maxWords(pkg);
        return """
                Bạn viết bản tóm tắt kết quả học tài chính của MỘT bé cho phụ huynh (người đọc: PARENT), bằng tiếng Việt, \
                giọng ấm áp, cụ thể, tôn trọng bé. Dữ liệu duy nhất được dùng là gói JSON ở tin nhắn người dùng; không suy \
                đoán điều gì ngoài gói.
                Trả về JSON đúng schema, 5 mục:
                - overview: 2–3 câu tổng quan.
                - decisions: mỗi phần tử {scene, text} cho một quyết định trong frame.decisions; trường scene chép nguyên \
                giá trị scene của gói (chỉ là khoá). Trong text, nhắc quyết định bằng chữ phương án bé chọn (trường label), \
                không nhắc mã. Chỉ nói bé "có" hoặc "chưa có" bước cân nhắc theo đúng trường deliberate; phần tử KHÔNG có trường \n                deliberate thì chỉ kể bé chọn gì, KHÔNG dùng chữ "cân nhắc". Lựa chọn trong truyện không có đúng sai.
                - doingWell: {concept, text}, CHỈ mã có band = GOOD trong result.concepts. Trong text gọi khái niệm bằng tên \
                (frame.concepts[].name).
                - needsPractice: {concept, text}, CHỈ mã có band = PRACTICE hoặc FAMILIAR. Có thể nhắc cụ thể câu bé chưa \
                làm đúng trong result.wrongItems (label = nội dung câu, chosen = bé chọn, expected = đáp án).
                - tips: {concept, keyPoint, text}, CHỈ dựa vào frame.concepts[].adultKeyPoints (keyPoint là chỉ số ý, đếm \
                từ 0). Gói không có adultKeyPoints thì tips là mảng rỗng.
                Khái niệm có band NO_DATA hoặc nằm trong result.noData: KHÔNG đưa vào mục nào, KHÔNG nhắc tới — hệ thống \
                tự thêm một dòng "chưa đủ bài để nhận xét".
                Luật bắt buộc:
                1. Mọi con số phải có nguyên văn trong gói, viết bằng chữ số đúng như trong gói (ví dụ 5/6, 300.000đ). \
                Không tự tính phần trăm hay con số mới. Không viết số bằng chữ.
                2. Độ dài: nhắm khoảng %d chữ (%s chữ), TUYỆT ĐỐI không quá %d chữ (%s chữ), tính tổng mọi mục, đếm \
                theo khoảng trắng. Mỗi phần tử chỉ 1–2 câu ngắn; bỏ bớt chi tiết thay vì viết dài.
                3. Không dán nhãn tính cách hay hoàn cảnh của bé hoặc gia đình (ví dụ lười, kém, tiêu hoang, nghèo).
                4. Không gọi lựa chọn trong truyện là sai hay đúng.
                5. Không khuyên dùng sản phẩm tài chính thật (cổ phiếu, tiền ảo, thẻ tín dụng, quỹ đầu tư, vay).
                6. %s
                7. Trong câu văn TUYỆT ĐỐI không viết mã: mã khái niệm (dạng D01.02), mã cảnh, mã phương án, mã câu hay bất \
                kỳ chữ IN_HOA nối bằng gạch dưới nào. Mã chỉ được nằm trong trường concept / scene. Gọi khái niệm bằng tên, \
                gọi quyết định bằng tình huống hoặc chữ phương án.
                """.formatted(target, inWords(target), max, inWords(max), ReportPackage.STATS_NOTE);
    }

    /**
     * Nhận xét cả lớp cho giáo viên (25/11). Gói chỉ có số gộp, AI không biết em nào là em nào; không được nhắc riêng một
     * em. Cùng schema 5 mục và cùng luật kiểm với bài của em (ReportAiOutput.check): decisions dẫn cảnh trong
     * frame.decisions, doingWell / needsPractice theo band của LỚP, tips chỉ từ adultKeyPoints.
     */
    static String classSystemPrompt(JsonNode pkg) {
        int target = targetWords(pkg);
        int max = maxWords(pkg);
        return """
                Bạn viết nhận xét kết quả học tài chính của MỘT LỚP sau một chương cho giáo viên (người đọc: TEACHER), \
                bằng tiếng Việt, giọng chuyên nghiệp, ngắn gọn, hữu ích cho việc dạy. Dữ liệu duy nhất được dùng là gói JSON ở \
                tin nhắn người dùng: số đã cộng dồn của cả lớp (result.submitted em đã nộp bài sau chương trên result.active \
                em đang hoạt động); không suy đoán điều gì ngoài gói.
                Trả về JSON đúng schema, 5 mục:
                - overview: 2–3 câu tổng quan về lớp.
                - decisions: mỗi phần tử {scene, text} cho một cảnh trong result.classDecisions; trường scene chép nguyên giá \
                trị scene của gói. Trong text, nói lớp nghiêng về phương án nào (gọi phương án bằng chữ label trong \
                frame.decisions, kèm số em trong counts). Lựa chọn trong truyện không có đúng sai; không dùng chữ "cân nhắc".
                - doingWell: {concept, text}, CHỈ mã có band = GOOD trong result.concepts (mức của cả lớp). Gọi khái niệm bằng \
                tên (frame.concepts[].name).
                - needsPractice: {concept, text}, CHỈ mã có band = PRACTICE hoặc FAMILIAR. Gợi ý ngắn nên ôn phần nào.
                - tips: {concept, keyPoint, text}, CHỈ dựa vào frame.concepts[].adultKeyPoints (keyPoint là chỉ số ý, đếm \
                từ 0), viết thành gợi ý cho tiết học. Gói không có adultKeyPoints thì tips là mảng rỗng.
                Khái niệm có band NO_DATA hoặc nằm trong result.noData: KHÔNG đưa vào mục nào, KHÔNG nhắc tới.
                Luật bắt buộc:
                1. Mọi con số phải có nguyên văn trong gói, viết bằng chữ số đúng như trong gói. Không tự tính phần trăm hay \
                con số mới. Không viết số bằng chữ.
                2. Độ dài: nhắm khoảng %d chữ (%s chữ), TUYỆT ĐỐI không quá %d chữ (%s chữ), tính tổng mọi mục, đếm \
                theo khoảng trắng. Mỗi phần tử chỉ 1–2 câu ngắn.
                3. Không nhắc riêng một em nào, không đoán em nào làm gì; không dán nhãn tính cách hay hoàn cảnh.
                4. Không gọi lựa chọn trong truyện là sai hay đúng; ở mục overview và decisions không dùng các chữ \
                "sai", "nhầm", "đúng đắn".
                5. Không khuyên dùng sản phẩm tài chính thật (cổ phiếu, tiền ảo, thẻ tín dụng, quỹ đầu tư, vay).
                6. Trong câu văn TUYỆT ĐỐI không viết mã: mã khái niệm (dạng D01.02), mã cảnh, mã phương án hay bất kỳ chữ \
                IN_HOA nối bằng gạch dưới nào. Mã chỉ được nằm trong trường concept / scene.
                """.formatted(target, inWords(target), max, inWords(max));
    }

    /**
     * Gom lô (25/11): tin người dùng là {"packages": [{"id", "package"}]}; mỗi gói là một bài riêng, viết theo đúng lời dặn
     * của bài lẻ (lấy theo gói đầu — mọi gói trong lô cùng ngăn nên cùng lời dặn). Đo 24/11 (build/exp-gom-lo-2026-11-24):
     * lô 10 đạt 18/20 bài, ≈ 2,2k token / bài; lô 20 bắt đầu lẫn dữ liệu nên giữ tối đa 10.
     */
    static String batchSystemPrompt(JsonNode first) {
        return """
                Tin nhắn người dùng là JSON {"packages": [{"id": ..., "package": {...}}]}. MỖI GÓI LÀ MỘT BÀI RIÊNG, hoàn \
                toàn độc lập: bài của gói nào chỉ dùng dữ liệu trong chính gói đó; TUYỆT ĐỐI không lấy con số, phương án, \
                khái niệm hay câu chữ từ gói khác. Không so sánh các gói với nhau.
                Trả về {"items": [...]}, đúng MỘT phần tử cho MỖI gói, cùng thứ tự, chép nguyên id. Mỗi phần tử có id và 5 \
                mục như mô tả bên dưới; mọi luật bên dưới (kể cả độ dài) áp cho TỪNG bài riêng, không phải tổng cả danh \
                sách. Trong các luật, "gói" nghĩa là gói của chính bài đó.

                Mô tả một bài:
                """ + systemPrompt(first);
    }

    /** Schema lô: items[] = schema bài lẻ + id. */
    static JsonNode batchResponseSchema() {
        com.fasterxml.jackson.databind.node.ObjectNode item = responseSchema().deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode().add("id");
        item.path("required").forEach(required::add);
        item.set("required", required);
        com.fasterxml.jackson.databind.node.ObjectNode props = JSON.createObjectNode();
        props.set("id", JSON.createObjectNode().put("type", "STRING"));
        item.path("properties").fields().forEachRemaining(e -> props.set(e.getKey(), e.getValue()));
        item.set("properties", props);
        com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode().put("type", "OBJECT");
        out.putArray("required").add("items");
        out.putObject("properties").putObject("items").put("type", "ARRAY").set("items", item);
        return out;
    }

    /** Schema đầu ra cho Gemini (OpenAPI subset). */
    static JsonNode responseSchema() {
        return read("""
                {"type":"OBJECT","required":["overview","decisions","doingWell","needsPractice","tips"],"properties":{
                 "overview":{"type":"STRING"},
                 "decisions":{"type":"ARRAY","items":{"type":"OBJECT","required":["scene","text"],
                   "properties":{"scene":{"type":"STRING"},"text":{"type":"STRING"}}}},
                 "doingWell":{"type":"ARRAY","items":{"type":"OBJECT","required":["concept","text"],
                   "properties":{"concept":{"type":"STRING"},"text":{"type":"STRING"}}}},
                 "needsPractice":{"type":"ARRAY","items":{"type":"OBJECT","required":["concept","text"],
                   "properties":{"concept":{"type":"STRING"},"text":{"type":"STRING"}}}},
                 "tips":{"type":"ARRAY","items":{"type":"OBJECT","required":["concept","keyPoint","text"],
                   "properties":{"concept":{"type":"STRING"},"keyPoint":{"type":"INTEGER"},"text":{"type":"STRING"}}}}}}
                """);
    }

    // ------------------------------------------------------------------ kiểm đầu ra

    /** Luật bị vi phạm (mã + chi tiết ngắn) hoặc null khi đạt. pkgText = gói nguyên văn (AiJob.input). */
    static String check(String pkgText, String raw) {
        JsonNode pkg = read(pkgText);
        JsonNode out = read(raw);
        if (out == null || !out.isObject() || !out.path("overview").isTextual() || out.path("overview").asText().isBlank()) {
            return "SHAPE: không phải JSON có overview";
        }
        for (String sec : List.of("decisions", "doingWell", "needsPractice", "tips")) {
            JsonNode a = out.path(sec);
            if (!a.isMissingNode() && !a.isNull() && !a.isArray()) {
                return "SHAPE: " + sec + " không phải mảng";
            }
            for (JsonNode it : a) {
                if (!it.path("text").isTextual()) {
                    return "SHAPE: " + sec + " thiếu text";
                }
            }
        }
        if (pkg == null) {
            return "SHAPE: gói hỏng";
        }
        String all = nfc(String.join("\n", texts(out, List.of("overview", "decisions", "doingWell", "needsPractice",
                "tips"))));
        Matcher rawCode = RAW_CODE.matcher(all);
        if (rawCode.find()) {
            return "RAW_CODE: " + rawCode.group();
        }
        Map<String, String> bands = new java.util.HashMap<>();
        pkg.path("result").path("concepts").forEach(c -> bands.put(c.path("id").asText(), c.path("band").asText()));
        for (JsonNode it : out.path("doingWell")) {
            String code = it.path("concept").asText();
            if (!"GOOD".equals(bands.get(code))) {
                return "DOING_WELL_BAND: " + code;
            }
        }
        for (JsonNode it : out.path("needsPractice")) {
            String code = it.path("concept").asText();
            if (!LOW_BANDS.contains(bands.getOrDefault(code, ""))) {
                return "NEEDS_PRACTICE_BAND: " + code;
            }
        }
        // Cảnh hợp lệ = mọi giá trị scene / chosen / id có trong gói (khung quyết định + lựa chọn của bé). 19/11: AI dẫn một
        // cảnh có trong lựa chọn của bé mà khung không liệt kê → UNKNOWN_SCENE oan.
        Set<String> scenes = new HashSet<>();
        for (String key : List.of("scene", "chosen", "id")) {
            pkg.findValues(key).forEach(v -> {
                if (v.isTextual()) {
                    scenes.add(v.asText());
                }
            });
        }
        for (JsonNode it : out.path("decisions")) {
            if (!scenes.contains(it.path("scene").asText())) {
                return "UNKNOWN_SCENE: " + it.path("scene").asText();
            }
        }
        // BR-45: nhận xét "cân nhắc" phải khớp nhãn của lựa chọn bé. Lỗi thật 19/11 (CH05): lựa chọn KHÔNG có nhãn (gói bỏ
        // trường deliberate) mà AI vẫn viết "chưa có bước cân nhắc" → phụ huynh hiểu sai về bé.
        Map<String, JsonNode> chosen = new java.util.HashMap<>();
        pkg.path("result").path("decisions").forEach(d -> chosen.put(d.path("scene").asText(), d));
        for (JsonNode it : out.path("decisions")) {
            JsonNode d = chosen.get(it.path("scene").asText());
            if (d == null) {
                continue;
            }
            String t = nfc(it.path("text").asText()).toLowerCase(Locale.ROOT);
            boolean negative = t.contains("chưa có bước cân nhắc") || t.contains("chưa cân nhắc")
                    || t.contains("không cân nhắc") || t.contains("không có bước cân nhắc");
            JsonNode label = d.path("deliberate");
            if (t.contains("cân nhắc") && (!label.isBoolean() || label.asBoolean() == negative)) {
                return "DELIBERATE_MISMATCH: " + it.path("scene").asText();
            }
        }
        Map<String, Integer> keyPoints = new java.util.HashMap<>();
        pkg.path("frame").path("concepts").forEach(c -> keyPoints.put(c.path("id").asText(),
                c.path("adultKeyPoints").size()));
        for (JsonNode it : out.path("tips")) {
            int idx = it.path("keyPoint").asInt(-1);
            if (idx < 0 || idx >= keyPoints.getOrDefault(it.path("concept").asText(), 0)) {
                return "TIPS_NOT_FROM_KEY_POINTS: " + it.path("concept").asText();
            }
        }

        Set<String> allowed = numbers(nfc(pkgText));
        Matcher m = NUMBER.matcher(all);
        while (m.find()) {
            if (!allowed.contains(norm(m.group()))) {
                return "NUMBER_NOT_IN_PACKAGE: " + m.group();
            }
        }
        // ponytail: chỉ bắt chữ số đơn trước danh từ đếm ("năm câu") và từ chỉ số lớn; "mười hai câu" bắt được "hai câu".
        Matcher w = COUNTED.matcher(all);
        while (w.find()) {
            if (!allowed.contains(DIGIT_WORDS.get(w.group(1).toLowerCase(Locale.ROOT)))) {
                return "NUMBER_NOT_IN_PACKAGE: " + w.group();
            }
        }
        String hit = firstWord(all, NUMBER_WORDS);
        if (hit != null) {
            return "NUMBER_WORD: " + hit;
        }
        int words = all.isBlank() ? 0 : all.trim().split("\\s+").length;
        if (words > maxWords(pkg)) {
            return "TOO_LONG: " + words + " > " + maxWords(pkg);
        }
        // Từ cấm có ngay trong tên / mục tiêu khái niệm của gói (ví dụ "thẻ tín dụng" ở chương tín dụng) là nói về bài học,
        // không phải khuyên dùng → cho phép.
        StringBuilder topic = new StringBuilder();
        pkg.path("frame").path("concepts").forEach(c -> topic.append(c.path("name").asText("")).append('\n')
                .append(c.path("objective").asText("")).append('\n'));
        String topicText = nfc(topic.toString());
        hit = firstWord(all, FORBIDDEN.stream().filter(fw -> firstWord(topicText, List.of(fw)) == null).toList());
        if (hit != null) {
            return "FORBIDDEN_WORD: " + hit;
        }
        hit = firstWord(nfc(String.join("\n", texts(out, List.of("overview", "decisions")))), FORBIDDEN_FOR_CHOICES);
        if (hit != null) {
            return "CHOICE_JUDGED: " + hit;
        }
        return null;
    }

    /**
     * Bài hiện cho người lớn: tổng quan + các mục có tiêu đề (mục rỗng thì bỏ) + dòng "Chưa đủ bài để nhận xét: <tên>" do BE
     * viết từ gói (AI không nói về các mã này). Đầu ra không phải JSON → trả nguyên văn.
     */
    static String compose(String pkgText, String raw) {
        JsonNode out = read(raw);
        if (out == null || !out.isObject() || !out.path("overview").isTextual()) {
            return raw;
        }
        List<String> parts = new ArrayList<>();
        parts.add(out.path("overview").asText().trim());
        boolean cls = ReportPackage.CLASS.equals(String.valueOf(read(pkgText) == null ? null
                : read(pkgText).path("kind").asText()));
        section(parts, out, "decisions", "Quyết định trong truyện");
        section(parts, out, "doingWell", cls ? "Lớp làm tốt" : "Bé làm tốt");
        section(parts, out, "needsPractice", cls ? "Cần ôn lại" : "Cần luyện thêm");
        section(parts, out, "tips", cls ? "Gợi ý cho tiết học" : "Gợi ý cho gia đình");
        ReportPackage.Pkg pkg = ReportPackage.read(pkgText);
        String few = pkg == null || pkg.result() == null || pkg.result().concepts() == null
                || pkg.result().noData() == null ? null : ReportPackage.noDataLine(pkg.result());
        if (few != null) {
            parts.add(few);
        }
        return String.join("\n\n", parts);
    }

    private static void section(List<String> parts, JsonNode out, String key, String title) {
        List<String> lines = new ArrayList<>();
        out.path(key).forEach(it -> lines.add("- " + it.path("text").asText().trim()));
        if (!lines.isEmpty()) {
            parts.add(title + "\n" + String.join("\n", lines));
        }
    }

    private static List<String> texts(JsonNode out, List<String> sections) {
        List<String> t = new ArrayList<>();
        for (String s : sections) {
            JsonNode n = out.path(s);
            if (n.isTextual()) {
                t.add(n.asText());
            }
            n.forEach(it -> t.add(it.path("text").asText("")));
        }
        return t;
    }

    /** Mọi con số trong gói, đã chuẩn hoá (gói nguyên văn: số JSON, số trong chữ phương án / mục tiêu, mã như D01.02). */
    static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(norm(m.group()));
            for (String part : m.group().split("[.,]")) {
                out.add(norm(part));   // "1.02" trong gói cũng cho phép 1 và 2 đứng riêng
            }
        }
        return out;
    }

    /** 1.800.000 / 1,800,000 → 1800000; 0,5 → 0.5; bỏ số 0 đứng đầu phần nguyên (01 → 1). */
    static String norm(String t) {
        String s = THOUSANDS.matcher(t).matches() ? t.replaceAll("[.,]", "") : t.replace(',', '.');
        int dot = s.indexOf('.');
        String head = dot < 0 ? s : s.substring(0, dot);
        head = head.replaceFirst("^0+(?=\\d)", "");
        return dot < 0 ? head : head + s.substring(dot);
    }

    private static String firstWord(String text, List<String> words) {
        for (String w : words) {
            Pattern p = Pattern.compile("(?<!\\p{L})" + Pattern.quote(w) + "(?!\\p{L})",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (p.matcher(text).find()) {
                return w;
            }
        }
        return null;
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC);
    }

    private static JsonNode read(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }
}
