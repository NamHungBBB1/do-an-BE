package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hàm chấm mini-game chung (spec build/spec-cham-minigame-be-2026-10-24.md). Ca kiểm {@code grading/cases.json} sinh từ LUẬT FE
 * HIỆN TẠI (ops/finteen-assets/cham/sinh_ca_kiem.mjs) trên 13 mini-game thật đã thêm {@code grade} (ops/finteen-assets/them_grade.py)
 * — BE phải chấm khớp 100 %, cả về đúng / sai lẫn tập câu (mẫu số).
 */
class MiniGameGraderTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode resource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/grading/" + name)) {
            return json.readTree(in);
        }
    }

    private static Map<String, JsonNode> inputs(JsonNode obj) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        obj.properties().forEach(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    @Test
    void sharedCasesFromFeRulesMatchExactly() throws Exception {
        Map<String, JsonNode> scenes = new HashMap<>();
        resource("games.json").forEach(g -> scenes.put(g.path("chapter").asText() + "/" + g.path("scene").path("id").asText(),
                g.path("scene")));
        JsonNode cases = resource("cases.json");
        List<String> mismatches = new ArrayList<>();
        Map<String, Integer> perGame = new TreeMap<>();
        for (JsonNode c : cases) {
            JsonNode scene = scenes.get(c.path("chapter").asText() + "/" + c.path("sceneId").asText());
            assertThat(scene).as("cảnh %s", c.path("sceneId").asText()).isNotNull();
            Map<String, Boolean> got = new TreeMap<>();
            MiniGameGrader.grade(scene, inputs(c.path("inputs"))).forEach(g -> got.put(g.questionId(), g.correct()));
            Map<String, Boolean> want = new TreeMap<>();
            c.path("expected").properties().forEach(e -> want.put(e.getKey(), e.getValue().asBoolean()));
            if (!got.equals(want)) {
                mismatches.add(c.path("gameId").asText() + " " + c.path("inputs") + "\n  FE " + want + "\n  BE " + got);
            }
            perGame.merge(c.path("gameId").asText(), want.size(), Integer::sum);
        }
        assertThat(mismatches).as("lệch FE / BE (%d ca)", cases.size()).isEmpty();
        assertThat(perGame).hasSize(12); // 13 cảnh trừ Đua tiết kiệm (không chấm)
    }

    @Test
    void questionsAreFilteredByTheSceneParts() throws Exception {
        Map<String, Integer> count = new TreeMap<>();
        resource("games.json").forEach(g -> count.put(g.path("scene").path("game").path("id").asText(),
                MiniGameGrader.questions(g.path("scene")).size()));
        // 63 câu = 64 câu FE đang chấm − tiêu chí `needs` CH04 (luôn đúng, bỏ — Hưng duyệt 24/10)
        assertThat(count.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(63);
        assertThat(count).containsEntry("LOAN_OFFERS_CH06", 6).containsEntry("LOAN_REPAYMENT_CH06", 4)
                .containsEntry("INSURANCE_DESK_CH07", 2).containsEntry("SCAM_OR_SAFE_CH07", 10)
                .containsEntry("PORTFOLIO_CH08", 2).containsEntry("CLAIM_REVIEW_CH08", 3).containsEntry("LIFE_PLAN_CH08", 3)
                .containsEntry("SAVINGS_RACE_CH02", 0).containsEntry("SMART_SHOPPING_CH04", 3);
    }

    @Test
    void missingOrOddValuesMarkOnlyThatQuestionWrong() throws Exception {
        JsonNode scene = json.readTree("""
                {"id":"S","knowledgeUnits":["D01.02"],"game":{"id":"G","items":[
                  {"id":"a","grade":{"rule":{"eq":"NEEDS"}}},
                  {"id":"b","grade":{"rule":{"eq":5}}},
                  {"id":"c","grade":{"input":"list","rule":{"all":[{"has":["x"]},{"count":{"min":2}}]}}}]}}""");
        var graded = MiniGameGrader.grade(scene, inputs(json.readTree("""
                {"a":"NEEDS","b":{"oops":1}}""")));
        assertThat(graded).extracting(MiniGameGrader.Graded::correct).containsExactly(true, false, false);
        assertThat(graded).extracting(MiniGameGrader.Graded::chosen).containsExactly("NEEDS", "{\"oops\":1}", null);
        assertThat(graded).extracting(MiniGameGrader.Graded::concept).containsOnly("D01.02");
        // số dạng chữ so theo giá trị; chuỗi rỗng sai
        assertThat(MiniGameGrader.grade(scene, inputs(json.readTree("{\"b\":\" 5 \"}"))).get(1).correct()).isTrue();
        assertThat(MiniGameGrader.grade(scene, inputs(json.readTree("{\"b\":\"\"}"))).get(1).correct()).isFalse();
    }

    @Test
    void ruleGrammarIsChecked() throws Exception {
        assertThat(MiniGameGrader.ruleProblems(json.readTree("{\"eq\":\"A\"}"), "r")).isEmpty();
        assertThat(MiniGameGrader.ruleProblems(json.readTree("{\"field\":\"cash\",\"min\":30}"), "r")).isEmpty();
        assertThat(MiniGameGrader.ruleProblems(json.readTree("{\"like\":\"A\"}"), "r"))
                .anyMatch(p -> p.contains("'like'")).anyMatch(p -> p.contains("thiếu phép so"));
        assertThat(MiniGameGrader.ruleProblems(json.readTree("{\"in\":[]}"), "r")).anyMatch(p -> p.contains("r.in"));
        assertThat(MiniGameGrader.ruleProblems(json.readTree("{\"any\":[{\"eq\":1},{\"field\":\"x\"}]}"), "r"))
                .anyMatch(p -> p.startsWith("r.any[1]"));
        assertThat(MiniGameGrader.ruleProblems(json.readTree("{\"all\":[],\"eq\":1}"), "r")).hasSize(2);
    }
}
