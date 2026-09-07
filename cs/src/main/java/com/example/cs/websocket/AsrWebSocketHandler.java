package com.example.cs.websocket;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.example.cs.config.AiConfig;
import com.example.cs.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ★ 实时语音识别桥接（方案B2核心）：前端WS ↔ 百炼 Recognition 互转
// 上行：前端文本指令(start/stop/abort) + 二进制 PCM 帧 → sendAudioFrame 转发百炼
// 下行：百炼回调 onEvent(partial/final) → 序列化为 JSON 推回前端
// 每个前端连接对应一个独立的百炼识别任务（State）
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    // ★ key/实时模型运行时从 AI 配置读取（托盘"配置"面板可改，下次开始识别即生效）
    private final AiConfigService aiConfigService;

    public AsrWebSocketHandler(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    private final ObjectMapper mapper = new ObjectMapper();

    // 会话状态：sessionKey → 桥接状态
    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static class State {
        volatile Recognition recognizer;      // 百炼识别任务（call 后生效）
        final StringBuilder finalText = new StringBuilder(); // 各句 final 结果累积
    }

    // -- 生命周期 --

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(states.remove(key(session)));
    }

    // -- 文本消息：控制指令 --

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String type;
        try {
            type = mapper.readTree(message.getPayload()).path("type").asText("");
        } catch (Exception e) {
            sendError(session, "无法解析指令");
            return;
        }
        switch (type) {
            case "start" -> startTask(session);
            case "stop" -> stopTask(session);
            case "abort" -> cleanup(states.remove(key(session)));
            default -> sendError(session, "未知指令: " + type);
        }
    }

    // -- 二进制消息：音频帧转发（Int16LE PCM 16kHz 单声道） --

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        State st = states.get(key(session));
        if (st == null || st.recognizer == null) return;
        // 复制到堆上 ByteBuffer，避免 direct buffer 引发 SDK 内部 array() 访问问题
        ByteBuffer src = message.getPayload();
        ByteBuffer frame = ByteBuffer.allocate(src.remaining());
        frame.put(src);
        frame.flip();
        try {
            st.recognizer.sendAudioFrame(frame);
        } catch (Exception e) {
            sendError(session, "音频帧发送失败: " + e.getMessage());
        }
    }

    // -- 任务控制 --

    private void startTask(WebSocketSession session) {
        String key = key(session);
        if (states.containsKey(key)) {
            send(session, Map.of("type", "error", "message", "任务已在进行中"));
            return;
        }
        AiConfig.EndpointConfig a = aiConfigService.get().getAsr();
        String key0 = a.getApiKey();
        String realtimeModel = a.getRealtimeModel();
        if (key0 == null || key0.isBlank()) {
            sendError(session, "ASR 密钥未配置，请在托盘'配置'面板中填写");
            return;
        }
        State st = new State();
        states.put(key, st);

        RecognitionParam param = RecognitionParam.builder()
                .apiKey(key0)
                .model(realtimeModel)
                .format("pcm")
                .sampleRate(16000)
                .build();
        Recognition recognizer = new Recognition();
        st.recognizer = recognizer;

        ResultCallback<RecognitionResult> callback = new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result.getSentence() == null) return;
                String text = result.getSentence().getText();
                if (text == null || text.isBlank()) return;
                if (result.isSentenceEnd()) {
                    // 一句话定稿：累积到整段文本
                    st.finalText.append(text);
                }
                // 中间结果实时推给前端刷新气泡（句子定稿也先以 partial 形式展示）
                send(session, Map.of("type", "partial", "text", text));
            }

            @Override
            public void onComplete() {
                // stop() 后触发：整段识别结束，返回最终文本
                send(session, Map.of("type", "final", "text", st.finalText.toString()));
                states.remove(key(session));
            }

            @Override
            public void onError(Exception e) {
                sendError(session, "识别失败: " + e.getMessage());
                cleanup(states.remove(key(session)));
            }
        };

        try {
            recognizer.call(param, callback);
        } catch (Exception e) {
            states.remove(key);
            cleanup(st);
            sendError(session, "启动识别任务失败: " + e.getMessage());
            return;
        }
        // 任务发起成功：通知前端可以开始逐帧发送音频（前端收到 ready 前数据只攒不发）
        send(session, Map.of("type", "ready"));
    }

    private void stopTask(WebSocketSession session) {
        State st = states.get(key(session));
        if (st == null || st.recognizer == null) {
            sendError(session, "没有进行中的识别任务");
            return;
        }
        try {
            // 阻塞直到百炼回调 onComplete（onComplete 里发送 final 并清理状态）
            st.recognizer.stop();
        } catch (Exception e) {
            sendError(session, "结束任务失败: " + e.getMessage());
            cleanup(states.remove(key(session)));
        }
    }

    private void cleanup(State st) {
        if (st == null) return;
        Recognition rec = st.recognizer;
        st.recognizer = null;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignore) { /* 已结束则忽略 */ }
        }
    }

    // -- 工具 --

    private String key(WebSocketSession session) {
        return session.getId();
    }

    // WebSocketSession 非线程安全：百炼回调线程与容器线程并发发送需加锁
    private void send(WebSocketSession session, Map<String, ?> payload) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (Exception ignore) { /* 连接已断等场景 */ }
    }

    private void sendError(WebSocketSession session, String message) {
        send(session, Map.of("type", "error", "message", message == null ? "" : message));
    }
}
