# Nanami 🐋

> 基于大语言模型的多模态智能桌面助手（AI 桌宠）
> 实时语音对话 · Live2D 表情联动 · 多级记忆体系 · 工具调用与 MCP 扩展

**Nanami（七海）**——名字取自角色「小柒」之「七」，与鲸鱼栖居之「海」。

![Nanami 演示](docs/demo.gif)

## ✨ 功能特性

- 🎙 **实时语音对话**：WebSocket 流式语音识别边说边出字（识别延迟 ~400ms，本机实测），SSE 流式回复 + 三级切句 + TTS 逐句预取，端到端首响约 1.5s；TTS 支持云端 API（含 Edge-TTS 免 Key 引擎）与浏览器本地合成，合成失败自动逐级降级、估时假朗读兜底，保持播报连续
- 😊 **表情随语义联动**：模型以 `[emotion:xxx]` 标签驱动 Live2D 表情；流式提取器逐段解析，缓冲尾部字符并在流结束时统一裁决，避免标签跨 chunk 截断后泄漏到对话显示，兼容倒装 / 中文 / 括号等模型自创变体；朗读结束后表情自动复位
- 🧠 **多级记忆体系**：短期对话（200 条滑动窗口）→ 长期事实（LLM 自动提炼，支持全量注入或 Embedding 向量检索，余弦相似度 Top-K）→ 视觉记忆（定时截屏 + VL 模型生成屏幕观察），跨会话记住用户
- 🛠 **工具调用 + MCP**：内置天气查询（Open-Meteo 免 Key，地理编码 10 候选打分定位城市）、文件浏览与读取（仅读文本文件，前置类型探测防大文件 OOM）、应用拉起等 Function Call 工具，路径与站点走白名单边界；通过 MCP 协议动态挂载外部工具服务器（支持 stdio / SSE / Streamable HTTP 三种传输）
- 🖥 **桌面融合**：透明无边框置顶窗口、鼠标穿透与拖拽切换、闲置主动问候、Live2D 多模型热切换（自动缩放保护）
- 📦 **工程化交付**：electron-builder 绿色版分发；jlink 裁剪定制 JRE（48MB）随包携带，用户免装 Java；对话 / 视觉 / TTS / ASR 四组 AI 服务配置热切换，改完即生效

## 🏗 系统架构

![系统架构](docs/architecture.png)

![功能模块](docs/modules.png)

**技术栈**

| 层 | 技术 |
|---|---|
| 桌面端 | Electron · Vue 3 · TypeScript · PIXI.js（Live2D 渲染） |
| 服务端 | Spring Boot 3 · Spring AI（ChatClient / Function Call / Embedding） |
| 通信 | WebSocket（语音 / 工具桥）· SSE（对话流） |
| AI 能力 | 对话大模型 · 多模态视觉模型 · TTS · ASR · Embedding（OpenAI 兼容协议，可接任意厂商） |

## 🚀 快速开始

### 环境要求

- JDK 17+、Maven 3.8+（或直接使用仓库自带的 `mvnw`）
- Node.js 18+

### 1. 启动后端（端口 8888）

```bash
cd cs
mvn spring-boot:run        # 或 ./mvnw spring-boot:run
```

### 2. 启动桌宠前端

```bash
cd frontend
npm install
npm run dev
```

### 3. 配置 AI 服务

首次启动后，右键托盘图标 →「配置」，分别填入**对话 / 视觉 / TTS / ASR** 四组服务的 Base URL、API Key 与模型名（OpenAI 兼容协议即可，阿里百炼 / 智谱 / SiliconFlow 等均可）。配置保存于 `~/.cyberpet-ai.json`，保存后热生效，无需重启。

> 也可以直接编辑 `cs/src/main/resources/application.properties` 写入默认值，该文件中不含任何真实密钥。

## 🗂 模型准备（Live2D）

因模型素材版权原因，仓库不随附 Live2D 模型文件。请自行获取 `.model3.json` 格式的模型，放入 `frontend/public/live2d/<模型名>/`，并在 `frontend/src/config.ts` 中登记（`modelName` = 文件夹名，`modelFile` = 模型 json 文件名）。

推荐使用 [Live2D 官方免费示例模型](https://www.live2d.com/en/learn/sample/)（Haru、Hiyori 等，注意其使用条款）。

## 📐 项目结构

```
Nanami/
├── cs/                          # Spring Boot 后端（端口 8888）
│   └── src/main/java/com/example/cs/
│       ├── controller/          # 对话 / 记忆 / 配置 REST 接口
│       ├── service/             # ChatService · MemoryService · McpService · TtsService · AsrService · AiConfigService
│       ├── tool/                # Function Call 工具（天气 / 文件 / 应用 / 桌宠控制）
│       └── websocket/           # Electron 桥接（语音流 / 工具执行）
├── frontend/                    # Electron + Vue 3 桌面端
│   ├── electron/                # 主进程（窗口 / 托盘 / 定时截屏 / Edge-TTS）
│   ├── src/
│   │   ├── composables/         # 语音 · 截屏 · 拖拽 · 记忆同步等组合式模块
│   │   ├── utils/               # 情绪标签流式提取器 · 切句器
│   │   └── App.vue
│   └── public/live2d/           # Live2D 模型（不入库，见「模型准备」）
└── docs/                        # 架构图等文档资源
```

## 🧪 测试与实测

- **功能验证**：多轮对话 / 语音链路 / 记忆召回 / 工具调用 / 表情联动 / 配置热切换等核心功能均经手工验证通过
- **性能实测（本机）**：语音识别延迟 ~400ms ｜ 端到端首响 ~1.5s ｜ 句间播报间隔 300ms ｜ 后端冷启动至就绪 ~3s（具体数值因机器与网络环境而异）

## 📄 License

[MIT](LICENSE)（Live2D 模型素材除外，素材版权归各自作者所有）
