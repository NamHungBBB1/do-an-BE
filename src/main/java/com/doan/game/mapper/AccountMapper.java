package com.doan.game.mapper;

import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.entity.Account;

import java.util.List;
import java.util.Set;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class AccountMapper {

    private AccountMapper() {
    }

    /**
     * Không kèm gói, không kèm vai — dùng khi người gọi không cần hai thứ đó (chỉ cần họ tên).
     */
    public static AccountResponse sang(Account nguon) {
        return new AccountResponse(nguon.getId(), nguon.getEmail(), nguon.getDisplayName(), List.of(), List.of());
    }

    /**
     * Bản đầy đủ. plans và roles KHÔNG lấy được từ Account — Parent/Teacher suy ra từ
     * Entitlement còn hạn, ADMIN nằm ở AccountRole — nên caller phải tra rồi đưa vào đây.
     * Đặt chúng vào bản ghi này thay vì để entity tự mang là đúng tinh thần tầng mapper.
     */
    public static AccountResponse sang(Account nguon, Set<String> plans, List<String> roles) {
        return new AccountResponse(nguon.getId(), nguon.getEmail(), nguon.getDisplayName(),
                List.copyOf(plans), List.copyOf(roles));
    }
}
