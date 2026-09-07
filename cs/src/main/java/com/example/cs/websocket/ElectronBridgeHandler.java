package com.example.cs.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ★ Electron 工具桥：AI 工具（打开网页/应用等需要操作电脑的动作）经此通道
 *   由 Electron 前端执行——后端工具方法发请求 → 桥转发 → 前端执行 → 回传结果。
 * 协议：
 *   下行 {id, action, args}  请求前端执行
 *   上行 {id, ok, result}    前端回传执行结果
 */
@Component
public class ElectronBridgeHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ElectronBridgeHandler.class);
    private static final long EXECUTE_TIMEOUT_SEC = 10;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Object sendLock = new Object(); // ★ Tomcat WebSocket 并发写同一 session 会抛异常，发送必须串行化
    private volatile WebSocketSession session;

    /** 工具方法调用入口：发请求给前端并同步等待结果（超时返回失败描述） */
    public String execute(String action, Map<String, ?> args) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) return "Electron 桥未连接（桌宠窗口未运行），无法执行该操作";
        String id = String.valueOf(seq.incrementAndGet());
        CompletableFuture<String> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (sendLock) {
                s.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("id", id, "action", action, "args", args))));
            }
            return f.get(EXECUTE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "工具执行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        log.info("Electron 桥已连接: {}", s.getId());
        this.session = s;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, org.springframework.web.socket.CloseStatus status) {
        if (this.session == s) {
            log.info("Electron 桥已断开: {}", s.getId());
            this.session = null;
        }
        // 断开时把未完成的请求全部以失败收尾，避免工具方法干等超时
        pending.forEach((id, f) -> f.complete("Electron 桥断开，操作未完成"));
        pending.clear();
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            String id = node.path("id").asText("");
            CompletableFuture<String> f = pending.remove(id);
            if (f != null) {
                String result = node.path("result").asText("执行完成");
                f.complete(result);
            }
        } catch (Exception e) {
            log.warn("Electron 桥消息解析失败: {}", message.getPayload(), e);
        }
    }
}
