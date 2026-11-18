package com.doan.game.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Mốc mức BR-44 (số nguyên, biên 3 câu / 50 % / 70 %) và nhãn "có bước cân nhắc" BR-45 — không cần Spring. */
class ReportBandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void bandBoundaries() {
        assertThat(ReportServiceImpl.band(2, 2)).isEqualTo("NO_DATA");
        assertThat(ReportServiceImpl.band(1, 3)).isEqualTo("PRACTICE");
        assertThat(ReportServiceImpl.band(4, 10)).isEqualTo("PRACTICE");
        assertThat(ReportServiceImpl.band(5, 10)).isEqualTo("FAMILIAR");
        assertThat(ReportServiceImpl.band(2, 3)).isEqualTo("FAMILIAR");
        assertThat(ReportServiceImpl.band(7, 10)).isEqualTo("GOOD");
        assertThat(ReportServiceImpl.band(3, 3)).isEqualTo("GOOD");
    }

    @Test
    void deliberateFromEvidenceScores() throws Exception {
        assertThat(ReportServiceImpl.deliberate(JSON.readTree("{\"evidenceScores\":{\"I\":0,\"C\":0,\"P\":2}}"))).isTrue();
        assertThat(ReportServiceImpl.deliberate(JSON.readTree("{\"evidenceScores\":{\"I\":0,\"C\":0,\"P\":0}}"))).isFalse();
        assertThat(ReportServiceImpl.deliberate(JSON.readTree("{\"evidenceScores\":{}}"))).isNull();
        assertThat(ReportServiceImpl.deliberate(JSON.readTree("{\"text\":\"x\"}"))).isNull();
        assertThat(ReportServiceImpl.deliberate(null)).isNull();
    }
}
