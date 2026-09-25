package com.doan.game.controller;

import com.doan.game.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/freeze/{groupId}")
    public void dongCung() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/{id}")
    public void xemBaoCao() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
