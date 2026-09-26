package com.doan.game.controller;

import com.doan.game.service.LearnerModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/learner-model")
@RequiredArgsConstructor
public class LearnerModelController {

    private final LearnerModelService learnerModelService;

    @GetMapping("/summary")
    public void xemTongKet() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
