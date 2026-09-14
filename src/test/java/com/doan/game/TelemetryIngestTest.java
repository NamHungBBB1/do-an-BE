package com.doan.game;

import com.doan.game.telemetry.repository.EstimateEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Một phép kiểm chạy được cho lát cắt dọc: HTTP -> validate -> DB.
 * Hỏng lúc nào cũng biết ngay, không phải mở Postman.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelemetryIngestTest {

    @Autowired MockMvc mvc;
    @Autowired EstimateEventRepository repo;

    private static final String ONE_ROW = """
        [{"playerKey":"p1","seed":"S-2026","buildVersion":"0.0.1","vai":"nhan-cong",
          "itemId":"goi1-moc4","guess":900000000,"truth":1312406699,"unit":"dong",
          "confidence":"doan-mo","attemptIndex":0,
          "shownAt":"2026-09-14T03:00:00Z","committedAt":"2026-09-14T03:00:12Z"}]
        """;

    @Test
    void ghi_duoc_mot_dong_hop_le() throws Exception {
        long before = repo.count();
        mvc.perform(post("/api/telemetry/estimates")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.saved").value(1));
        assertThat(repo.count()).isEqualTo(before + 1);
    }

    @Test
    void thieu_seed_thi_tu_choi_chu_khong_ghi_bua() throws Exception {
        long before = repo.count();
        mvc.perform(post("/api/telemetry/estimates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ONE_ROW.replace("\"seed\":\"S-2026\"", "\"seed\":\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(repo.count()).isEqualTo(before);
    }
}
