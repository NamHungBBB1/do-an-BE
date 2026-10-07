package com.doan.game.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sao lưu DB H2 mỗi đêm bằng lệnh BACKUP TO chạy NGAY TRONG app (cùng kết nối với app).
 *
 * Vì sao trong app: sự cố 07/10 — mở tệp DB sống bằng công cụ H2 từ bên ngoài làm H2 lùi về phiên bản cũ, mất
 * 1,5 ngày dữ liệu. BACKUP TO là lệnh online chính chủ của H2: chụp nhất quán khi app đang chạy, không mở tệp lần hai.
 * Chỉ chạy với H2 dạng tệp (jdbc:h2:file); test dùng H2 bộ nhớ nên tự bỏ qua. Sang PostgreSQL thì thay bằng pg_dump.
 */
@Component
public class DbBackupJob {

    private static final Logger log = LoggerFactory.getLogger(DbBackupJob.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String datasourceUrl;
    private final Path dir;
    private final int keep;

    public DbBackupJob(JdbcTemplate jdbc, Clock clock,
                       @Value("${spring.datasource.url}") String datasourceUrl,
                       @Value("${app.backup.dir:/var/finteen/backup}") String dir,
                       @Value("${app.backup.keep:14}") int keep) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.datasourceUrl = datasourceUrl;
        this.dir = Path.of(dir);
        this.keep = keep;
    }

    /** 02:45 giờ VN mỗi đêm (sau cron Kidz 02:30). */
    @Scheduled(cron = "${app.backup.cron:0 45 2 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void nightly() {
        try {
            Path file = backupNow();
            if (file != null) {
                log.info("Sao lưu DB xong: {} ({} byte)", file, Files.size(file));
            }
        } catch (Exception e) {
            // Không ném ra: job hỏng không được làm sập app. Log ERROR để soát lỗi thấy.
            log.error("Sao lưu DB THẤT BẠI", e);
        }
    }

    /** Chụp một bản .zip và xoá bản cũ, giữ {@code keep} bản mới nhất. Trả null nếu không phải H2 dạng tệp. */
    public Path backupNow() throws IOException {
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:h2:file:")) {
            return null;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("finteen-" + LocalDateTime.now(clock).format(STAMP) + ".zip").toAbsolutePath();
        // Đường dẫn do mình dựng (thư mục cấu hình + giờ), không có dữ liệu người dùng; vẫn bỏ dấu ' cho chắc.
        jdbc.execute("BACKUP TO '" + file.toString().replace("\\", "/").replace("'", "") + "'");
        prune();
        return file;
    }

    private void prune() throws IOException {
        List<Path> old;
        try (Stream<Path> files = Files.list(dir)) {
            old = files.filter(p -> p.getFileName().toString().matches("finteen-\\d{8}-\\d{4}\\.zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .skip(keep)
                    .toList();
        }
        for (Path p : old) {
            Files.deleteIfExists(p);
        }
    }
}
