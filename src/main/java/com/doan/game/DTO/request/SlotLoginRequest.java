package com.doan.game.DTO.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Trẻ đăng nhập bằng mã in trên giấy + PIN 6 số. Hai ô này KHÔNG phải mật khẩu:
 * ai đó nhặt được mã cũng chưa vào được nếu không có PIN.
 */
public record SlotLoginRequest(
        @NotBlank @Size(max = 16) String code,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "PIN gồm 6 chữ số") String pin) {
}