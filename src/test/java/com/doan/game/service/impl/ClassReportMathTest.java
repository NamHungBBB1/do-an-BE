package com.doan.game.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phép tính nhỏ của API báo cáo lớp (26/11): %, khoảng điểm, nhãn phương án, chủ đề của mã. */
class ClassReportMathTest {

    @Test
    void rateRoundsAndNullOnEmpty() {
        assertThat(ClassReportService.rate(11, 18)).isEqualTo(61);
        assertThat(ClassReportService.rate(1, 3)).isEqualTo(33);
        assertThat(ClassReportService.rate(2, 3)).isEqualTo(67);
        assertThat(ClassReportService.rate(0, 0)).isNull();
    }

    @Test
    void scoreBandEdges() {
        assertThat(ClassReportService.scoreBand(0)).isZero();
        assertThat(ClassReportService.scoreBand(49)).isZero();
        assertThat(ClassReportService.scoreBand(50)).isEqualTo(1);
        assertThat(ClassReportService.scoreBand(69)).isEqualTo(1);
        assertThat(ClassReportService.scoreBand(70)).isEqualTo(2);
        assertThat(ClassReportService.scoreBand(84)).isEqualTo(2);
        assertThat(ClassReportService.scoreBand(85)).isEqualTo(3);
        assertThat(ClassReportService.scoreBand(100)).isEqualTo(3);
    }

    @Test
    void optionKeysAndDomains() {
        assertThat(ClassReportService.optionKey(0)).isEqualTo("A");
        assertThat(ClassReportService.optionKey(2)).isEqualTo("C");
        assertThat(KnowledgeUnits.domainOf("D04.02")).isEqualTo("D04");
        assertThat(KnowledgeUnits.domainOf("CH01_X")).isNull();
        assertThat(KnowledgeUnits.DOMAINS).hasSize(8).containsKeys("D01", "D08");
    }
}
