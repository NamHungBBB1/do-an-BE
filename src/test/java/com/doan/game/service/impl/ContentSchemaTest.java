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
    void singleOptionChoiceIsAllowedWithWarning() throws Exception {
        ObjectNode c = minimal();
        ((com.fasterxml.jackson.databind.node.ArrayNode) c.path("scenes").get(0).path("options")).remove(1);
        assertThat(schema.validate(c)).containsExactly("scenes[0].options: chỉ 1 lựa chọn — như nút tiếp tục");
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
                      "condition":{"all":[{"var":"money","op":">=","value":100},{"var":"flag:x","op":"==","value":true}]}},
                     {"id":"o2","label":"B","nextSceneId":"b"}]},
                  {"id":"b","type":"minigame","game":{"kind":"SORT"},"nextSceneId":"e"},
                  {"id":"e","type":"ending"}]}
                """);
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
}
