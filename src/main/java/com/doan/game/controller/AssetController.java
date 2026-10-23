package com.doan.game.controller;

import com.doan.game.DTO.request.UpdateAssetRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.AssetResponse;
import com.doan.game.DTO.response.AssetUploadResponse;
import com.doan.game.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Kho ảnh trong Studio (24/10, đợt 2a): cổng /api/studio mở cho 4 vai, ai ghi được thì service tra AccountRole. */
@RestController
@RequestMapping("/api/studio/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public ApiResponse<List<AssetResponse>> list(@RequestParam(required = false) String kind,
                                                 @RequestParam(required = false) String chapter,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String q) {
        return ApiResponse.ok(assetService.list(kind, chapter, status, q));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(assetService.get(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AssetUploadResponse> upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file,
                                                   @RequestParam(required = false) String label,
                                                   @RequestParam(required = false) String chapterCode) throws IOException {
        return ApiResponse.ok(assetService.upload(currentAccountId(jwt), file.getOriginalFilename(), file.getBytes(),
                label, chapterCode));
    }

    @PutMapping("/{id}")
    public ApiResponse<AssetResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @RequestBody UpdateAssetRequest req) {
        return ApiResponse.ok(assetService.update(currentAccountId(jwt), id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        assetService.delete(currentAccountId(jwt), id);
        return ApiResponse.ok();
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
