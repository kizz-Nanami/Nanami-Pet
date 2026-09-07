package com.example.cs.tool;

import com.example.cs.websocket.ElectronBridgeHandler;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ★ Electron 侧工具集（经 /ws/electron 桥由桌宠客户端执行）：
 * 打开网页、打开本地应用——这些动作只有 Electron 能做，后端仅转发。
 */
@Component
public class ElectronTools {

    private final ElectronBridgeHandler bridge;

    public ElectronTools(ElectronBridgeHandler bridge) {
        this.bridge = bridge;
    }

    @Tool(description = "打开指定的网页。当用户说\"打开XX网站\"、\"帮我搜XX\"、\"看XX网页\"时调用")
    public String openWebsite(
            @ToolParam(description = "要打开的网址，例如 www.baidu.com 或 https://github.com") String url
    ) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        // 只放行 http(s)，防其他协议被系统直接执行
        if (!u.matches("^https?://[\\w.-]+.*$")) return "网址格式不合法：" + url;
        return bridge.execute("open_website", Map.of("url", u));
    }

    @Tool(description = "打开用户电脑上的本地应用或程序。name 可以是应用名（如 notepad、mspaint、calc）或程序完整路径")
    public String openApplication(
            @ToolParam(description = "应用名称或可执行文件完整路径，例如 notepad 或 C:/Program Files/.../app.exe") String name
    ) {
        String n = name.trim();
        if (n.isEmpty()) return "应用名不能为空";
        // 白名单字符校验（防命令注入 + 防路径穿越）：
        //   - 应用名（notepad、mspaint 等）：字母/数字/中文/空格/点/横线，不允许 : / \ ——防止
        //     AI 被提示注入后传 "C:/Windows/System32/xx.exe" 任意路径
        //   - 完整路径场景：单独走严格校验，仅放行系统常见程序目录
        if (n.contains(":") || n.contains("/") || n.contains("\\")) {
            String lower = n.toLowerCase().replaceAll("\\\\", "/");
            boolean allowed = lower.startsWith("c:/program files/") || lower.startsWith("c:/program files (x86)/")
                    || lower.startsWith("c:/windows/system32/") || lower.startsWith("c:/windows/")
                    || lower.startsWith(System.getProperty("user.home").toLowerCase().replaceAll("\\\\", "/") + "/appdata/local/");
            if (!allowed) return "仅支持打开应用名或 Program Files/系统目录/用户 AppData 下的程序：" + n;
        }
        if (!n.matches("^[\\w\\u4e00-\\u9fff .:\\\\/\\-]+$")) return "应用名包含非法字符，无法打开";
        return bridge.execute("open_application", Map.of("name", n));
    }
}
