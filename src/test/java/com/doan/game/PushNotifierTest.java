package com.doan.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.doan.game.service.DeviceTokenService;
import com.doan.game.service.PushMessage;
import com.doan.game.service.PushNotifier;
import com.doan.game.service.PushService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PushNotifier: gửi cho MỌI máy của tài khoản, xoá token FCM báo chết, và không bao giờ làm hỏng
 * luồng chính. Không transaction trong test nên gửi ngay (đường afterCompletion kiểm lúc chạy thật).
 */
class PushNotifierTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final PushMessage MESSAGE =
            new PushMessage("Minh vừa xong chương 3", "Chương 3 · 12/40 chương",
                    Map.of("type", "CHAPTER_COMPLETED"));

    @Test
    @DisplayName("Gửi đủ mọi máy; chỉ token INVALID_TOKEN mới bị xoá")
    void sendsToEveryDeviceAndDeletesOnlyDeadTokens() {
        PushService push = mock(PushService.class);
        DeviceTokenService store = mock(DeviceTokenService.class);
        when(push.isEnabled()).thenReturn(true);
        when(store.tokensOf(ACCOUNT)).thenReturn(List.of("tok-1", "tok-2", "tok-3"));
        when(push.send("tok-1", MESSAGE)).thenReturn(PushService.Outcome.SENT);
        when(push.send("tok-2", MESSAGE)).thenReturn(PushService.Outcome.INVALID_TOKEN);
        when(push.send("tok-3", MESSAGE)).thenReturn(PushService.Outcome.FAILED);

        new PushNotifier(push, store, Runnable::run).notify(ACCOUNT, MESSAGE);

        verify(push, times(3)).send(anyString(), eq(MESSAGE));
        verify(store).removeToken("tok-2");
        verify(store, never()).removeToken("tok-1");
        verify(store, never()).removeToken("tok-3");
    }

    @Test
    @DisplayName("Thiếu khoá Firebase: không đọc DB, không gửi")
    void disabledDoesNotTouchTheDatabase() {
        PushService push = mock(PushService.class);
        DeviceTokenService store = mock(DeviceTokenService.class);
        when(push.isEnabled()).thenReturn(false);

        new PushNotifier(push, store, Runnable::run).notify(ACCOUNT, MESSAGE);

        verifyNoInteractions(store);
        verify(push, never()).send(anyString(), eq(MESSAGE));
    }

    @Test
    @DisplayName("Lỗi đọc token chỉ ghi log, không ném ra luồng nghiệp vụ")
    void storageFailureIsSwallowed() {
        PushService push = mock(PushService.class);
        DeviceTokenService store = mock(DeviceTokenService.class);
        when(push.isEnabled()).thenReturn(true);
        when(store.tokensOf(ACCOUNT)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> new PushNotifier(push, store, Runnable::run).notify(ACCOUNT, MESSAGE));
    }
}
