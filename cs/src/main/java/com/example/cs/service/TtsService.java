package com.example.cs.service;

import com.example.cs.config.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

// ★ 语音合成服务（方案B）：调用阿里百炼 qwen3-tts 的 DashScope 原生接口
// 返回 JSON，output.audio.data 为 Base64 音频（wav），为空时回退下载 output.audio.url。
// key 未配置或调用失败时抛异常，由 Controller 转成 503，前端收到后自动降级回浏览器 TTS / 假朗读。
// ★ 引擎路由：openai 兼容 → /audio/speech；dashscope + 模型名含 realtime → WebSocket 事件流
//   （DashscopeRealtimeClient，返回 wav）；其余 → HTTP 一次性合成。
@Service
public class TtsService {

    // ★ key/url/模型运行时从 AI 配置读取（托盘"配置"面板可改，保存即生效）；音色/指令仍走 application.properties
    private final AiConfigService aiConfigService;
    private final DashscopeRealtimeClient realtimeClient;

    // 音色：Cherry/Ethan/Nofish/Jennifer/Ryan/Katerina/Elias 等，换音色只改这一行
    @Value("${tts.voice:Cherry}")
    private String voice;

    // 指令控制（instruct 系列专属）：用自然语言描述说话风格，留空不生效
    @Value("${tts.instructions:}")
    private String instructions;

    private final ObjectMapper mapper = new ObjectMapper();

    public TtsService(AiConfigService aiConfigService, DashscopeRealtimeClient realtimeClient) {
        this.aiConfigService = aiConfigService;
        this.realtimeClient = realtimeClient;
    }

    // ★ 带超时的 RestClient 工厂：连接 5s / 读取 30s —— 上游 TTS 服务挂起时及时失败，
    //   异常沿现有路径抛出 → Controller 503 → 前端降级链，不拖死请求线程
    private static RestClient timedClient() {
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(5))
                                .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory).build();
    }

    public byte[] synthesize(String text) {
        AiConfig.EndpointConfig t = aiConfigService.get().getTts();
        String provider = t.getProvider() == null || t.getProvider().isBlank() ? "dashscope" : t.getProvider();
        // ★ 上游限流/瞬时不可用短退避重试：qwen3-tts 在预取+串播的请求密度下会间歇性返回
        //   503/429（实测隔句失败），直接重试即可恢复；不重试会让前端降级到浏览器 TTS（声音突变）。
        //   只重试 429/5xx/网络异常，业务异常（密钥未配置、无音频数据）照旧直接抛。
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(500L * attempt); // 500ms / 1s 退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("TTS 重试被中断", ie);
                }
                System.out.println("[TTS] 上游失败(" + (last == null ? "?" : last.getMessage()) + ")，第" + (attempt + 1) + "次尝试...");
            }
            try {
                // ★ openai 兼容引擎返回 mp3（audio/mpeg），其余返回 wav —— 前端按 Content-Type 正常播放，无需区分
                if ("openai".equals(provider)) {
                    return synthesizeOpenAI(t, text);
                }
                // ★ realtime 系列模型只支持 WebSocket 事件流协议，走专用客户端（返回 wav，语义等同一次性合成）
                if (t.getModel() != null && t.getModel().toLowerCase().contains("realtime")) {
                    return realtimeClient.synthesize(t.getModel(), t.getApiKey(), t.getBaseUrl(), text);
                }
                return synthesizeDashscope(t, text);
            } catch (org.springframework.web.client.RestClientResponseException e) {
                int sc = e.getStatusCode().value();
                if (sc == 429 || sc >= 500) {
                    last = new IllegalStateException("上游 " + sc);
                    continue;
                }
                throw e; // 401/400 等业务错误不重试
            } catch (org.springframework.web.client.ResourceAccessException e) {
                last = new IllegalStateException("网络异常: " + e.getMessage());
                continue;
            } catch (RuntimeException e) {
                // ★ realtime WebSocket 路径的限流（1007 rate limit 等）抛的是普通 RuntimeException，
                //   上面两个 catch 捕不到；识别限流特征消息后同样退避重试，其余照旧直接抛
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("rate limit")) {
                    last = e;
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /** OpenAI 兼容 /audio/speech 协议：硅基流动(CosyVoice2/fish-speech)、OpenAI、Groq、Azure OpenAI 等通用 */
    private byte[] synthesizeOpenAI(AiConfig.EndpointConfig t, String text) {
        if (t.getApiKey() == null || t.getApiKey().isBlank()) {
            throw new IllegalStateException("TTS 密钥未配置，请在托盘'配置'面板中填写");
        }
        String base = t.getBaseUrl().trim().replaceAll("/+$", "");
        return timedClient().post()
                .uri(base + "/audio/speech")
                .header("Authorization", "Bearer " + t.getApiKey().trim())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", t.getModel().trim(),
                        "input", text,
                        "voice", t.getVoice() == null ? "alloy" : t.getVoice().trim(),
                        "response_format", "mp3"))
                .retrieve()
                .body(byte[].class);
    }

    /** DashScope 原生协议（qwen3-tts-instruct 系列） */
    private byte[] synthesizeDashscope(AiConfig.EndpointConfig t, String text) {
        String key = t.getApiKey();
        String url = t.getBaseUrl();
        String model = t.getModel();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("TTS 密钥未配置，请在托盘'配置'面板中填写");
        }
        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", t.getVoice() == null || t.getVoice().isBlank() ? voice : t.getVoice().trim());
        if (instructions != null && !instructions.isBlank()) {
            input.put("instructions", instructions);
        }
        String resp = timedClient().post()
                .uri(url)
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", input))
                .retrieve()
                .body(String.class);
        return extractAudio(resp);
    }

    // 解析 DashScope 响应：优先取 output.audio.data（Base64），为空则下载 output.audio.url
    private byte[] extractAudio(String resp) {
        try {
            JsonNode audio = mapper.readTree(resp).path("output").path("audio");
            String data = audio.path("data").asText("");
            if (!data.isBlank()) {
                return Base64.getDecoder().decode(data);
            }
            String audioUrl = audio.path("url").asText("");
            if (!audioUrl.isBlank()) {
                return timedClient().get().uri(URI.create(audioUrl)).retrieve().body(byte[].class);
            }
            throw new IllegalStateException("TTS 响应中无音频数据: " + resp);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 TTS 响应失败: " + e.getMessage(), e);
        }
    }
}
