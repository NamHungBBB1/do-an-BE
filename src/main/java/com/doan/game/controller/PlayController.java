package com.doan.game.controller;

import com.doan.game.service.PlayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/play")
@RequiredArgsConstructor
public class PlayController {

    private final PlayService playService;

    @PostMapping("/chapter")
    public void nhanLoChuong() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/state")
    public void xemTrangThai() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
