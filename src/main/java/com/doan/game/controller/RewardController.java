package com.doan.game.controller;

import com.doan.game.service.RewardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/rewards")
@RequiredArgsConstructor
public class RewardController {

    private final RewardService rewardService;

    @PostMapping("/redeem")
    public void doiThuong() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
