package com.doan.game.auth.web;

import com.doan.game.auth.domain.ChildSlot;
import com.doan.game.auth.domain.LearningContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mọi hình dạng request/response của auth gom vào một tệp — chúng chỉ là dữ liệu,
 * rải ra 12 tệp thì mở 12 tab mới đọc được một luồng.
 */
public final class AuthDtos {

    private AuthDtos() {}

    // ---- gửi lên ----

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 160) String email,
            /** Chốt: Gmail + SĐT. Mới chỉ lưu, chưa gửi SMS xác thực — SMS tốn tiền. */
            @NotBlank @Pattern(regexp = "^(0|\\+84)[0-9]{8,10}$",
                    message = "số điện thoại không đúng dạng Việt Nam") String phone,
            @NotBlank @Size(min = 8, max = 72, message = "mật khẩu tối thiểu 8 ký tự") String password,
            @NotBlank @Size(max = 80) String displayName) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {}

    public record ChildLoginRequest(
            @NotBlank @Size(max = 16) String code,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "PIN gồm 6 chữ số") String pin) {}

    public record CreateSlotRequest(
            @NotBlank @Size(max = 80) String displayName,
            @NotNull LearningContext context) {}

    public record EnterContextRequest(@NotNull UUID slotId) {}

    /**
     * Bật gói. CHƯA nối cổng thanh toán — thanh toán là tính năng khác, không phải auth.
     * Chốt "cả hai gói đều mất phí" được ghi nhận ở đây, chưa được thu ở đây.
     */
    public record PlanRequest(boolean parentPlan, boolean teacherPlan) {}

    // ---- trả về ----

    public record TokenView(String token, String typ, long expiresIn) {}

    /** PIN gốc CHỈ xuất hiện ở đây, đúng một lần. Sau đó chỉ còn bản băm. */
    public record NewSlotView(UUID id, String code, String pin, String displayName,
                              LearningContext context) {}

    public record SlotView(UUID id, String code, String displayName, LearningContext context,
                           int level, String badge, boolean linked, boolean archived,
                           boolean locked) {
        public static SlotView of(ChildSlot s) {
            return new SlotView(s.getId(), s.getCode(), s.getDisplayName(), s.getContext(),
                    s.getLevel(), s.getBadge(), s.getLinkedAccountId() != null,
                    s.isArchived(), s.isLocked());
        }
    }

    public record AdultMeView(UUID id, String email, String phone, String displayName,
                              Set<String> roles, List<SlotView> slots,
                              List<SlotView> linkedContexts) {}

    /**
     * Trẻ nhìn thấy đúng bối cảnh mình đang ở. Không có đường nào từ đây nhìn sang bối cảnh kia —
     * đó là chốt "nhà không thấy lớp, lớp không thấy nhà".
     */
    public record ChildMeView(UUID slotId, String displayName, LearningContext context,
                              int level, String badge, boolean linked) {}
}
