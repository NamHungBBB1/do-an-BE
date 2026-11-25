package com.doan.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.doan.game.service.PushMessage;
import com.doan.game.service.PushService;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * PushService không được gọi FCM thật trong test, và không được làm hỏng luồng nghiệp vụ khi chưa
 * có khoá. Hai nhánh đó kiểm ở đây; nhánh SENT/INVALID_TOKEN cần token thật nên để lúc thử tay.
 */
class PushServiceTest {

    private static final String TEST_APP = "push-test";

    private FirebaseApp app;

    @AfterEach
    void cleanUp() throws Exception {
        if (app != null) {
            app.delete();
            app = null;
        }
    }

    @Test
    @DisplayName("Thiếu khoá: push tắt, mọi lời gọi trả DISABLED chứ không ném")
    void disabledWithoutCredentials() {
        PushService service = new PushService(provider(null));

        assertFalse(service.isEnabled());
        assertEquals(PushService.Outcome.DISABLED,
                service.send("fake-token", new PushMessage("t", "b", Map.of())));
    }

    @Test
    @DisplayName("Có FirebaseApp nhưng token rỗng: trả FAILED, không gọi mạng")
    void blankTokenIsRejectedBeforeNetwork() {
        PushService service = new PushService(provider(fakeApp()));

        assertTrue(service.isEnabled());
        assertEquals(PushService.Outcome.FAILED, service.send(" ", message()));
        assertEquals(PushService.Outcome.FAILED, service.send(null, message()));
        assertEquals(PushService.Outcome.FAILED, service.send("fake-token", null));
    }

    private PushMessage message() {
        return new PushMessage("Minh vừa xong chương 3", "Chương 3 · 12/40 chương",
                Map.of("type", "CHAPTER_COMPLETED"));
    }

    /**
     * FirebaseApp thật nhưng credentials giả (không có khoá riêng, không gọi được Google) — đủ để
     * isEnabled() trả true mà không cần service account trong CI.
     */
    private FirebaseApp fakeApp() {
        GoogleCredentials credentials =
                GoogleCredentials.create(new AccessToken("fake", Date.from(Instant.now().plusSeconds(600))));
        app = FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId("finteen-mobile-test")
                .build(), TEST_APP);
        return app;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<FirebaseApp> provider(FirebaseApp value) {
        ObjectProvider<FirebaseApp> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
