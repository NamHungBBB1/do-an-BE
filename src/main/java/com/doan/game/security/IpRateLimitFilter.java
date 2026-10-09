package com.doan.game.security;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn theo IP cho ba cửa công khai có GỬI MAIL (rà soát 09/10, S-02): /api/auth/register,
 * /api/auth/verify/resend, /api/auth/password/forgot. Không có nó, một vòng for là cạn quota Brevo
 * (tài khoản dùng chung với Kidz) và bảng account đầy tài khoản rác.
 *
 * IP lấy từ phần tử CUỐI của X-Forwarded-For: nginx nối IP thật vào cuối ($proxy_add_x_forwarded_for),
 * phần đầu client tự ghi gì cũng được. Không có header mà gọi từ loopback = gọi nội bộ trên máy chủ
 * (health check, test MockMvc) → không đếm.
 *
 * Chạy TRƯỚC Spring Security (HIGHEST_PRECEDENCE): chặn spam trước khi tốn công đọc JWT.
 *
 * ponytail: cửa sổ trượt trong bộ nhớ, một instance; nhiều instance thì chuyển sang Redis.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IpRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> MAIL_PATHS = Set.of(
            "/api/auth/register", "/api/auth/verify/resend", "/api/auth/password/forgot");
    /** Quá số IP này thì xoá sạch bảng đếm: mất đếm một lần còn hơn phình bộ nhớ. */
    private static final int MAX_TRACKED_IPS = 50_000;

    private final int limit;
    private final Duration window;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public IpRateLimitFilter(@Value("${app.ratelimit.mail-per-window:5}") int limit,
                             @Value("${app.ratelimit.window-seconds:900}") long windowSeconds,
                             ObjectMapper mapper, Clock clock) {
        this.limit = limit;
        this.window = Duration.ofSeconds(windowSeconds);
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return !"POST".equals(req.getMethod()) || !MAIL_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(req);
        if (ip == null) {
            chain.doFilter(req, res);
            return;
        }
        long now = clock.millis();
        if (hits.size() > MAX_TRACKED_IPS) {
            hits.clear();
        }
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (q) {
            long cutoff = now - window.toMillis();
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.pollFirst();
            }
            allowed = q.size() < limit;
            if (allowed) {
                q.addLast(now);
            }
        }
        if (!allowed) {
            ErrorCode ec = ErrorCode.TOO_MANY_REQUESTS;
            res.setStatus(ec.getStatus().value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            mapper.writeValue(res.getWriter(), ApiResponse.error(ec.getCode(), ec.getMessage()));
            return;
        }
        chain.doFilter(req, res);
    }

    /** null = nội bộ (loopback, không qua nginx) → không giới hạn. */
    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        String addr = req.getRemoteAddr();
        if (addr == null || addr.equals("127.0.0.1") || addr.equals("0:0:0:0:0:0:0:1") || addr.equals("::1")) {
            return null;
        }
        return addr;
    }
}
