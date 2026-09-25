package com.doan.game.controller;

import com.doan.game.service.SlotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @PostMapping
    public void phatCho() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/return")
    public void traCho() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @DeleteMapping("/{id}")
    public void xoaSach() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/login")
    public void dangNhapTre() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
