package com.doan.game.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Chạy TẠI CHỖ (app.assets.serve-local=true): BE tự trả kho ảnh ở /assets/** để thử Studio mà không đụng VPS — đặt kèm
 * ASSETS_BASE_URL=http://localhost:8080/assets. Production để tắt: nginx trả /finteen/assets/ thẳng từ đĩa (22/10).
 */
@Configuration
public class AssetsConfig implements WebMvcConfigurer {

    @Value("${app.assets.serve-local:false}")
    private boolean serveLocal;

    @Value("${app.assets.dir}")
    private String dir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (serveLocal) {
            registry.addResourceHandler("/assets/**")
                    .addResourceLocations(Path.of(dir).toAbsolutePath().toUri().toString())
                    .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        }
    }
}
