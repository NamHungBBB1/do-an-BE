package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Từ điển khung v3 (65 mã) + chữ cho câu sai (đợt D.1, 19/11). Cảnh mẫu lấy dáng thật của nội dung 2.7.1: thẻ Cần / Muốn
 * (CH01), câu có options a / b (CH03), luật số, tập hồ sơ (CH07), trường không có nhãn (CH08).
 */
class MiniGameLabelsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void dictionaryHas65UniqueCodesWithVietnameseNames() {
        assertThat(KnowledgeUnits.ALL).hasSize(65);
        Set<String> domains = new HashSet<>();
        KnowledgeUnits.ALL.values().forEach(u -> {
            assertThat(u.id()).matches("D0[1-8]\\.\\d{2}");
            assertThat(u.nameVi()).as(u.id()).isNotBlank();
            assertThat(u.objective()).as(u.id()).isNotBlank();
            assertThat(u.level()).as(u.id()).isNotBlank();
            domains.add(u.domain());
        });
        assertThat(domains).hasSize(8);
        assertThat(KnowledgeUnits.nameOf("D01.02")).isEqualTo("Phân biệt cần và muốn");
        assertThat(KnowledgeUnits.nameOf("D01.06")).isEqualTo("Tiền tệ và tỷ giá");
        assertThat(KnowledgeUnits.nameOf("T01.01")).isEqualTo("T01.01");
    }

    @Test
    void categoricalItemsResolveToZoneAndOptionLabels() throws Exception {
        JsonNode needWant = JSON.readTree("""
                {"id":"s","knowledgeUnits":["D01.02"],"game":{"id":"G","zones":[
                  {"id":"NEEDS","title":"Giỏ Cần"},{"id":"WANTS","title":"Giỏ Muốn"}],"items":[
                  {"id":"ITEM_HEADPHONES","label":"Tai nghe mới khi tai nghe cũ còn dùng được","category":"WANTS",
                   "explanation":"Mong muốn đổi đồ mới.","grade":{"rule":{"eq":"WANTS"}}}]}}""");
        MiniGameLabels.Labels l = MiniGameLabels.of(needWant, "ITEM_HEADPHONES", "NEEDS");
        assertThat(l).isEqualTo(new MiniGameLabels.Labels("Tai nghe mới khi tai nghe cũ còn dùng được", "Giỏ Cần", "WANTS",
                "Giỏ Muốn", "Mong muốn đổi đồ mới."));

        JsonNode options = JSON.readTree("""
                {"id":"s","game":{"id":"G","questions":[{"id":"net_salary","title":"Lương thực nhận",
                  "options":[{"id":"a","label":"2.700.000đ"},{"id":"b","label":"3.300.000đ"}],
                  "hint":"Trừ khấu trừ.","explain":"Thực nhận = gộp − khấu trừ.","grade":{"rule":{"eq":"a"}}}]}}""");
        l = MiniGameLabels.of(options, "net_salary", "b");
        assertThat(l).isEqualTo(new MiniGameLabels.Labels("Lương thực nhận", "3.300.000đ", "a", "2.700.000đ",
                "Thực nhận = gộp − khấu trừ."));
    }

    @Test
    void ruleOpsDescribedInVietnamese() throws Exception {
        JsonNode scene = JSON.readTree("""
                {"id":"s","game":{"id":"G","docs":[{"id":"receipt","label":"Hoá đơn"},{"id":"claim","label":"Đơn yêu cầu"}],
                 "qs":[
                  {"id":"reserve","label":"Số tiền giữ lại","grade":{"rule":{"min":200000}}},
                  {"id":"range","label":"Khoảng","grade":{"rule":{"min":300000,"max":500000}}},
                  {"id":"day","label":"Ngày huỷ","grade":{"rule":{"min":1,"max":29}}},
                  {"id":"packet","label":"Bộ hồ sơ","grade":{"rule":{"sets":[["receipt","claim"]]}}},
                  {"id":"must","label":"Phải có","grade":{"rule":{"has":["receipt"],"count":{"min":2}}}},
                  {"id":"either","label":"Một trong","grade":{"rule":{"in":["receipt","claim"]}}},
                  {"id":"pay","label":"Phí","grade":{"rule":{"eq":300000}}},
                  {"id":"near","label":"Giữ tiền mặt","grade":{"input":"allocation","rule":{"field":"cash","min":30}}},
                  {"id":"spread","label":"Phân bổ","grade":{"input":"allocation","rule":{"all":[
                    {"field":"bonds","min":1},{"field":"equity","min":1}]}}},
                  {"id":"total","label":"Tổng","grade":{"rule":{"sumOf":{"from":"docs","field":"x"},"min":1}}}]}}""");
        assertThat(MiniGameLabels.of(scene, "reserve", "150000"))
                .extracting(MiniGameLabels.Labels::chosenLabel, MiniGameLabels.Labels::expectedLabel)
                .containsExactly("150.000đ", "≥ 200.000đ");
        assertThat(MiniGameLabels.of(scene, "range", "1").expectedLabel()).isEqualTo("từ 300.000đ đến 500.000đ");
        assertThat(MiniGameLabels.of(scene, "day", "31").expectedLabel()).isEqualTo("từ 1 đến 29");
        MiniGameLabels.Labels packet = MiniGameLabels.of(scene, "packet", "[\"receipt\"]");
        assertThat(packet.chosenLabel()).isEqualTo("Hoá đơn");
        assertThat(packet.expectedLabel()).isEqualTo("đúng 2 mục: Hoá đơn, Đơn yêu cầu");
        assertThat(packet.expected()).isEqualTo("{\"sets\":[[\"receipt\",\"claim\"]]}");
        assertThat(MiniGameLabels.of(scene, "must", "[]").expectedLabel()).isEqualTo("có Hoá đơn, chọn ít nhất 2 mục");
        assertThat(MiniGameLabels.of(scene, "either", "x").expectedLabel()).isEqualTo("Hoá đơn hoặc Đơn yêu cầu");
        assertThat(MiniGameLabels.of(scene, "pay", "250000").expectedLabel()).isEqualTo("300.000đ");
        // Trường / phép không có nhãn trong nội dung → null (FE dùng chữ dự phòng), không bao giờ mã thô.
        assertThat(MiniGameLabels.of(scene, "near", "{\"cash\":10}"))
                .extracting(MiniGameLabels.Labels::label, MiniGameLabels.Labels::chosenLabel,
                        MiniGameLabels.Labels::expectedLabel)
                .containsExactly("Giữ tiền mặt", null, null);
        assertThat(MiniGameLabels.of(scene, "spread", null).expectedLabel()).isNull();
        assertThat(MiniGameLabels.of(scene, "total", null).expectedLabel()).isNull();
        assertThat(MiniGameLabels.of(scene, "missing", "x")).isEqualTo(MiniGameLabels.NONE);
    }

    @Test
    void legacyItemUsesCategory() throws Exception {
        JsonNode legacy = JSON.readTree("""
                {"id":"s","game":{"id":"G","zones":[{"id":"NEEDS","title":"Cần"},{"id":"WANTS","title":"Muốn"}],
                 "items":[{"id":"ITEM_UPGRADE","category":"WANTS","text":"Nâng cấp tai nghe"}]}}""");
        assertThat(MiniGameLabels.of(legacy, "ITEM_UPGRADE", "NEEDS"))
                .isEqualTo(new MiniGameLabels.Labels("Nâng cấp tai nghe", "Cần", "WANTS", "Muốn", null));
    }
}
