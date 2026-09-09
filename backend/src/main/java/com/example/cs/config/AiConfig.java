package com.example.cs.config;

/**
 * ★ AI 服务配置（托盘"配置"面板，用户可分别配置四类服务的 key/url/模型名）
 * chat 对话 / vision 视觉识图 / tts 语音合成 / asr 语音识别，各自独立 EndpointConfig
 */
public class AiConfig {
    private EndpointConfig chat = new EndpointConfig();
    private EndpointConfig vision = new EndpointConfig();
    private EndpointConfig tts = new EndpointConfig();
    private EndpointConfig asr = new EndpointConfig();

    /** 单个服务的连接配置；provider/voice 仅 TTS 使用（engine: dashscope/openai/edge），realtimeModel 仅 asr 使用，
     *  embeddingModel 仅 chat 组使用（向量记忆，留空=禁用向量检索回退全量注入） */
    public static class EndpointConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String realtimeModel;
        private String provider;
        private String voice;
        private String embeddingModel;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getRealtimeModel() { return realtimeModel; }
        public void setRealtimeModel(String realtimeModel) { this.realtimeModel = realtimeModel; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

        /** 必填字段缺失时用默认值补齐 */
        public void fillMissing(String defUrl, String defKey, String defModel) {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = defUrl;
            if (apiKey == null) apiKey = defKey;
            if (model == null || model.isBlank()) model = defModel;
        }
    }

    public EndpointConfig getChat() { return chat; }
    public void setChat(EndpointConfig chat) { this.chat = chat; }
    public EndpointConfig getVision() { return vision; }
    public void setVision(EndpointConfig vision) { this.vision = vision; }
    public EndpointConfig getTts() { return tts; }
    public void setTts(EndpointConfig tts) { this.tts = tts; }
    public EndpointConfig getAsr() { return asr; }
    public void setAsr(EndpointConfig asr) { this.asr = asr; }
}
