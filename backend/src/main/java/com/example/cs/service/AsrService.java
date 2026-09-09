package com.example.cs.service;

import com.example.cs.config.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

// ★ 语音识别服务（方案B1）：调用阿里百炼 qwen3-asr-flash 的 OpenAI 兼容接口
// 前端录好 16kHz 单声道 wav 整段传来，这里转 Data URL 后走 chat/completions，
// 识别文本从 choices[0].message.content 取出。
// key 未配置或调用失败时抛异常，由 Controller 转成 503。
@Service
public class AsrService {

    // ★ key/url/模型运行时从 AI 配置读取（托盘"配置"面板可改，保存即生效）
    private final AiConfigService aiConfigService;

    private final ObjectMapper mapper = new ObjectMapper();

    public AsrService(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    // ★ 带超时的 RestClient 工厂：连接 5s / 读取 60s（ASR 整段音频识别较慢，读取放宽）。
    //   上游挂起时及时失败 → Controller 503 → 前端按既有降级逻辑处理，不拖死请求线程
    private static RestClient timedClient() {
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(5))
                                .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }

    public String transcribe(byte[] wav) {
        AiConfig.EndpointConfig a = aiConfigService.get().getAsr();
        String key = a.getApiKey();
        String url = a.getBaseUrl();
        String model = a.getModel();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ASR 密钥未配置，请在托盘'配置'面板中填写");
        }
        // Base64 音频用 Data URL 形式直传（qwen3-asr-flash 支持的两种输入之一）
        String dataUri = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        String resp = timedClient().post()
                .uri(url)
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "user", "content", List.of(
                                        Map.of("type", "input_audio",
                                               "input_audio", Map.of("data", dataUri))
                                ))
                        )
                ))
                .retrieve()
                .body(String.class);
        return extractText(resp);
    }

    private String extractText(String resp) {
        try {
            String text = mapper.readTree(resp).path("choices").path(0)
                    .path("message").path("content").asText("").trim();
            if (text.isBlank()) {
                throw new IllegalStateException("ASR 响应中无文本: " + resp);
            }
            return text;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 ASR 响应失败: " + e.getMessage(), e);
        }
    }
}
