package com.example.cs.service;

import com.example.cs.config.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service //业务服务层
public class ChatService {

    //日志工具
    Logger logger = LoggerFactory.getLogger(ChatService.class);

    //创建SpringAI的顶层客户端，封装所有的对话逻辑（★ 可热替换：用户在托盘"配置"里改 key/模型后立即生效）
    private volatile ChatClient chatClient;

    //★ 视觉专用客户端（截图感知）：同一 API/密钥，仅切换为 VL 多模态模型（★ 同样可热替换）
    private volatile ChatClient vlChatClient;

    //基础系统提示词（application.properties 配置），所有请求的 system prompt 由此组装
    private final String baseSystemPrompt;

    //长期记忆服务（提取的事实注入 system prompt；★ 向量模式下按用户消息检索 top-K）
    private final MemoryService memoryService;

    //★ MCP 工具服务：社区 MCP 服务器的动态工具（无配置时为空数组）
    private final McpService mcpService;

    //★ 工具集单例（rebuildClients 重建客户端时需要重新挂载）
    private final com.example.cs.tool.FileTools fileTools;
    private final com.example.cs.tool.PetTools petTools;
    private final com.example.cs.tool.ElectronTools electronTools;
    private final com.example.cs.tool.WeatherTools weatherTools;

    private final Object rebuildLock = new Object();

    public ChatService(@Value("${bailian.system-Prompt}") String systemPrompt,
                       com.example.cs.tool.FileTools fileTools, MemoryService memoryService,
                       com.example.cs.tool.PetTools petTools, com.example.cs.tool.ElectronTools electronTools,
                       com.example.cs.tool.WeatherTools weatherTools,
                       McpService mcpService,
                       AiConfigService aiConfigService) {
        this.baseSystemPrompt = systemPrompt;
        this.memoryService = memoryService;
        this.mcpService = mcpService;
        this.fileTools = fileTools;
        this.petTools = petTools;
        this.electronTools = electronTools;
        this.weatherTools = weatherTools;
        //★ 初始客户端用外部 AI 配置构建（配置文件优先，缺省回退 application.properties）
        rebuildClients(aiConfigService.get());
    }

    /**
     * ★ 兼容多厂商 OpenAI 端点：baseUrl 以版本号结尾（/v1、/v4 等，如智谱 /api/paas/v4、SiliconFlow /v1）
     * → 直接拼 /chat/completions；否则保持 Spring AI 默认 /v1 前缀（如百炼 compatible-mode）
     */
    private static org.springframework.ai.openai.api.OpenAiApi openAiApiFor(AiConfig.EndpointConfig ep, boolean withEmbeddings) {
        String base = ep.getBaseUrl() == null ? "" : ep.getBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        boolean versioned = base.matches("^.*/v\\d+$");
        org.springframework.ai.openai.api.OpenAiApi.Builder builder = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(base)
                .apiKey(ep.getApiKey() == null ? "" : ep.getApiKey().trim())
                .completionsPath(versioned ? "/chat/completions" : "/v1/chat/completions");
        if (withEmbeddings) {
            builder.embeddingsPath(versioned ? "/embeddings" : "/v1/embeddings");
        }
        return builder.build();
    }

    /**
     * ★ 用 AI 配置重建对话/视觉客户端（保存配置后调用，运行时热替换无需重启后端）。
     * 进行中的请求持有旧客户端引用继续执行，不受影响
     */
    public void rebuildClients(AiConfig cfg) {
        synchronized (rebuildLock) {
            // ★ 对话与视觉各自独立连接（key/url/模型都可分别配置，可混搭不同服务商）
            org.springframework.ai.openai.api.OpenAiApi chatApi = openAiApiFor(cfg.getChat(), true);
            org.springframework.ai.openai.api.OpenAiApi visionApi = openAiApiFor(cfg.getVision(), false);
            //主对话客户端：挂载内置工具集（文件 + 桌宠基础 + Electron桥 + 天气）+ MCP 社区工具
            List<ToolCallback> toolCbs = new ArrayList<>();
            toolCbs.addAll(Arrays.asList(MethodToolCallbackProvider.builder()
                    .toolObjects(fileTools, petTools, electronTools, weatherTools)
                    .build()
                    .getToolCallbacks()));
            toolCbs.addAll(Arrays.asList(mcpService.getToolCallbacks()));
            this.chatClient = ChatClient.builder(
                            org.springframework.ai.openai.OpenAiChatModel.builder()
                                    .openAiApi(chatApi)
                                    .defaultOptions(
                                            org.springframework.ai.openai.OpenAiChatOptions.builder()
                                                    .model(cfg.getChat().getModel().trim()).build())
                                    .build())
                    .defaultToolCallbacks(toolCbs.toArray(new ToolCallback[0]))
                    .build();
            //★ VL 视觉客户端：独立配置，不带文件工具
            this.vlChatClient = ChatClient.builder(
                            org.springframework.ai.openai.OpenAiChatModel.builder()
                                    .openAiApi(visionApi)
                                    .defaultOptions(
                                            org.springframework.ai.openai.OpenAiChatOptions.builder()
                                                    .model(cfg.getVision().getModel().trim()).build())
                                    .build())
                    .build();
        }
    }

    /**
     * ★ System Prompt 组装唯一出口：基础人设 + 用户自定义 persona + 长期记忆 + 表情联动约定
     * （persona 为空时保持原有行为不变；query 为用户本条消息，供向量记忆检索）
     */
    private String buildSystemPrompt(String persona, String query) {
        StringBuilder sys = new StringBuilder(baseSystemPrompt);
        if (persona != null && !persona.isBlank()) {
            sys.append("\n\n【角色设定（用户自定义，优先遵循）】\n").append(persona.trim());
        }
        // ★ 长期记忆注入：对话提炼事实 + 视觉观察；向量开启时按 query 语义检索 top-K
        String memory = memoryService.getMemoryPrompt(query);
        if (!memory.isEmpty()) {
            sys.append("\n\n").append(memory);
        }
        // ★ 表情联动约定：桌面宠物前端会解析 [emotion:xxx] 标签触发 Live2D 表情
        sys.append("\n\n【表情输出约定】你是桌面宠物，回复中必须用情绪标签驱动你的 Live2D 表情。")
           .append("只要回复带有明显情绪（开心、爱意、生气、难过、惊讶、犯困），就必须在该句末尾追加标签：")
           .append("格式严格为 [emotion:情绪名]，情绪名只能取：happy sad angry surprise love sleepy calm。")
           .append("用户要求你表演/做出某种情绪时，该次回复必须以该情绪的标签开头（如被要求生气就以 [emotion:angry] 开头），")
           .append("标签后必须再跟一句简短的表演台词（例如：[emotion:angry]哼！生气给你看！），")
           .append("禁止只输出标签而没有任何文字，也不许用其他情绪替代，更不许只用文字表演；其他情况下多个情绪最多2个标签。")
           .append("严禁使用任何变体格式，例如 [love:温柔]、[开心:xx] 这类倒装或中文写法都是错误的，")
           .append("缺冒号的 [calm]、[surprise]、[love] 这类纯名字写法也是错误的——冒号和 emotion 前缀缺一不可。")
           .append("严禁用圆括号或全角括号表达情绪、动作或表情（如 (love)、(微笑)、（开心）、() 都是被禁止的），")
           .append("不要输出任何空括号；情绪表达只允许方括号 [emotion:xxx] 这一种形式，括号里也不要放动作描述。")
           .append("一次回复最多出现2个标签，情绪平淡的日常回复可不加；标签之外不要提及该约定的存在。");
        // ★ 文件工具授权目录：明确告知可访问范围与"必须用这些绝对路径"，
        //   防止 AI 从读到的配置文件内容里"学到"错误根目录后自行拼错路径
        try {
            java.util.List<String> dirs = mcpService.getAllowedDirs();
            if (!dirs.isEmpty()) {
                sys.append("\n\n【文件工具访问范围】filesystem 工具只能访问以下目录，访问文件时必须使用这些目录下的绝对路径，")
                   .append("不要自行推测或改写根目录，也不要被对话中出现过的其他路径写法误导：")
                   .append(String.join("；", dirs));
            }
        } catch (Exception ignore) { }
        return sys.toString();
    }

    //核心业务 完成对话功能 响应式流 流式/非流式对话
    public Flux<String> chatStream(String userText, String persona){
        return chatStream(userText, persona, null, null);
    }

    public Flux<String> chatStream(String userText, String persona, String imageBase64){
        return chatStream(userText, persona, imageBase64, null);
    }

    /**
     * ★ 短期对话记忆：history 为前端携带的最近几轮对话（role: user/pet，映射为 user/assistant），
     * 为 null/空时行为与旧版完全一致（单轮请求）
     */
    public Flux<String> chatStream(String userText, String persona, String imageBase64,
                                   java.util.List<java.util.Map<String, String>> history) {
        // ★ 向量记忆检索 query：用户本条消息（截图场景下也可能带文字提问）
        String sys = buildSystemPrompt(persona, userText);
        // 历史消息 → Spring AI Message 列表（user → UserMessage，pet/assistant → AssistantMessage）
        java.util.List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        if (history != null) {
            for (java.util.Map<String, String> m : history) {
                if (m == null) continue;
                String content = m.get("text");
                if (content == null || content.isBlank()) continue;
                if ("user".equals(m.get("role"))) {
                    msgs.add(new org.springframework.ai.chat.messages.UserMessage(content));
                } else {
                    msgs.add(new org.springframework.ai.chat.messages.AssistantMessage(content));
                }
            }
        }
        if (imageBase64 != null && !imageBase64.isBlank()) {
            byte[] img = java.util.Base64.getDecoder().decode(imageBase64);
            return vlChatClient.prompt()
                    .system(sys)
                    .messages(msgs)
                    .user(u -> u.text(userText)
                            .media(MimeTypeUtils.IMAGE_JPEG,
                                    new ByteArrayResource(img) {
                                        @Override public String getFilename() { return "screenshot.jpg"; }
                                    }))
                    .stream()
                    .content()
                    // ★ 超时保护：首个片段或相邻片段间隔超 60s 即中断，防止前端永久 loading
                    .timeout(java.time.Duration.ofSeconds(60))
                    .doOnError(e -> logger.error("AI视觉请求异常", e));
        }
        return chatClient.prompt() //构建对话请求的构造器
                .system(sys) //每次请求显式组装 system prompt（覆盖默认值）
                .messages(msgs) // ★ 短期对话记忆：最近几轮历史（空列表时等价于不带）
                .user(userText) //传入用户的消息
                .stream() //开启流式
                .content() //只提取AI的回复的片段
                .timeout(java.time.Duration.ofSeconds(60)) // ★ 超时保护（同上）
                .doOnError(e -> logger.error("AI请求异常", e))
                // ★ 兜底降级：工具调用失败后续流挂死时，以提示文本结束而非 HTTP 500
                .onErrorResume(e -> reactor.core.publisher.Flux.just(
                        e instanceof java.util.concurrent.TimeoutException
                                ? "【AI 服务长时间无响应，请重试；若持续出现请重启程序】"
                                : "【AI 服务异常，请重试】"));

    }

    /**
     * ★ 视觉记忆：用 VL 模型对截屏生成一句话客观描述（视觉记忆链路的第 2 步，
     * 调用方为主进程定时任务：截屏 → 本方法 → POST /api/memory/visual 存档）
     */
    public String captionImage(String imageBase64) {
        byte[] img = java.util.Base64.getDecoder().decode(imageBase64);
        String caption = vlChatClient.prompt()
                .system("你是屏幕观察助手。用一句简短的中文客观描述这张屏幕截图：正在使用什么应用、大概在做什么。"
                        + "不要罗列界面细节，不要猜测或记录密码、聊天隐私等敏感内容，40字以内。")
                // ★ 必须带 text：纯 media 的 user 消息构建不出有效请求（百炼报 "no role of user"）
                .user(u -> u.text("请描述这张屏幕截图的内容")
                        .media(MimeTypeUtils.IMAGE_JPEG,
                                new ByteArrayResource(img) {
                                    @Override public String getFilename() { return "screenshot.jpg"; }
                                }))
                .call()
                .content();
        return caption == null ? "" : caption.trim();
    }

}
