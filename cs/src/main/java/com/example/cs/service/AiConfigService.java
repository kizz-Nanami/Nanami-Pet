package com.example.cs.service;

import com.example.cs.config.AiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;

/**
 * ★ AI 服务配置管理：外部持久化到 ~/.cyberpet-ai.json（打包后依然可写），
 * 文件不存在/损坏/字段缺失时回退 application.properties 内置默认值。
 * 四类服务（对话/视觉/TTS/语音识别）各自独立 key/url/模型名
 */
@Service
public class AiConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AiConfigService.class);
    private final File configFile = new File(System.getProperty("user.home"), ".cyberpet-ai.json");
    private final ObjectMapper mapper = new ObjectMapper();

    // -- 内置默认值（外部配置缺失时的回退来源） --
    @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String defChatUrl;
    @Value("${spring.ai.openai.api-key:}")
    private String defChatKey;
    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String defChatModel;
    @Value("${bailian.vl-model:qwen-vl-plus}")
    private String defVisionModel;
    @Value("${tts.api-key:}")
    private String defTtsKey;
    @Value("${tts.url:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}")
    private String defTtsUrl;
    @Value("${tts.model:qwen3-tts-instruct-flash}")
    private String defTtsModel;
    @Value("${asr.api-key:}")
    private String defAsrKey;
    @Value("${asr.url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String defAsrUrl;
    @Value("${asr.model:qwen3-asr-flash}")
    private String defAsrModel;
    @Value("${asr.realtime-model:paraformer-realtime-v2}")
    private String defAsrRealtimeModel;
    @Value("${tts.voice:Cherry}")
    private String defTtsVoice;
    @Value("${memory.embedding-model:text-embedding-v3}")
    private String defEmbeddingModel;

    private AiConfig current;

    @PostConstruct
    public void load() {
        AiConfig def = defaults();
        if (configFile.exists()) {
            try {
                AiConfig loaded = mapper.readValue(configFile, AiConfig.class);
                // 各组字段缺失时用默认值补齐（防手改文件损坏；旧版平铺结构因未知字段会走异常分支回退默认）
                loaded.getChat().fillMissing(def.getChat().getBaseUrl(), def.getChat().getApiKey(), def.getChat().getModel());
                loaded.getVision().fillMissing(def.getVision().getBaseUrl(), def.getVision().getApiKey(), def.getVision().getModel());
                loaded.getTts().fillMissing(def.getTts().getBaseUrl(), def.getTts().getApiKey(), def.getTts().getModel());
                loaded.getAsr().fillMissing(def.getAsr().getBaseUrl(), def.getAsr().getApiKey(), def.getAsr().getModel());
                if (loaded.getAsr().getRealtimeModel() == null || loaded.getAsr().getRealtimeModel().isBlank())
                    loaded.getAsr().setRealtimeModel(def.getAsr().getRealtimeModel());
                if (loaded.getTts().getProvider() == null || loaded.getTts().getProvider().isBlank())
                    loaded.getTts().setProvider("dashscope");
                if (loaded.getTts().getVoice() == null || loaded.getTts().getVoice().isBlank())
                    loaded.getTts().setVoice(defTtsVoice);
                // ★ 向量记忆：旧配置文件无 embeddingModel 字段时补默认（留空由用户显式禁用）
                if (loaded.getChat().getEmbeddingModel() == null)
                    loaded.getChat().setEmbeddingModel(defEmbeddingModel);
                current = loaded;
                logger.info("已加载外部 AI 配置: {}", configFile.getAbsolutePath());
                return;
            } catch (Exception e) {
                logger.warn("外部 AI 配置读取失败，使用内置默认: {}", e.getMessage());
            }
        }
        current = def;
    }

    private AiConfig defaults() {
        AiConfig def = new AiConfig();
        def.getChat().fillMissing(defChatUrl, defChatKey, defChatModel);
        def.getVision().fillMissing(defChatUrl, defChatKey, defVisionModel);
        // TTS/ASR 密钥留空时自动复用对话密钥（application.properties 里的约定保持不变）
        def.getTts().fillMissing(defTtsUrl, defTtsKey.isBlank() ? defChatKey : defTtsKey, defTtsModel);
        def.getAsr().fillMissing(defAsrUrl, defAsrKey.isBlank() ? defChatKey : defAsrKey, defAsrModel);
        def.getAsr().setRealtimeModel(defAsrRealtimeModel);
        def.getTts().setProvider("dashscope");
        def.getTts().setVoice(defTtsVoice);
        def.getChat().setEmbeddingModel(defEmbeddingModel);
        return def;
    }

    public synchronized AiConfig get() { return current; }

    /** 保存并落盘（调用方负责随后触发各服务的客户端热替换） */
    public synchronized AiConfig update(AiConfig cfg) {
        current = cfg;
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, cfg);
            logger.info("AI 配置已保存: {}", configFile.getAbsolutePath());
        } catch (Exception e) {
            logger.error("AI 配置落盘失败", e);
        }
        return cfg;
    }

    /** 测试连接（不落盘）：用传入的对话配置发一次最小请求，返回给前端的可读结果 */
    public String testConnection(AiConfig cfg) {
        try {
            AiConfig.EndpointConfig c = cfg.getChat();
            // baseUrl 以 /v数字 结尾（如智谱 /api/paas/v4）→ 直接拼 /chat/completions；否则默认 /v1 前缀
            String base = c.getBaseUrl().trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            boolean versioned = base.matches("^.*/v\\d+$");
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(base)
                    .apiKey(c.getApiKey() == null ? "" : c.getApiKey().trim())
                    .completionsPath(versioned ? "/chat/completions" : "/v1/chat/completions")
                    .build();
            ChatClient client = ChatClient.builder(OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(OpenAiChatOptions.builder().model(c.getModel().trim()).build())
                            .build())
                    .build();
            String reply = client.prompt().user("请只回复两个字：成功").call().content();
            if (reply == null || reply.isBlank()) return "接口通了，但没有返回内容";
            return "连接成功，模型回复：" + reply;
        } catch (Exception e) {
            return "连接失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
