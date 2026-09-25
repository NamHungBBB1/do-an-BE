package com.doan.game.controller;

import com.doan.game.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public void moNhom() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/close")
    public void dongNhom() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/{id}/capacity")
    public void demChoTrong() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
