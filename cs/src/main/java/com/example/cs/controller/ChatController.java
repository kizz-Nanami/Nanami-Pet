package com.example.cs.controller;

import com.example.cs.config.AiConfig;
import com.example.cs.service.AiConfigService;
import com.example.cs.service.ChatService;
import com.example.cs.service.MemoryService;
import com.example.cs.service.TtsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    //把service层的方法注入到controller
    private final ChatService chatService;
    private final TtsService ttsService;
    private final MemoryService memoryService;
    private final AiConfigService aiConfigService;
    public ChatController(ChatService chatService, TtsService ttsService, MemoryService memoryService,
                          AiConfigService aiConfigService) {
        this.chatService = chatService;
        this.ttsService = ttsService;
        this.memoryService = memoryService;
        this.aiConfigService = aiConfigService;
    }

    //★ AI 服务配置：回显当前配置（托盘"配置"面板用）
    @GetMapping("/api/ai-config")
    public AiConfig getAiConfig() {
        return aiConfigService.get();
    }

    //★ AI 服务配置：保存 + 运行时热替换对话/视觉/记忆提炼客户端（无需重启后端）
    @PostMapping("/api/ai-config")
    public Map<String, Object> updateAiConfig(@RequestBody AiConfig cfg) {
        aiConfigService.update(cfg);
        chatService.rebuildClients(aiConfigService.get());
        memoryService.rebuildClient(aiConfigService.get());
        return Map.of("ok", true);
    }

    //★ AI 服务配置：测试连接（不落盘，用传入配置发一次最小请求）
    @PostMapping("/api/ai-config/test")
    public Map<String, Object> testAiConfig(@RequestBody AiConfig cfg) {
        return Map.of("message", aiConfigService.testConnection(cfg));
    }

    //Flux<String> 响应式字符流   返回一个纯字符的响应式流
    @PostMapping(value = "/api/text-chat",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> textChat(@RequestBody Map<String,Object> map) {
        String text = str(map.get("text"));
        String persona = str(map.get("persona")); // ★ 用户自定义角色人设（可为空）
        String image = str(map.get("image"));     // ★ 截屏视觉感知：JPEG base64（可为空，非空走 VL 视觉模型）
        // ★ 短期对话记忆：前端携带的最近几轮历史（可为空，兼容旧客户端不带此字段）
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history = (List<Map<String, String>>) map.get("history");
        return chatService.chatStream(text, persona, image, history).concatWithValues("[DONE]");
    }

    private String str(Object v) { return v == null ? null : String.valueOf(v); }

    // ★ 长期记忆：前端对话更新时同步完整对话（含删除），后端哈希去重 + 防抖提炼
    @PostMapping("/api/memory/sync")
    public ResponseEntity<Void> memorySync(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        if (messages != null) {
            memoryService.syncConversation(messages);
        }
        return ResponseEntity.ok().build();
    }

    // ★ 视觉记忆第 1 步：截屏 → VL 模型生成一句话描述（主进程定时任务调用）
    @PostMapping("/api/vision-caption")
    public Map<String, Object> visionCaption(@RequestBody Map<String, String> body) {
        String image = body.get("image");
        if (image == null || image.isBlank()) {
            return Map.of("error", "image is required");
        }
        try {
            String caption = chatService.captionImage(image);
            return Map.of("text", caption);
        } catch (Exception e) {
            logger.error("视觉描述生成失败: {}", e.getMessage());
            return Map.of("error", e.getMessage() == null ? "caption failed" : e.getMessage());
        }
    }

    // ★ 视觉记忆第 2 步：描述文本存档（带时间戳，向量化后参与长期记忆检索）
    @PostMapping("/api/memory/visual")
    public ResponseEntity<Void> memoryVisual(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text != null && !text.isBlank()) {
            memoryService.addVisualFact(text);
        }
        return ResponseEntity.ok().build();
    }

    // ★ 彻底清除全部记忆（对话事实/向量/视觉观察/快照），由托盘"清除全部记忆与聊天记录"调用
    @PostMapping("/api/memory/clear")
    public ResponseEntity<Void> memoryClear() {
        memoryService.clearAll();
        return ResponseEntity.ok().build();
    }

    // ★ 语音合成接口（方案B）：文本 → wav 二进制（qwen3-tts 输出 wav）
    // 失败（key 未配/网络错/余额不足）统一返回 503，前端自动降级回浏览器 TTS
    @PostMapping(value = "/api/tts", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> tts(@RequestBody Map<String,String> map) {
        String text = map.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            byte[] audio = ttsService.synthesize(text.trim());
            // 引擎为 openai 兼容协议时返回 mp3，DashScope 返回 wav —— 前端按 Content-Type 播放
            String engine = aiConfigService.get().getTts().getProvider();
            MediaType mt = "openai".equals(engine) ? MediaType.parseMediaType("audio/mpeg")
                    : MediaType.parseMediaType("audio/wav");
            return ResponseEntity.ok()
                    .contentType(mt)
                    .body(audio);
        } catch (Exception e) {
            // ★ 失败时打日志（之前异常被吞无迹可循），响应体带原因供 curl/前端排查；前端按状态码降级不受影响
            logger.error("TTS 合成失败: {}", e.getMessage(), e);
            return ResponseEntity.status(503).body(("TTS失败: " + e.getMessage()).getBytes());
        }
    }
}
