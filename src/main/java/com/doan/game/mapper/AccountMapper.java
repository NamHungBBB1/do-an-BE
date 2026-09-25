package com.doan.game.mapper;

import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.entity.Account;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class AccountMapper {

    private AccountMapper() {
    }

    public static AccountResponse sang(Account nguon) {
        throw new UnsupportedOperationException("chua cai dat");
    }
}
