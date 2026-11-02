package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.service.impl.VoiceRenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Cửa cho máy sinh giọng (GitHub Actions, voice/worker.py) — 03/11. Không dùng JWT: xác thực bằng header
 * {@code X-Voice-Worker-Key} (khoá chung, /etc/finteen.env VOICE_WORKER_KEY + GitHub Secrets). SecurityConfig permitAll đường này.
 */
@RestController
@RequestMapping("/api/voice-worker")
@RequiredArgsConstructor
public class VoiceWorkerController {

    static final String HEADER = "X-Voice-Worker-Key";
    private final VoiceRenderService voice;

    @GetMapping("/jobs")
    public ApiResponse<List<VoiceRenderService.Job>> jobs(@RequestHeader(value = HEADER, required = false) String k) {
        voice.requireWorker(k);
        return ApiResponse.ok(voice.jobs());
    }

    /** Máy trực sẵn hết giờ: báo trước khi tắt; result = số việc còn chờ (> 0 thì ở lại làm tiếp). */
    @PostMapping("/leaving")
    public ApiResponse<Integer> leaving(@RequestHeader(value = HEADER, required = false) String k) {
        voice.requireWorker(k);
        return ApiResponse.ok(voice.leaving());
    }

    @GetMapping(value = "/refs/{profile}", produces = "audio/wav")
    public byte[] ref(@RequestHeader(value = HEADER, required = false) String k, @PathVariable String profile) {
        voice.requireWorker(k);
        return voice.ref(profile);
    }

    @PostMapping(value = "/clips/{key}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> submit(@RequestHeader(value = HEADER, required = false) String k, @PathVariable String key,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "seconds", required = false) BigDecimal seconds) throws IOException {
        voice.requireWorker(k);
        voice.submit(key, file.getBytes(), seconds);
        return ApiResponse.ok(null);
    }

    @PostMapping("/clips/{key}/failed")
    public ApiResponse<Void> failed(@RequestHeader(value = HEADER, required = false) String k, @PathVariable String key,
                                    @RequestBody(required = false) Map<String, String> body) {
        voice.requireWorker(k);
        voice.fail(key, body == null ? null : body.get("error"));
        return ApiResponse.ok(null);
    }
}
