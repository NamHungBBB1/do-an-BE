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
 * Hai điều kiện, thiếu một là từ chối (401, người dùng phải đăng nhập lại):
 *
 *  1. Tài khoản vẫn tồn tại — xoá tài khoản thì token cũ phải chết theo.
 *  2. `tv` trong token bằng `Account.tokenVersion` — tăng số này mỗi lần đổi mật khẩu là đuổi
 *     được kẻ đang cầm token cũ (xem Account.tokenVersion).
 *
 * Còn `scope` thì KHÔNG từ chối mà THAY bằng vai + gói đang có trong database (L-13, 04/10):
 * mua gói xong có quyền ngay, gói hết hạn hay bị thu ADMIN thì mất quyền ngay, mà không ai bị
 * đá ra phải đăng nhập lại.
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

        // L-13 (04/10, Hưng chốt): KHÔNG từ chối khi scope lệch — mua gói xong hay gói hết hạn lúc
        // 0h mà đá người dùng ra thì họ phải đăng nhập lại bằng mật khẩu, mà không có cách lấy
        // token mới. Thay vào đó THAY scope bằng vai + gói đang có trong DB: quyền luôn đúng hiện
        // tại, token vẫn sống. Thu hồi thật (đổi mật khẩu, xoá tài khoản) vẫn đi qua tv ở trên.
        Set<String> hienTai = phamViHienTai(id);
        if (phamViTrongToken(jwt).equals(hienTai)) {
            return jwt;
        }
        return Jwt.withTokenValue(jwt.getTokenValue())
                .headers(h -> h.putAll(jwt.getHeaders()))
                .claims(c -> {
                    c.putAll(jwt.getClaims());
                    c.put("scope", String.join(" ", hienTai));
                })
                .build();
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
