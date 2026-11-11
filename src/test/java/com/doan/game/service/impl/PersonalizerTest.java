package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hàm cá nhân hoá (spec build/spec-ca-nhan-hoa-2026-11-11.md). Ghép phải XÁC ĐỊNH: đổi code ghép = đổi mọi lượt chơi cũ. */
class PersonalizerTest {

    private final ObjectMapper om = new ObjectMapper();
    private final Personalizer p = new Personalizer(om);

    private JsonNode json(String s) throws Exception {
        return om.readTree(s.replace('\'', '"'));
    }

    // Mini chương: token món đồ + mục tiêu theo sở thích, câu của An đổi theo giới tính, mini-game Cần / Muốn dùng token.
    private static final String CHAPTER = """
            {'chapterId':'CH01',
             'personalization':{'tokens':{
                'item':{'default':'chiếc tai nghe','interest:SPORT':'đôi giày','interest:MUSIC':'album'},
                'goal':{'default':'lớp vẽ','interest:SPORT':'lớp bóng đá'}}},
             'scenes':[
              {'id':'S1','speakerId':'MINH','text':'{{item}} đang giảm còn 250.000đ!','background':'{{ASSET_BASE}}/a/bg1.webp',
               'nextSceneId':'S2'},
              {'id':'S2','type':'choice','speakerId':'AN','text':'Thiếu 50.000đ mới đủ đóng {{goal}}.',
               'variants':{'gender:FEMALE':{'speaker':'An (nữ)'}},
               'options':[{'id':'A','text':'Mua {{item}} giá 250.000đ','moneyEvents':[{'amount':-250000,'description':'Mua {{item}}'}],
                           'deltaStats':{'SAVING':-25},'nextSceneId':'S3'},
                          {'id':'B','text':'Giữ tiền cho {{goal}}','nextSceneId':'S3'}]},
              {'id':'S3','type':'minigame','game':{'items':[
                 {'id':'I1','label':'{{item}} thay khi hỏng','category':'NEEDS','grade':{'rule':{'eq':'NEEDS'}}},
                 {'id':'I2','label':'{{item}} mẫu mới','category':'WANTS','grade':{'rule':{'eq':'WANTS'}}}]}}]}
            """;

    @Test
    void resolveFollowsHungRule() {
        // trùng → lấy; một bên → lấy bên đó
        assertThat(p.resolve(Map.of("PARENT", Map.of("interest", "SPORT"), "CHILD", Map.of("interest", "SPORT"))))
                .containsEntry("interest", "SPORT");
        assertThat(p.resolve(Map.of("PARENT", Map.of("gender", "FEMALE")))).containsEntry("gender", "FEMALE");
        assertThat(p.resolve(Map.of("CHILD", Map.of("age", "8_10")))).containsEntry("age", "8_10");
        // khác nhau = 0 → game chọn phụ quyết; không có game → không nhãn (bản gốc)
        Map<String, Map<String, String>> conflict = Map.of("PARENT", Map.of("interest", "SPORT"), "CHILD", Map.of("interest", "MUSIC"));
        assertThat(p.resolve(conflict)).doesNotContainKey("interest");
        assertThat(p.resolve(Map.of("PARENT", Map.of("interest", "SPORT"), "CHILD", Map.of("interest", "MUSIC"),
                "GAME", Map.of("interest", "ART")))).containsEntry("interest", "ART");
        // game không đè giá trị đã thống nhất
        assertThat(p.resolve(Map.of("PARENT", Map.of("gender", "MALE"), "CHILD", Map.of("gender", "MALE"),
                "GAME", Map.of("gender", "FEMALE")))).containsEntry("gender", "MALE");
        // giá trị lạ = chưa trả lời; không ai trả lời = rỗng
        assertThat(p.resolve(Map.of("PARENT", Map.of("interest", "HACK"), "CHILD", Map.of("interest", "TECH"))))
                .containsEntry("interest", "TECH");
        assertThat(p.resolve(Map.of())).isEmpty();
        assertThat(p.resolve(null)).isEmpty();
    }

    @Test
    void composeFillsTokensAndSurfaceVariantsOnly() throws Exception {
        JsonNode ch = json(CHAPTER);
        JsonNode girlSport = p.compose(ch, Map.of("gender", "FEMALE", "interest", "SPORT", "age", "8_10"));
        assertThat(girlSport.has("personalization")).isFalse();
        assertThat(girlSport.at("/scenes/0/text").asText()).isEqualTo("đôi giày đang giảm còn 250.000đ!");
        assertThat(girlSport.at("/scenes/0/background").asText()).isEqualTo("{{ASSET_BASE}}/a/bg1.webp");
        assertThat(girlSport.at("/scenes/1/text").asText()).isEqualTo("Thiếu 50.000đ mới đủ đóng lớp bóng đá.");
        assertThat(girlSport.at("/scenes/1/speaker").asText()).isEqualTo("An (nữ)");
        assertThat(girlSport.at("/scenes/1").has("variants")).isFalse();
        assertThat(girlSport.at("/scenes/1/options/0/moneyEvents/0/description").asText()).isEqualTo("Mua đôi giày");
        assertThat(girlSport.at("/scenes/1/options/0/moneyEvents/0/amount").asInt()).isEqualTo(-250000);
        assertThat(girlSport.at("/scenes/2/game/items/1/label").asText()).isEqualTo("đôi giày mẫu mới");
        assertThat(girlSport.at("/scenes/2/game/items/1/category").asText()).isEqualTo("WANTS");

        JsonNode base = p.compose(ch, Map.of());
        assertThat(base.at("/scenes/0/text").asText()).isEqualTo("chiếc tai nghe đang giảm còn 250.000đ!");
        assertThat(base.at("/scenes/1").has("speaker")).isFalse();
        // interest có token "item" nhưng không có "goal" → goal về default
        assertThat(p.compose(ch, Map.of("interest", "MUSIC")).at("/scenes/1/text").asText())
                .isEqualTo("Thiếu 50.000đ mới đủ đóng lớp vẽ.");
        // không sửa bản gốc
        assertThat(ch.has("personalization")).isTrue();
    }

    @Test
    void composeIsDeterministic() throws Exception {
        JsonNode ch = json(CHAPTER);
        Map<String, String> l = Map.of("gender", "FEMALE", "interest", "SPORT");
        assertThat(om.writeValueAsString(p.compose(ch, l))).isEqualTo(om.writeValueAsString(p.compose(ch, l)));
        assertThat(p.variantKey(ch, Map.of("age", "8_10", "interest", "SPORT", "gender", "FEMALE")))
                .isEqualTo("gender:FEMALE|interest:SPORT");   // age không dùng trong chương → không vào khoá
        assertThat(p.variantKey(ch, Map.of())).isEqualTo("base");
    }

    @Test
    void combosCoverEveryValuePlusUnknown() throws Exception {
        List<Map<String, String>> all = p.combos(json(CHAPTER));
        assertThat(p.dimensionsUsed(json(CHAPTER))).containsExactly("gender", "interest");
        assertThat(all).hasSize(3 * 9);   // (2 giới tính + chưa biết) × (8 sở thích + chưa biết)
        assertThat(all.get(0)).isEmpty();
        assertThat(all).contains(Map.of("gender", "FEMALE", "interest", "TRIP"));
    }

    @Test
    void validChapterPasses() throws Exception {
        assertThat(p.check(json(CHAPTER))).isEmpty();
    }

    @Test
    void realChaptersWithoutPersonalizationAreUntouched() throws Exception {
        for (String code : List.of("CH01", "CH02", "CH03")) {
            JsonNode ch;
            try (InputStream in = new ClassPathResource("content/" + code + ".json").getInputStream()) {
                ch = om.readTree(in);
            }
            assertThat(p.check(ch)).as(code).isEmpty();
            assertThat(p.compose(ch, Map.of("gender", "FEMALE", "interest", "SPORT"))).as(code).isEqualTo(ch);
            assertThat(p.combos(ch)).as(code).hasSize(1);
        }
    }

    @Test
    void checkBlocksNonSurfaceOverride() throws Exception {
        String bad = CHAPTER.replace("'variants':{'gender:FEMALE':{'speaker':'An (nữ)'}}",
                "'variants':{'gender:FEMALE':{'nextSceneId':'S1','deltaStats':{'SAVING':9}}}");
        assertThat(p.check(json(bad))).anyMatch(s -> s.contains("nextSceneId")).anyMatch(s -> s.contains("deltaStats"));
    }

    @Test
    void checkBlocksUnknownLabelMissingDefaultMixedDims() throws Exception {
        String bad = CHAPTER
                .replace("'interest:MUSIC':'album'", "'interest:KPOP':'album'")
                .replace("'goal':{'default':'lớp vẽ',", "'goal':{'gender:FEMALE':'lớp múa',");
        List<String> problems = p.check(json(bad));
        assertThat(problems).anyMatch(s -> s.contains("interest:KPOP"));
        assertThat(problems).anyMatch(s -> s.contains("goal") && s.contains("default"));

        String mixed = CHAPTER.replace("'goal':{'default':'lớp vẽ','interest:SPORT':'lớp bóng đá'}",
                "'goal':{'default':'lớp vẽ','interest:SPORT':'lớp bóng đá','gender:FEMALE':'lớp múa'}");
        assertThat(p.check(json(mixed))).anyMatch(s -> s.contains("một chiều"));

        String twoDims = CHAPTER.replace("'variants':{'gender:FEMALE':{'speaker':'An (nữ)'}}",
                "'variants':{'gender:FEMALE':{'text':'A'},'age:8_10':{'text':'B'}}");
        assertThat(p.check(json(twoDims))).anyMatch(s -> s.contains("hai chiều"));
    }

    @Test
    void checkBlocksChangedNumbersUnresolvedTokensAndTokenInId() throws Exception {
        String price = CHAPTER.replace("'interest:SPORT':'đôi giày'", "'interest:SPORT':'đôi giày 300.000đ'");
        assertThat(p.check(json(price))).anyMatch(s -> s.contains("số trong chữ"));

        String missing = CHAPTER.replace("đang giảm còn", "{{brand}} đang giảm còn");
        assertThat(p.check(json(missing))).anyMatch(s -> s.contains("{{brand}}"));

        String inId = CHAPTER.replace("{'id':'I1',", "{'id':'I1-{{item}}',");
        assertThat(p.check(json(inId))).anyMatch(s -> s.contains("không phải chữ / ảnh"));
    }
}
