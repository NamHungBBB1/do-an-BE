package com.doan.game.service.impl;

import com.doan.game.repository.VoiceClipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khoá giọng Java phải khớp TỪNG KÝ TỰ với Python (render_voice.py + voice/worker.py): key-vectors.json do
 * ops/voice/key_vectors.py sinh từ 52 câu đã có giọng + ca biên (emoji, gạch dài, "500.000đ", spokenText, tắt giọng, ghi đè).
 * Lệch một ký tự = BE coi câu cũ là câu mới → sinh lại / gỡ giọng đang đúng.
 */
class VoiceKeysTest {

    private final VoiceKeys keys = new VoiceKeys(Mockito.mock(VoiceClipRepository.class), new ObjectMapper(),
            new Personalizer(new ObjectMapper()));

    @Test
    void sameKeysAsPython() throws Exception {
        JsonNode vectors;
        try (InputStream in = new ClassPathResource("voice/key-vectors.json").getInputStream()) {
            vectors = new ObjectMapper().readTree(in);
        }
        assertThat(vectors.size()).isGreaterThanOrEqualTo(58);
        for (JsonNode v : vectors) {
            VoiceKeys.Line l = keys.lineOf(v.path("scene"));
            String id = v.path("scene").path("id").asText();
            if (v.path("profile").isNull()) {
                assertThat(l).as(id).isNull();
                continue;
            }
            assertThat(l).as(id).isNotNull();
            assertThat(l.profile()).as(id).isEqualTo(v.path("profile").asText());
            assertThat(l.readText()).as(id).isEqualTo(v.path("readText").asText());
            assertThat(l.key()).as(id).isEqualTo(v.path("key").asText());
        }
    }

    @Test
    void cleanMatchesRenderRules() {
        assertThat(VoiceKeys.clean("Con được 500.000đ — nhớ nhé… ✨  ok")).isEqualTo("Con được 500.000 đồng , nhớ nhé... ok");
        assertThat(VoiceRenderService.isMp3(new byte[]{'I', 'D', '3', 4})).isTrue();
        assertThat(VoiceRenderService.isMp3(new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0})).isTrue();
        assertThat(VoiceRenderService.isMp3("<html".getBytes())).isFalse();
    }

    /** Cá nhân hoá (spec 11/11): câu của mọi tổ hợp được đưa vào hàng đợi giọng — token đã thay, An nữ đọc bằng giọng nữ. */
    @Test
    void personalizedChapterListsLinesOfEveryCombo() throws Exception {
        JsonNode ch = new ObjectMapper().readTree(("""
                {'personalization':{'tokens':{'item':{'default':'tai nghe','interest:SPORT':'đôi giày'}}},
                 'scenes':[{'id':'S1','speakerId':'AN','text':'Mình muốn {{item}} quá!',
                            'variants':{'gender:FEMALE':{'voice':'ngoc-huyen'}}}]}
                """).replace('\'', '"'));
        java.util.List<VoiceKeys.Line> lines = keys.lines(ch);
        assertThat(lines).extracting(VoiceKeys.Line::readText).allMatch(t -> !t.contains("{{"));
        assertThat(lines).extracting(l -> l.profile() + "|" + l.readText())
                .contains("adam|Mình muốn tai nghe quá!", "ngoc-huyen|Mình muốn tai nghe quá!",
                        "adam|Mình muốn đôi giày quá!", "ngoc-huyen|Mình muốn đôi giày quá!");
        // (2 giọng) × (2 chữ: mặc định + SPORT) = 4 câu khác nhau; 7 sở thích còn lại về mặc định → trùng khoá, không đếm lại
        assertThat(lines).hasSize(4);
    }
}
