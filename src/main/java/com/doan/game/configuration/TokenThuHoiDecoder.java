package com.doan.game.configuration;

import com.doan.game.entity.Account;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.service.EntitlementService;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Kiểm chữ ký HẾT rồi mới đối chiếu với database. Lớp này là thứ khiến token hết hiệu lực
 * NGAY LẬP TỨC thay vì đợi 168 giờ.
 *
 * Ba điều kiện, thiếu một là từ chối (401, người dùng phải xin token mới):
 *
 *  1. Tài khoản vẫn tồn tại — xoá tài khoản thì token cũ phải chết theo.
 *  2. `tv` trong token bằng `Account.tokenVersion` — tăng số này mỗi lần đổi mật khẩu là đuổi
 *     được kẻ đang cầm token cũ (xem Account.tokenVersion).
 *  3. `scope` trong token bằng vai + gói ĐANG CÓ trong database — không thì ai đó bị thu vai
 *     ADMIN hoặc gói hết hạn vẫn giữ quyền tới khi token hết hạn.
 *
 * Token trẻ (typ=SLOT) bỏ qua cả ba: trẻ không có Account để mà so.
 *
 * Đắt hơn decoder thường (2 câu truy vấn mỗi request), nhưng đó là cái giá của việc thu hồi.
 * Không kiểm thì đổi mật khẩu chẳng đuổi được ai.
 */
public class TokenThuHoiDecoder implements JwtDecoder {

    private final JwtDecoder goc;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final EntitlementService entitlementService;

    public TokenThuHoiDecoder(JwtDecoder goc, AccountRepository accountRepo,
                              AccountRoleRepository accountRoleRepo, EntitlementService entitlementService) {
        this.goc = goc;
        this.accountRepo = accountRepo;
        this.accountRoleRepo = accountRoleRepo;
        this.entitlementService = entitlementService;
    }

    @Override
    public Jwt decode(String token) {
        // Sai chữ ký, sai khoá, hết hạn: ném ngay tại đây, không đụng tới database.
        Jwt jwt = goc.decode(token);

        if (!"ACCOUNT".equals(jwt.getClaimAsString("typ"))) {
            return jwt;
        }

        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new BadJwtException("subject không phải UUID", e);
        }
        Account a = accountRepo.findById(id)
                .orElseThrow(() -> new BadJwtException("tài khoản không còn tồn tại"));

        Integer tv = phienBanTrongToken(jwt);
        if (tv != null && tv != a.getTokenVersion()) {
            throw new BadJwtException("token đã bị thu hồi (đổi mật khẩu)");
        }

        if (!phamViTrongToken(jwt).equals(phamViHienTai(id))) {
            throw new BadJwtException("phạm vi token không còn khớp với vai/gói hiện tại");
        }
        return jwt;
    }

    /**
     * Phiên bản trong claim `tv`. Trả null khi token không có claim (phát trước khi thêm cột)
     * — khi đó bỏ qua bước so, vì chữ ký vẫn do ta ký và không ai tự thêm claim được.
     * Số trong JSON có thể về tới đây là Integer, Long hay Double tùy bộ parse, nên đọc qua
     * Number rồi ép int.
     */
    private static Integer phienBanTrongToken(Jwt jwt) {
        Object raw = jwt.getClaim("tv");
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw != null) {
            try {
                return Integer.valueOf(raw.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Scope trong JWT là chuỗi cách nhau bởi dấu cách. Dùng TreeSet để so không phụ thuộc thứ tự. */
    private static Set<String> phamViTrongToken(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        Set<String> s = new TreeSet<>();
        if (scope != null) {
            for (String x : scope.trim().split("\\s+")) {
                if (!x.isBlank()) {
                    s.add(x);
                }
            }
        }
        return s;
    }

    private Set<String> phamViHienTai(UUID accountId) {
        Set<String> s = new TreeSet<>();
        accountRoleRepo.findByAccount_Id(accountId).forEach(r -> s.add(r.getRole().name()));
        entitlementService.activePlans(accountId).forEach(k -> s.add(k.name()));
        return s;
    }
}
