package com.doan.game.configuration;

import com.doan.game.service.ArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Khoá máy lưu trữ kiểm ở MỘT chỗ cho mọi đường /api/archive-worker/** (27/11): đường thêm sau không thể quên kiểm. Chạy trước
 * khi Spring đọc body / path, nên khoá sai luôn ra 403 (lỗi ném ở đây vẫn qua GlobalExceptionHandler).
 */
@Configuration
public class ArchiveWorkerConfig implements WebMvcConfigurer {

    public static final String HEADER = "X-Archive-Worker-Key";
    private final ArchiveService archiveService;

    public ArchiveWorkerConfig(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                archiveService.requireWorker(req.getHeader(HEADER));
                return true;
            }
        }).addPathPatterns("/api/archive-worker/**");
    }
}
