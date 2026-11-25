package com.doan.game.service.impl;

import com.doan.game.DTO.response.SlotReportResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Nhận xét có cấu trúc (25/11): bài AI giữ lời AI nhưng số / tên lấy từ gói; bản theo luật cùng hình, không lời. */
class ReportSectionsTest {

    private static final String PKG = ReportAiOutputTest.PKG.replace(
            "\"decisions\":[{\"chosen\":\"B\",\"deliberate\":true,\"scene\":\"CH04_RECAP_KEEP\"}",
            "\"decisions\":[{\"chosen\":\"B\",\"deliberate\":true,\"label\":\"Giữ tiền cho lớp vẽ\","
                    + "\"scene\":\"CH01_SC02_CHOICE\"}");

    private static final String OUT = "{\"overview\":\"Bé làm đúng 5/6 câu.\","
            + "\"decisions\":[{\"scene\":\"CH01_SC02_CHOICE\",\"text\":\"Bé có bước cân nhắc.\"}],"
            + "\"doingWell\":[{\"concept\":\"D01.02\",\"text\":\" Bé phân biệt tốt cần và muốn. \"}],"
            + "\"needsPractice\":[{\"concept\":\"D01.04\",\"text\":\"Cần luyện thêm.\"}],"
            + "\"tips\":[{\"concept\":\"D01.04\",\"keyPoint\":0,\"text\":\"Cùng bé đặt mục tiêu.\"}]}";

    @Test
    void aiOutputKeepsWordsButTakesNumbersFromPackage() {
        SlotReportResponse.Sections s = ReportSections.fromAi(PKG, OUT);
        assertThat(s.overview()).isEqualTo("Bé làm đúng 5/6 câu.");
        assertThat(s.decisions()).singleElement().satisfies(d -> {
            assertThat(d.option()).isEqualTo("Giữ tiền cho lớp vẽ");
            assertThat(d.deliberate()).isTrue();
            assertThat(d.text()).isEqualTo("Bé có bước cân nhắc.");
        });
        assertThat(s.doingWell()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo(KnowledgeUnits.nameOf("D01.02"));
            assertThat(c.band()).isEqualTo("GOOD");
            assertThat(c.correct()).isEqualTo(5);
            assertThat(c.total()).isEqualTo(6);
            assertThat(c.text()).isEqualTo("Bé phân biệt tốt cần và muốn.");
        });
        assertThat(s.needsPractice()).singleElement().satisfies(c -> assertThat(c.band()).isEqualTo("PRACTICE"));
        assertThat(s.tips()).singleElement().satisfies(t -> assertThat(t.text()).isEqualTo("Cùng bé đặt mục tiêu."));
        assertThat(s.notEnough()).singleElement().satisfies(c -> {
            assertThat(c.concept()).isEqualTo("D01.03");
            assertThat(c.total()).isZero();
        });
        // Đầu ra không phải JSON (bài cũ, hỏng) → null, FE dùng text.
        assertThat(ReportSections.fromAi(PKG, "không phải json")).isNull();
        assertThat(ReportSections.fromAi("hỏng", OUT)).isNull();
    }

    @Test
    void rulePackageHasSameShapeWithoutWords() {
        SlotReportResponse.Sections s = ReportSections.fromPackage(ReportPackage.read(PKG));
        assertThat(s.overview()).isNull();
        // Bản luật kể mọi quyết định của bé trong gói (3), lời = null.
        assertThat(s.decisions()).hasSize(3).allSatisfy(d -> assertThat(d.text()).isNull());
        assertThat(s.decisions().get(0).option()).isEqualTo("Giữ tiền cho lớp vẽ");
        assertThat(s.decisions().get(0).deliberate()).isTrue();
        assertThat(s.doingWell()).extracting(SlotReportResponse.ConceptNote::concept).containsExactly("D01.02");
        assertThat(s.needsPractice()).extracting(SlotReportResponse.ConceptNote::concept).containsExactly("D01.04");
        assertThat(s.tips()).isEmpty();
        assertThat(s.notEnough()).extracting(SlotReportResponse.ConceptCount::concept).containsExactly("D01.03");
        assertThat(ReportSections.fromPackage(null)).isNull();
    }
}
