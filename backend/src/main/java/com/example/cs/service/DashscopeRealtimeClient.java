package com.example.cs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * ★ DashScope qwen3-tts-*-realtime 系列专用合成客户端（方案A包装）。
 * realtime 模型不支持 HTTP 一次性合成端点（multimodal-generation），只支持
 * WebSocket 事件流协议（wss://.../api-ws/v1/realtime）。本客户端对每句文本：
 * 建立一次 WS 连接 → session.update（音色/格式）→ input_text_buffer.append（送文本）
 * → 收齐全部 response.audio.delta（base64 PCM 块）→ response.done 收尾 →
 * 拼接 PCM 手工封装 44 字节标准 WAV 头，返回完整 WAV 字节。
 * 对上层（TtsService/前端）语义等同一次性合成；任何失败抛异常 → Controller 转 503 →
 * 前端走现有三级降级链，不会无声或打断使用。
 */
@Service
public class DashscopeRealtimeClient {

    private static final Logger logger = LoggerFactory.getLogger(DashscopeRealtimeClient.class);

    // 音色与 application.properties 保持同源（realtime 系列支持 Cherry 等 system voices）
    @Value("${tts.voice:Cherry}")
    private String voice;

    // 指令控制仅 Qwen3-TTS-Instruct-Flash-Realtime 系列支持，普通 realtime 模型传了会报错
    @Value("${tts.instructions:}")
    private String instructions;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 合成一句文本，返回标准 WAV（24000Hz/16bit/mono）。
     *
     * @param model   realtime 模型名（如 qwen3-tts-flash-realtime-2025-09-18）
     * @param apiKey  DashScope API Key
     * @param baseUrl 配置面板里的 HTTP 合成端点 —— 仅用来推导 WS 域名（国内/国际站自适应）
     * @param text    待合成文本
     */
    public byte[] synthesize(String model, String apiKey, String baseUrl, String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TTS 密钥未配置，请在托盘'配置'面板中填写");
        }
        CompletableFuture<byte[]> result = new CompletableFuture<>();

        WebSocket.Listener listener = new WebSocket.Listener() {
            final StringBuilder pending = new StringBuilder();     // WS 大消息会被分片，攒齐再解析
            final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

            @Override
            public void onOpen(WebSocket ws) {
                logger.info("[TTS-RT] 连接已建立");
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                pending.append(data);
                if (last) {
                    try {
                        handleEvent(ws, pending.toString(), pcm, result);
                    } catch (Exception e) {
                        result.completeExceptionally(e);
                    }
                    pending.setLength(0);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                // 服务端未给 response.done 就关闭：视为失败，防止上层永久等待
                if (!result.isDone()) {
                    result.completeExceptionally(
                            new IllegalStateException("TTS 实时连接提前关闭: " + statusCode + " " + reason));
                }
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                result.completeExceptionally(
                        new IllegalStateException("TTS 实时连接错误: " + error.getMessage(), error));
            }
        };

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + apiKey.trim())
                .buildAsync(URI.create(resolveWsUrl(baseUrl, model)), listener)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        result.completeExceptionally(
                                new IllegalStateException("TTS 实时连接建立失败: " + err.getMessage(), err));
                        return;
                    }
                    try {
                        // 连接就绪后按序发送：会话配置 → 整句文本 → 显式提交触发合成
                        ws.sendText(sessionUpdate(model), true)
                                .thenCompose(w -> w.sendText(appendText(text), true))
                                .thenCompose(w -> w.sendText(commitText(), true));
                    } catch (Exception e) {
                        result.completeExceptionally(
                                new IllegalStateException("TTS 实时请求发送失败: " + e.getMessage(), e));
                    }
                });

        try {
            // 句级合成超时保护：超时/失败都走异常 → 503 → 前端降级，且异常后关闭连接防泄漏
            return result
                    .orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((r, e) -> { /* 关闭在 handleEvent/onClose 内完成 */ })
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    /** 从 HTTP 合成端点推导 realtime WS 端点：取 host 拼 wss（国内/国际站自适应），默认国内站 */
    private String resolveWsUrl(String baseUrl, String model) {
        String host = "dashscope.aliyuncs.com";
        try {
            URI u = URI.create(baseUrl == null ? "" : baseUrl.trim());
            if (u.getHost() != null && !u.getHost().isBlank()) host = u.getHost();
        } catch (Exception ignore) { /* baseUrl 异常时用默认域名 */ }
        return "wss://" + host + "/api-ws/v1/realtime?model=" + URLEncoder.encode(model, StandardCharsets.UTF_8);
    }

    /** session.update：音色 + 裸 PCM 24000Hz（流式 delta 拼接最稳妥），Instruct-Realtime 系列附加 instructions */
    private String sessionUpdate(String model) {
        try {
            Map<String, Object> session = new HashMap<>();
            session.put("voice", voice == null || voice.isBlank() ? "Cherry" : voice.trim());
            // ★ 官方协议字段名是 response_format（此前误用 format）
            session.put("response_format", "pcm");
            session.put("sample_rate", 24000);
            if (model != null && model.toLowerCase().contains("instruct")
                    && instructions != null && !instructions.isBlank()) {
                session.put("instructions", instructions.trim());
            }
            // ★ 协议为 OpenAI Realtime 风格：消息类型字段是 "type"（此前误用 "event"，服务端报 Invalid message type）
            return mapper.writeValueAsString(Map.of("type", "session.update", "session", session));
        } catch (Exception e) {
            throw new IllegalStateException("构造 session.update 失败", e);
        }
    }

    /** input_text_buffer.append：整句文本一次送入（text 在消息顶层，嵌套写法服务端收不到） */
    private String appendText(String text) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "type", "input_text_buffer.append",
                    "text", text));
        } catch (Exception e) {
            throw new IllegalStateException("构造文本消息失败", e);
        }
    }

    /**
     * input_text_buffer.commit：显式提交触发合成。
     * ★ 官方文档明确 response.created/response.audio.delta 在服务端收到 commit 事件后才产生；
     * server_commit 模式的自动时机不可控（为流式输入设计），一次性整句必须显式 commit
     */
    private String commitText() {
        return "{\"type\":\"input_text_buffer.commit\"}";
    }

    /** 按事件分发：音频块累积 / 完成收尾 / 错误上抛，未知事件静默忽略 */
    private void handleEvent(WebSocket ws, String json, ByteArrayOutputStream pcm, CompletableFuture<byte[]> result) {
        try {
            // ★ 诊断日志：记录服务端每个下行事件（音频块截断，避免刷屏）
            logger.info("[TTS-RT] 下行事件: {}", json.length() > 200 ? json.substring(0, 200) + "..." : json);
            JsonNode node = mapper.readTree(json);
            String event = node.path("event").asText(node.path("type").asText(""));
            switch (event) {
                case "response.audio.delta" -> {
                    String b64 = node.path("delta").asText(node.path("audio").asText(""));
                    if (!b64.isBlank()) pcm.writeBytes(Base64.getDecoder().decode(b64));
                }
                case "response.done" -> {
                    if (pcm.size() == 0) {
                        result.completeExceptionally(new IllegalStateException("TTS 实时合成未返回音频数据"));
                    } else {
                        result.complete(pcmToWav(pcm.toByteArray(), 24000, 1, 16));
                    }
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                }
                case "error", "session.error" -> {
                    String msg = node.path("error").path("message").asText("");
                    if (msg.isBlank()) msg = json;
                    result.completeExceptionally(new IllegalStateException("TTS 实时合成失败: " + msg));
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "error");
                }
                default -> { /* session.created / session.updated / response.audio.done 等忽略 */ }
            }
        } catch (Exception e) {
            result.completeExceptionally(new IllegalStateException("解析 TTS 实时事件失败: " + e.getMessage(), e));
        }
    }

    /** 裸 PCM → 标准 WAV（44 字节 RIFF 头），前端 Audio 按 audio/wav 直接播放 */
    private byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        ByteBuffer buf = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + pcm.length);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);                                  // fmt 块长度
        buf.putShort((short) 1);                         // PCM 格式
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(pcm.length);
        buf.put(pcm);
        return buf.array();
    }
}
