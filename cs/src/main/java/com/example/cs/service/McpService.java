package com.example.cs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ MCP 工具服务：接入 MCP 协议工具服务器，把社区现成工具动态挂载进对话客户端。
 * 支持 stdio 子进程（本地命令）、SSE 与 Streamable HTTP（远程服务）三种传输。
 * 配置持久化 ~/.cyberpet-mcp.json，保存后立即重连生效（无需重启后端）。
 *
 * 配置格式（与 Claude Desktop 生态一致）：
 * {
 *   "mcpServers": {
 *     "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/Users/xxx"] },
 *     "remote":     { "url": "https://example.com/mcp", "type": "streamable" }
 *   }
 * }
 *
 * 单个 server 连接失败不影响其他 server；失败信息暴露给前端配置面板展示。
 */
@Service
public class McpService {

    private static final Logger logger = LoggerFactory.getLogger(McpService.class);

    private final File configFile = new File(System.getProperty("user.home"), ".cyberpet-mcp.json");
    private final ObjectMapper mapper = new ObjectMapper();

    private final Object lock = new Object();
    private List<McpSyncClient> clients = new ArrayList<>();
    private volatile ToolCallback[] callbacks = new ToolCallback[0];
    private volatile List<Map<String, Object>> status = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!configFile.exists()) {
            logger.info("未找到 MCP 配置（{}），跳过 MCP 工具接入", configFile.getAbsolutePath());
            return;
        }
        rebuild();
    }

    public ToolCallback[] getToolCallbacks() {
        return callbacks;
    }

    /** 配置 + 各 server 连接状态（前端 MCP 面板展示用） */
    public Map<String, Object> getConfigWithStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servers", readServers());
        out.put("status", status);
        return out;
    }

    /** 保存配置并立即重建连接；返回配置 + 连接状态 */
    public Map<String, Object> saveConfig(JsonNode servers) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("mcpServers", servers);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(configFile, wrapper);
            logger.info("MCP 配置已保存: {}", configFile.getAbsolutePath());
        } catch (Exception e) {
            logger.error("MCP 配置落盘失败", e);
        }
        rebuild();
        return getConfigWithStatus();
    }

    /** 重建全部 MCP 连接（启动/保存配置时调用）：单个 server 失败不影响其他 */
    public void rebuild() {
        synchronized (lock) {
            for (McpSyncClient c : clients) {
                try { c.closeGracefully(); } catch (Exception ignore) { }
            }
            clients = new ArrayList<>();
            List<Map<String, Object>> st = new ArrayList<>();

            JsonNode servers = readServers();
            if (servers != null && servers.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = servers.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    String name = e.getKey();
                    JsonNode conf = e.getValue();
                    try {
                        McpSyncClient client = buildClient(name, conf);
                        client.initialize();
                        clients.add(client);
                        int tools = client.listTools().tools().size();
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("name", name);
                        s.put("ok", true);
                        s.put("tools", tools);
                        st.add(s);
                        logger.info("[MCP] 已连接 {}: {} 个工具", name, tools);
                    } catch (Exception ex) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("name", name);
                        s.put("ok", false);
                        s.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                        st.add(s);
                        // ★ 打完整异常链（SDK 常把真实原因包在 cause 里）
                        logger.warn("[MCP] 连接失败 {}", name, ex);
                    }
                }
            }

            if (!clients.isEmpty()) {
                this.callbacks = new SyncMcpToolCallbackProvider(clients).getToolCallbacks();
            } else {
                this.callbacks = new ToolCallback[0];
            }
            this.status = st;
            logger.info("[MCP] 重建完成：{} 个 server，共 {} 个工具", clients.size(), callbacks.length);
        }
    }

    /** 按单条配置构建 MCP 客户端（stdio / SSE / Streamable HTTP） */
    private McpSyncClient buildClient(String name, JsonNode conf) {
        McpClient.SyncSpec spec;
        String url = conf.path("url").asText("");
        if (!url.isBlank()) {
            // type 缺省时按 url 后缀猜：/sse → SSE，其余 → Streamable HTTP
            String type = conf.path("type").asText(url.endsWith("/sse") ? "sse" : "streamable");
            if ("sse".equals(type)) {
                spec = McpClient.sync(HttpClientSseClientTransport.builder(url).build());
            } else {
                spec = McpClient.sync(HttpClientStreamableHttpTransport.builder(url).build());
            }
        } else {
            String command = conf.path("command").asText("");
            if (command.isBlank()) throw new IllegalArgumentException("stdio server 需要 command 字段");
            List<String> args = new ArrayList<>();
            conf.path("args").forEach(a -> args.add(a.asText()));
            Map<String, String> env = new HashMap<>();
            conf.path("env").fields().forEachRemaining(en -> env.put(en.getKey(), en.getValue().asText()));
            // ★ Windows 兜底：ProcessBuilder 无法直接启动 .cmd，npx/npm 自动包一层 cmd /c
            //   （注意顺序：最终命令为 cmd /c npx -y ...，即 /c 必须在最前）
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win") && (command.equalsIgnoreCase("npx") || command.equalsIgnoreCase("npm"))) {
                args.add(0, command);
                args.add(0, "/c");
                command = "cmd";
            }
            ServerParameters params = ServerParameters.builder(command)
                    .args(args)
                    .env(env)
                    .build();
            // ★ 捕获子进程 stderr 打进后端日志：npx 报错/下载进度等真实原因可见
            StdioClientTransport transport = new StdioClientTransport(params,
                    new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(mapper));
            transport.setStdErrorHandler(line -> logger.info("[MCP][{}] stderr: {}", name, line));
            spec = McpClient.sync(transport);
        }
        // ★ 握手超时 60s：兼容 npx 首次下载包的冷启动；requestTimeout 只管后续工具调用
        return spec.initializationTimeout(Duration.ofSeconds(60))
                .requestTimeout(Duration.ofSeconds(30)).build();
    }

    private JsonNode readServers() {
        try {
            if (!configFile.exists()) return mapper.createObjectNode();
            return mapper.readTree(Files.readString(configFile.toPath(), StandardCharsets.UTF_8)).path("mcpServers");
        } catch (Exception e) {
            logger.warn("MCP 配置读取失败: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    @PreDestroy
    public void shutdown() {
        for (McpSyncClient c : clients) {
            try { c.closeGracefully(); } catch (Exception ignore) { }
        }
    }

    /**
     * ★ 提取各 server args 中真实存在的目录路径（供 system prompt 注入）：
     * AI 曾从读到的文件内容里"学到"错误的 MCP 根目录而拼错路径（如把 Desktop 拼到 D:/Try 下），
     * 明确告知授权目录可避免误导。非目录参数（-y、包名等）自然被 isDirectory 过滤。
     */
    public List<String> getAllowedDirs() {
        List<String> dirs = new ArrayList<>();
        JsonNode servers = readServers();
        if (servers == null || !servers.isObject()) return dirs;
        for (Iterator<Map.Entry<String, JsonNode>> it = servers.fields(); it.hasNext(); ) {
            JsonNode args = it.next().getValue().path("args");
            for (JsonNode a : args) {
                String v = a.asText();
                File f = new File(v);
                if (f.isDirectory()) dirs.add(f.getAbsolutePath());
            }
        }
        return dirs;
    }
}
