package com.doan.game.service.impl;

import com.doan.game.enums.AiProviderKind;
import com.doan.game.service.AiKeyProbe.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Thử khoá (26/11): 404 của Gemini = project không được dùng model đang đặt → báo rõ thay vì "HTTP 404". */
class HttpAiKeyProbeTest {

    private final HttpAiKeyProbe probe = new HttpAiKeyProbe("gemini-3.5-flash-lite");

    @Test
    void classifiesStatuses() {
        assertThat(probe.classify(AiProviderKind.GEMINI, 200).outcome()).isEqualTo(Outcome.OK);
        assertThat(probe.classify(AiProviderKind.GEMINI, 404).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(probe.classify(AiProviderKind.GEMINI, 404).detail()).contains("gemini-3.5-flash-lite");
        assertThat(probe.classify(AiProviderKind.GEMINI, 403).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(probe.classify(AiProviderKind.GEMINI, 429).outcome()).isEqualTo(Outcome.QUOTA);
        assertThat(probe.classify(AiProviderKind.OPENROUTER, 402).outcome()).isEqualTo(Outcome.QUOTA);
        assertThat(probe.classify(AiProviderKind.OPENROUTER, 404).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(probe.classify(AiProviderKind.GEMINI, 503).outcome()).isEqualTo(Outcome.ERROR);
    }
}
