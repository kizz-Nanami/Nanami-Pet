package com.example.cs.controller;

import com.example.cs.service.AiConfigService;
import com.example.cs.service.ChatService;
import com.example.cs.service.McpService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ★ MCP 工具服务器配置：前端 AI 配置面板编辑 ~/.cyberpet-mcp.json，
 * 保存后立即重连并热替换对话客户端工具集（无需重启后端）
 */
@RestController
@CrossOrigin
public class McpController {

    private final McpService mcpService;
    private final ChatService chatService;
    private final AiConfigService aiConfigService;

    public McpController(McpService mcpService, ChatService chatService, AiConfigService aiConfigService) {
        this.mcpService = mcpService;
        this.chatService = chatService;
        this.aiConfigService = aiConfigService;
    }

    @GetMapping("/api/mcp-config")
    public Map<String, Object> getMcpConfig() {
        return mcpService.getConfigWithStatus();
    }

    /** body: {"servers": {...mcpServers 对象...}}；保存后重建连接并刷新 ChatService 工具集 */
    @PostMapping("/api/mcp-config")
    public Map<String, Object> saveMcpConfig(@RequestBody JsonNode body) {
        JsonNode servers = body.has("servers") ? body.get("servers") : body.path("mcpServers");
        Map<String, Object> result = mcpService.saveConfig(servers);
        // MCP 工具集变化 → 重建对话客户端挂载新工具（复用当前 AI 配置）
        chatService.rebuildClients(aiConfigService.get());
        return result;
    }
}
