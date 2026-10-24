package com.doan.game.service.impl;

import com.doan.game.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bộ khuôn nội dung (24/10, K1). Dữ liệu THẬT CH01–03 (bản 1.1.0 đang chạy) phải qua được — dạng cũ chỉ cảnh báo, không chặn;
 * sai rõ ràng thì 1001 kèm đường dẫn trường.
 */
class ContentSchemaTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ContentSchema schema = new ContentSchema(json);

    private JsonNode chapter(String file) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/content/" + file)) {
            return json.readTree(in);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CH01.json", "CH02.json", "CH03.json"})
    void realChaptersPassWithLegacyWarnings(String file) throws Exception {
        JsonNode ch = chapter(file);
        List<String> warnings = schema.validate(ch);
        assertThat(warnings).isNotEmpty();   // điều kiện chuỗi, tên loại cũ 'scene', mini-game thiếu kind, FIQ
        assertThat(warnings).anyMatch(w -> w.contains("tên cũ 'scene'"));
        assertThat(warnings).anyMatch(w -> w.contains("initialStats.FIQ"));
        // Bộ khuôn không được đổi nội dung: tách rồi ghép phải y bản cũ (Hưng 24/10).
        assertThat(ch).isEqualTo(chapter(file));
    }

    @Test
    void singleOptionChoiceIsAllowedWithoutWarning() throws Exception {
        // 1 lựa chọn = màn "Tiếp tục" có thật (CH04, CH05) — hợp lệ, không nhắc nữa (Hưng 24/10: lưu nháp sạch cảnh báo).
        ObjectNode c = minimal();
        ((com.fasterxml.jackson.databind.node.ArrayNode) c.path("scenes").get(0).path("options")).remove(1);
        assertThat(schema.validate(c)).isEmpty();
    }

    @Test
    void optionIdReusedAcrossScenesIsWarnedButSameSceneIsRejected() throws Exception {
        // CH04 dùng lại CH04_SC03_A/B ở 3 cảnh; BE chơi tìm lựa chọn theo (cảnh, id) nên chỉ nhắc.
        ObjectNode c = minimal();
        ((ObjectNode) c.path("scenes").get(1)).removeAll().put("id", "b").put("type", "choice").put("text", "?")
                .set("options", json.readTree("[{\"id\":\"o1\",\"text\":\"A\",\"nextSceneId\":\"e\"},{\"id\":\"o3\",\"text\":\"C\",\"nextSceneId\":\"e\"}]"));
        assertThat(schema.validate(c)).containsExactly("scenes[1].options[0].id: mã 'o1' đã dùng ở scenes[0].options (câu hỏi khác) — nên đặt mã riêng");

        // Cùng nhóm quyết định (các phiên bản của một câu hỏi, như CH04_RECEIPT) dùng chung mã là đúng → không nhắc.
        ((ObjectNode) c.path("scenes").get(0)).put("choiceGroup", "G");
        ((ObjectNode) c.path("scenes").get(1)).put("choiceGroup", "G");
        assertThat(schema.validate(c)).isEmpty();

        ObjectNode d = minimal();
        ((ObjectNode) d.path("scenes").get(0).path("options").get(1)).put("id", "o1");
        assertThatThrownBy(() -> schema.validate(d)).hasMessageContaining("scenes[0].options[1].id: mã lựa chọn trùng trong cảnh: o1");
    }

    @Test
    void uppercasePositionIsKeptAndWarned() throws Exception {
        JsonNode ch = chapter("CH01.json");
        assertThat(schema.validate(ch)).anyMatch(w -> w.contains(".position: 'LEFT'"));
        assertThat(ch.toString()).contains("\"position\":\"LEFT\"");
    }

    @Test
    void miniGameWithoutKindGetsSuggestedKind() throws Exception {
        assertThat(schema.validate(chapter("CH01.json")))
                .anyMatch(w -> w.contains("game.kind") && w.contains("NEED_WANT"));
    }

    private ObjectNode minimal() throws Exception {
        return (ObjectNode) json.readTree("""
                {"id":"c","title":"t","scenes":[
                  {"id":"a","type":"choice","text":"?","options":[
                     {"id":"o1","text":"A","nextSceneId":"b","deltaStats":{"SAVING":5},
                      "condition":{"all":[{"var":"money","op":">=","value":100},{"var":"flag:x","op":"==","value":true}]},
                      "lockedReason":"Chưa đủ tiền"},
                     {"id":"o2","label":"B","nextSceneId":"b"}]},
                  {"id":"b","type":"minigame","game":{"kind":"SORT","items":[{"id":"i1","grade":{"rule":{"eq":"NEEDS"}}}]},"nextSceneId":"e"},
                  {"id":"e","type":"ending"}]}
                """);
    }

    /** Khoá lựa chọn: một cơ chế = condition theo khuôn + câu nhắn cho trẻ (Hưng 24/10). requires cũ chỉ cảnh báo. */
    @Test
    void lockNeedsReasonAndOldRequiresWarns() throws Exception {
        ObjectNode c = minimal();
        ((ObjectNode) c.path("scenes").get(0).path("options").get(0)).remove("lockedReason");
        assertThatThrownBy(() -> schema.validate(c)).hasMessageContaining("options[0].lockedReason");

        ObjectNode d = minimal();
        ((ObjectNode) d.path("scenes").get(0).path("options").get(1)).putObject("requires").put("minMoney", 100);
        assertThat(schema.validate(d)).anyMatch(w -> w.contains("options[1].requires") && w.contains("dạng cũ"));
    }

    /** Ghi nhớ lựa chọn theo "quyết định": choice:<nhóm> phải trỏ câu hỏi có thật và mã lựa chọn có thật; inspected:<thẻ> lạ chỉ nhắc. */
    @Test
    void choiceAndInspectedVariables() throws Exception {
        ObjectNode ok = minimal();
        setCondition(ok, "{\"var\":\"choice:a\",\"op\":\"==\",\"value\":\"o1\"}");
        assertThat(schema.validate(ok)).isEmpty();

        ObjectNode badValue = minimal();
        setCondition(badValue, "{\"var\":\"choice:a\",\"op\":\"==\",\"value\":\"o9\"}");
        assertThatThrownBy(() -> schema.validate(badValue)).hasMessageContaining("'o9' không phải lựa chọn của 'a'");

        ObjectNode badGroup = minimal();
        setCondition(badGroup, "{\"var\":\"choice:khong-co\",\"op\":\"==\",\"value\":\"o1\"}");
        assertThatThrownBy(() -> schema.validate(badGroup)).hasMessageContaining("nhóm quyết định 'khong-co'");

        ObjectNode unknownCard = minimal();
        setCondition(unknownCard, "{\"var\":\"inspected:the-la\",\"op\":\"==\",\"value\":true}");
        assertThat(schema.validate(unknownCard)).anyMatch(w -> w.contains("thẻ xem 'the-la'"));
    }

    private void setCondition(ObjectNode chapter, String leaf) throws Exception {
        ((ObjectNode) chapter.path("scenes").get(0).path("options").get(0)).set("condition", json.readTree(leaf));
    }

    /** "Chưa đạt" chỉ tính vào điểm — onFailSceneId là trường thừa (Hưng 24/10 bỏ hẳn), còn trong dữ liệu cũ thì nhắc. */
    @Test
    void onFailSceneIdIsLeftoverWarning() throws Exception {
        ObjectNode c = minimal();
        ((ObjectNode) c.path("scenes").get(1)).put("onFailSceneId", "a");
        assertThat(schema.validate(c)).anyMatch(w -> w.contains("scenes[1].onFailSceneId") && w.contains("thừa"));
    }

    /** Đường đi chưa trọn: lưu nháp chỉ cảnh báo (đang soạn dở), gửi duyệt thì chặn. */
    @Test
    void brokenFlowWarnsOnSaveAndBlocksOnSubmit() throws Exception {
        ObjectNode c = minimal();
        ((com.fasterxml.jackson.databind.node.ArrayNode) c.path("scenes")).add(json.readTree("""
                {"id":"lac","type":"dialogue","text":"không ai tới"}"""));
        List<String> warnings = schema.validate(c);
        assertThat(warnings).anyMatch(w -> w.contains("lac: không đường nào từ cảnh đầu tới được"));
        assertThat(warnings).anyMatch(w -> w.contains("scenes[3]: chưa chọn cảnh kế tiếp"));
        assertThatThrownBy(() -> schema.requireCompleteFlow(c)).hasMessageContaining("đường đi");
        schema.requireCompleteFlow(minimal());   // chương trọn vẹn thì qua
    }

    /** Chương "chốt quyết định": chọn xong mà còn đường quay lại chính câu hỏi → trẻ kẹt. Lựa chọn chỉ dẫn đường (deferLedger) thì được. */
    @Test
    void lockDecisionsLoopIsCaught() throws Exception {
        ObjectNode c = minimal();
        c.putObject("metadata").put("lockDecisions", true);
        ((ObjectNode) c.path("scenes").get(1)).put("nextSceneId", "a");   // mini-game quay lại câu hỏi
        ((ObjectNode) c.path("scenes").get(0).path("options").get(1)).put("nextSceneId", "e");
        assertThat(schema.validate(c)).anyMatch(w -> w.contains("options[0]") && w.contains("kẹt"));
        ((ObjectNode) c.path("scenes").get(0).path("options").get(0)).put("deferLedger", true);
        assertThat(schema.validate(c)).noneMatch(w -> w.contains("kẹt"));
    }

    @Test
    void choiceGroupDefaultsToSceneId() throws Exception {
        assertThat(ContentSchema.choiceGroupOf(json.readTree("{\"id\":\"s1\"}"))).isEqualTo("s1");
        assertThat(ContentSchema.choiceGroupOf(json.readTree("{\"id\":\"s1\",\"choiceGroup\":\"g\"}"))).isEqualTo("g");
    }

    @Test
    void structuredContentPassesClean() throws Exception {
        assertThat(schema.validate(minimal())).isEmpty();
    }

    @Test
    void clearErrorsAreRejectedWithFieldPath() throws Exception {
        ObjectNode c = minimal();
        ((ObjectNode) c.path("scenes").get(0)).put("type", "quiz");
        assertThatThrownBy(() -> schema.validate(c)).isInstanceOf(AppException.class).hasMessageContaining("scenes[0].type");

        ObjectNode d = minimal();
        ((com.fasterxml.jackson.databind.node.ArrayNode) d.path("scenes").get(0).path("options")).removeAll();
        assertThatThrownBy(() -> schema.validate(d)).hasMessageContaining("ít nhất 1 lựa chọn");

        ObjectNode e = minimal();
        ((ObjectNode) e.path("scenes").get(0).path("options").get(0).path("deltaStats")).put("SAVING", "nhiều");
        assertThatThrownBy(() -> schema.validate(e)).hasMessageContaining("deltaStats.SAVING");

        ObjectNode f = minimal();
        ((ObjectNode) f.path("scenes").get(1).path("game")).put("kind", "TETRIS");
        assertThatThrownBy(() -> schema.validate(f)).hasMessageContaining("game.kind");

        ObjectNode g = minimal();
        ((ObjectNode) g.path("scenes").get(0).path("options").get(0).path("condition").path("all").get(0)).put("op", "=~");
        assertThatThrownBy(() -> schema.validate(g)).hasMessageContaining(".op");

        ObjectNode h = minimal();
        ((ObjectNode) h.path("scenes").get(0).path("options").get(1)).put("nextSceneId", "khong-co");
        assertThatThrownBy(() -> schema.validate(h)).hasMessageContaining("khong-co");

        ObjectNode i = (ObjectNode) json.readTree("""
                {"scenes":[{"id":"a","type":"inspect","inspect":{"hotspot":{"x":120}}}]}""");
        assertThatThrownBy(() -> schema.validate(i)).hasMessageContaining("hotspot.x");
    }

    @Test
    void miniGameGradesAreCheckedOnSave() throws Exception {
        ObjectNode c = minimal();
        ObjectNode item = (ObjectNode) c.path("scenes").get(1).path("game").path("items").get(0);
        ((ObjectNode) item.path("grade")).set("rule", json.readTree("{\"like\":\"NEEDS\"}"));
        assertThatThrownBy(() -> schema.validate(c)).hasMessageContaining("'like'");
        ((ObjectNode) item.path("grade")).set("rule", json.readTree("{\"eq\":\"NEEDS\"}"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) c.path("scenes").get(1).path("game").path("items")).add(item.deepCopy());
        assertThatThrownBy(() -> schema.validate(c)).hasMessageContaining("trùng");
        ((ObjectNode) c.path("scenes").get(1)).set("game", json.readTree("{\"kind\":\"SORT\"}"));
        assertThat(schema.validate(c)).anyMatch(w -> w.contains("không có câu chấm điểm"));
    }

    /** Bọc một cảnh mini-game thành chương tối thiểu (cảnh mini-game → kết) để kiểm riêng phần game. */
    private ObjectNode chapterOf(JsonNode minigameScene) {
        ObjectNode scene = minigameScene.deepCopy();
        scene.put("nextSceneId", "end");
        scene.remove("onPassSceneId");
        ObjectNode ch = json.createObjectNode();
        ch.put("id", "t");
        ch.put("title", "t");
        ch.putArray("scenes").add(scene).add(json.createObjectNode().put("id", "end").put("type", "ending"));
        return ch;
    }

    @Test
    void everyRealMiniGamePassesItsKindTemplate() throws Exception {
        JsonNode games;
        try (InputStream in = getClass().getResourceAsStream("/grading/games.json")) {
            games = json.readTree(in);
        }
        assertThat(games).hasSize(13);
        for (JsonNode g : games) {
            assertThat(schema.validate(chapterOf(g.path("scene"))))
                    .as("mini-game %s", g.path("scene").path("game").path("id").asText())
                    .noneMatch(w -> w.contains("game.kind"));
        }
    }

    @Test
    void miniGameConfigIsCheckedAgainstItsKind() throws Exception {
        JsonNode shop = null;
        try (InputStream in = getClass().getResourceAsStream("/grading/games.json")) {
            for (JsonNode g : json.readTree(in)) {
                if ("SMART_SHOPPING".equals(g.path("scene").path("game").path("kind").asText())) {
                    shop = g.path("scene");
                }
            }
        }
        ObjectNode missing = chapterOf(shop);
        ((ObjectNode) missing.path("scenes").get(0).path("game")).remove("phones");
        assertThatThrownBy(() -> schema.validate(missing)).hasMessageContaining("game.phones").hasMessageContaining("bắt buộc");
        ObjectNode wrongType = chapterOf(shop);
        ((ObjectNode) wrongType.path("scenes").get(0).path("game").path("phones").get(1)).put("price", "1.300.000đ");
        assertThatThrownBy(() -> schema.validate(wrongType)).hasMessageContaining("game.phones[1].price").hasMessageContaining("số");
    }
}
