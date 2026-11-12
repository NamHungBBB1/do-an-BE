package com.doan.game.DTO.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Một trang kèm tổng số: FE biết còn bao nhiêu trang thay vì lật tới khi nhận danh sách rỗng.
 * Dùng cho ba bảng admin: giao dịch, tài khoản và sổ cấp / thu vai.
 */
public record PageResponse<T>(List<T> items, long totalElements, int totalPages, int page, int size) {

    /** Trang thô đã có sẵn danh sách dòng đã ánh xạ (dùng khi phải gom dữ liệu cho cả trang trước). */
    public static <T> PageResponse<T> of(Page<?> p, List<T> items) {
        return new PageResponse<>(items, p.getTotalElements(), p.getTotalPages(), p.getNumber(), p.getSize());
    }

    /** Ánh xạ từng dòng của trang. */
    public static <E, T> PageResponse<T> of(Page<E> p, Function<E, T> map) {
        return of(p, p.getContent().stream().map(map).toList());
    }
}
