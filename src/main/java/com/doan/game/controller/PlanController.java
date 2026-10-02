package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PlanResponse;
import com.doan.game.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bảng giá công khai (SecurityConfig mở). Admin đặt giá ở AdminController. Có ruột (02/10). */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @GetMapping
    public ApiResponse<List<PlanResponse>> xemGia() {
        return ApiResponse.ok(planService.xemGia());
    }
}
