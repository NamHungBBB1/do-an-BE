package com.doan.game.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sao lưu H2 dạng tệp ra .zip, giữ đúng N bản mới nhất; H2 bộ nhớ thì bỏ qua. */
class DbBackupJobTest {

    @TempDir Path tmp;

    @Test
    void backsUpFileDatabaseAndKeepsNewest() throws Exception {
        String url = "jdbc:h2:file:" + tmp.resolve("db/finteen").toString().replace("\\", "/") + ";MODE=PostgreSQL";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("CREATE TABLE t(x INT)");
        Path dir = tmp.resolve("backup");
        Files.createDirectories(dir);
        for (String old : new String[]{"finteen-20260101-0245.zip", "finteen-20260102-0245.zip", "khac.zip"}) {
            Files.writeString(dir.resolve(old), "x");
        }
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T19:45:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

        Path file = new DbBackupJob(jdbc, clock, url, dir.toString(), 2).backupNow();

        assertEquals("finteen-20261008-0245.zip", file.getFileName().toString());
        assertTrue(Files.size(file) > 0);
        try (Stream<Path> files = Files.list(dir)) {
            // giữ 2 bản finteen-* mới nhất + tệp lạ không đụng tới
            assertEquals(java.util.Set.of("finteen-20261008-0245.zip", "finteen-20260102-0245.zip", "khac.zip"),
                    files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void skipsInMemoryDatabase() throws Exception {
        assertNull(new DbBackupJob(null, Clock.systemUTC(), "jdbc:h2:mem:x", tmp.toString(), 14).backupNow());
    }
}
