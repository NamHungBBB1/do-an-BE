package com.doan.game.service.impl;

import com.doan.game.enums.AiProviderKind;
import com.doan.game.service.AiKeyProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gemini: POST /v1beta/models/{model}:countTokens với đúng model BE đang dùng — miễn phí, không trừ lượt sinh, và báo 404
 * khi project của khoá không được dùng model đó (26/11: project mới gọi gemini-2.5-flash bị 404 dù liệt kê model vẫn 200).
 * Khoá đi trong header x-goog-api-key — không đặt trên URL để khỏi lọt vào log. OpenRouter: GET /api/v1/key (Bearer) trả
 * hạn mức và số dư của chính khoá đó (docs OpenRouter "Limits").
 */
@Component
public class HttpAiKeyProbe implements AiKeyProbe {

    private static final String PING = "{\"contents\":[{\"parts\":[{\"text\":\"ping\"}]}]}";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String geminiModel;

    public HttpAiKeyProbe(@Value("${app.ai.gemini-model:gemini-3.5-flash-lite}") String geminiModel) {
        this.geminiModel = geminiModel;
    }

    @Override
    public Result probe(AiProviderKind provider, String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(15));
        switch (provider) {
            case GEMINI -> b.uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel
                            + ":countTokens"))
                    .header("x-goog-api-key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(PING));
            case OPENROUTER -> b.uri(URI.create("https://openrouter.ai/api/v1/key"))
                    .header("Authorization", "Bearer " + key).GET();
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return classify(provider, r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.ERROR, "bị ngắt");
        } catch (Exception e) {
            return new Result(Outcome.ERROR, e.getClass().getSimpleName());
        }
    }

    Result classify(AiProviderKind provider, int status) {
        if (status == 200) {
            return new Result(Outcome.OK, "200");
        }
        if (status == 404 && provider == AiProviderKind.GEMINI) {
            return new Result(Outcome.INVALID, "HTTP 404: khoá này không dùng được model " + geminiModel);
        }
        Outcome o = status == 400 || status == 401 || status == 403 ? Outcome.INVALID
                : status == 402 || status == 429 ? Outcome.QUOTA : Outcome.ERROR;
        return new Result(o, "HTTP " + status);
    }
}
