package com.example.cs.config;

import com.example.cs.websocket.AsrWebSocketHandler;
import com.example.cs.websocket.ElectronBridgeHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// ★ WebSocket 配置：/ws/asr（实时语音识别桥接）+ /ws/electron（工具执行桥）
// Electron 渲染进程 origin 是 file://，故放行所有来源
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrWebSocketHandler asrWebSocketHandler;
    private final ElectronBridgeHandler electronBridgeHandler;
    public WebSocketConfig(AsrWebSocketHandler asrWebSocketHandler, ElectronBridgeHandler electronBridgeHandler) {
        this.asrWebSocketHandler = asrWebSocketHandler;
        this.electronBridgeHandler = electronBridgeHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrWebSocketHandler, "/ws/asr").setAllowedOrigins("*");
        registry.addHandler(electronBridgeHandler, "/ws/electron").setAllowedOrigins("*");
    }
}
