<template>
  <!-- ★ 完整的设置窗口 -->
  <div v-if="isSettings" class="settings-window">
    <div class="settings-titlebar">
      <span>{{ settingsTab === "__aiConfig" ? "AI 服务配置" : "设置" }}</span>
      <button class="titlebar-close" onclick="window.close()">X</button>
    </div>
    <div v-if="settingsTab !== '__aiConfig'" class="settings-tabs">
      <button :class="{ active: settingsTab === 'chat' }" @click="settingsTab = 'chat'">聊天记录</button>
      <button :class="{ active: settingsTab === 'persona' }" @click="settingsTab = 'persona'">角色</button>
      <button :class="{ active: settingsTab === 'shortcut' }" @click="settingsTab = 'shortcut'">快捷键</button>
      <button :class="{ active: settingsTab === 'voice' }" @click="settingsTab = 'voice'">语音</button>
      <button :class="{ active: settingsTab === 'general' }" @click="settingsTab = 'general'">通用</button>
    </div>

    <!-- ★ AI 服务配置面板：托盘"配置"入口进入（独立视图，不占 Tab 栏）；四类服务各自独立 key/url/模型 -->
    <div v-if="settingsTab === '__aiConfig'" class="ai-config-panel">
      <div v-for="s in aiSections" :key="s.key" class="ai-config-group">
        <div class="ai-group-title">{{ s.label }}</div>
        <div v-if="s.key === 'tts'" class="ai-config-field">
          <label>合成引擎</label>
          <select v-model="aiForm.tts.provider" class="ai-engine-select">
            <option v-for="opt in ttsEngineOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>
        <div class="ai-config-field">
          <label>接口地址 (Base URL)<template v-if="s.key === 'tts' && isEdgeEngine()">（Edge 引擎无需填写）</template></label>
          <input v-model="aiForm[s.key].baseUrl" :placeholder="s.urlPlaceholder" :disabled="s.key === 'tts' && isEdgeEngine()" spellcheck="false" />
        </div>
        <div class="ai-config-field">
          <label>API Key<template v-if="s.key === 'tts' && isEdgeEngine()">（Edge 引擎无需填写）</template></label>
          <div class="ai-key-row">
            <input v-model="aiForm[s.key].apiKey" :type="showApiKey[s.key] ? 'text' : 'password'" placeholder="sk-..." :disabled="s.key === 'tts' && isEdgeEngine()" spellcheck="false" />
            <button class="ai-key-eye" :title="showApiKey[s.key] ? '隐藏' : '显示'" @click="showApiKey[s.key] = !showApiKey[s.key]">{{ showApiKey[s.key] ? "隐藏" : "显示" }}</button>
          </div>
        </div>
        <div class="ai-config-field">
          <label>模型名<template v-if="s.key === 'tts' && isEdgeEngine()">（Edge 引擎无需填写）</template></label>
          <input v-model="aiForm[s.key].model" :placeholder="s.modelPlaceholder" :disabled="s.key === 'tts' && isEdgeEngine()" spellcheck="false" />
        </div>
        <div v-if="s.key === 'chat'" class="ai-config-field">
          <label>向量记忆 Embedding 模型（留空禁用）</label>
          <input v-model="aiForm.chat.embeddingModel" placeholder="text-embedding-v3" spellcheck="false" />
        </div>
        <div v-if="s.key === 'tts'" class="ai-config-field">
          <label>音色</label>
          <input v-model="aiForm.tts.voice" :placeholder="ttsVoicePlaceholder()" spellcheck="false" />
        </div>
        <div v-if="s.key === 'asr'" class="ai-config-field">
          <label>实时识别模型（边说边出字）</label>
          <input v-model="aiForm[s.key].realtimeModel" placeholder="paraformer-realtime-v2" spellcheck="false" />
        </div>
      </div>

      <!-- ★ 视觉记忆：主进程定时截屏 → VL 描述 → 存档长期记忆（配置存主进程 cyberpet-config.json） -->
      <div class="ai-config-group">
        <div class="ai-group-title">视觉记忆（AI 定期观察屏幕）</div>
        <div class="ai-config-field">
          <label class="ai-check-label">
            <input type="checkbox" v-model="visualMem.enabled" />
            启用（AI 会记住你最近在屏幕上做什么）
          </label>
        </div>
        <div v-if="visualMem.enabled" class="ai-config-field">
          <label>观察间隔（分钟，最小 5）</label>
          <input type="number" v-model.number="visualMem.intervalMin" min="5" spellcheck="false" />
        </div>
      </div>

      <!-- ★ MCP 工具服务器：接入社区 MCP 工具（配置存 ~/.cyberpet-mcp.json，保存后立即重连） -->
      <div class="ai-config-group">
        <div class="ai-group-title">MCP 工具服务器</div>
        <div class="ai-config-field">
          <label>服务器配置（JSON，stdio 或 url）</label>
          <textarea v-model="mcpJson" rows="6" spellcheck="false" class="ai-mcp-textarea" :placeholder="mcpPlaceholder"></textarea>
        </div>
        <div class="ai-config-actions">
          <button class="shortcut-btn primary" :disabled="mcpSaving" @click="saveMcpConfig">{{ mcpSaving ? "连接中…" : "保存并连接" }}</button>
        </div>
        <div v-if="mcpStatusMsg" class="ai-test-msg" :class="{ ok: mcpStatusOk }">{{ mcpStatusMsg }}</div>
      </div>

      <div class="ai-config-actions">
        <button class="shortcut-btn" :disabled="aiTesting" @click="testAiConfig">{{ aiTesting ? "测试中…" : "测试连接（对话）" }}</button>
        <button class="shortcut-btn primary" :disabled="aiSaving" @click="saveAiConfig">{{ aiSaving ? "保存中…" : "保存并生效" }}</button>
      </div>
      <div v-if="aiTestMsg" class="ai-test-msg" :class="{ ok: aiTestOk }">{{ aiTestMsg }}</div>
      <div class="voice-hint">对话/视觉/语音识别为 OpenAI 兼容协议，可填任意兼容服务商（DeepSeek、硅基流动、百炼等）；TTS 与实时识别当前为 DashScope 原生协议，换其他服务商需协议适配。保存后立即生效，配置持久化到用户目录 .cyberpet-ai.json</div>
    </div>

    <!-- 聊天 Tab -->
    <div v-if="settingsTab === 'chat'" class="settings-chat" ref="chatArea">
      <div v-for="(msg, i) in displayConversation" :key="i" :class="['bubble', msg.role]"
        @contextmenu.prevent="openDeleteMenu($event, msg)">
        {{ msg.text }}
      </div>
      <div v-if="loading" class="bubble pet loading-bubble">
        <span class="dots"><span>.</span><span>.</span><span>.</span></span>
      </div>
    </div>

    <div v-if="settingsTab === 'chat'" class="chat-input-bar">
      <button v-if="pendingScreenshot" class="shot-badge" title="点击取消随消息附带的屏幕截图"
        @click="clearScreenshot">📷 已截屏</button>
      <input v-model="textInput" class="chat-text-input" placeholder="输入消息..."
        @keydown.enter="sendTextMessage" />
      <!-- ★ 桌宠播报语音时显示：点击立即停止本次朗读（含另一窗口的播报） -->
      <button v-if="isSpeaking || remoteSpeaking" class="chat-stop-speech-btn" title="停止语音"
        @click="stopSpeech">⏹</button>
      <button class="chat-send-btn" @click="sendTextMessage"
        :disabled="!textInput.trim() || loading">发送</button>
    </div>

    <!-- 右键菜单：删除 -->
    <div v-if="ctxMenu" class="ctx-mask" @click="ctxMenu = null" @contextmenu.prevent="ctxMenu = null">
      <div class="ctx-menu" :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }">
        <button class="ctx-item" @click="menuDelete">删除</button>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteTarget" class="del-mask" @click.self="deleteTarget = null">
      <div class="del-dialog">
        <div class="del-title">是否确认删除此消息</div>
        <div class="del-actions">
          <button class="del-cancel" @click="deleteTarget = null">取消</button>
          <button class="del-ok" @click="confirmDelete">确认</button>
        </div>
      </div>
    </div>

    <!-- ★ 角色 Tab：模型选择 + 自定义人设（人设随请求传给后端，模型切换即时重建） -->
  <div v-if="settingsTab === 'persona'" class="persona-settings">
    <div class="model-select-row">
      <label class="model-label">模型</label>
      <GlassSelect v-model="selectedModelKey" :options="modelOptions" />
    </div>
    <div class="voice-hint" style="margin-bottom: 10px;">切换模型立即生效并记住选择；表情映射不匹配时相关表情自动跳过</div>
    <textarea v-model="personaDraft" class="persona-textarea"
      placeholder="例如：你是一只傲娇的猫娘，说话简短带点毒舌，但很关心主人…"></textarea>
    <div class="persona-actions">
      <button class="shortcut-btn primary" :disabled="personaDraft === personaText" @click="savePersona">保存</button>
    </div>
    <div class="voice-hint">人设每次对话随请求发送，会追加在系统提示词之后；留空则使用默认人设</div>
  </div>

  <!-- 快捷键 Tab：语音/截屏两组快捷键，各自独立配置 -->
<div v-if="settingsTab === 'shortcut'" class="shortcut-settings">
  <div class="shortcut-row">
    <span class="shortcut-label">语音快捷键</span>
    <span class="key-badge" :class="{ capturing: captureTarget === 'voice' }">{{ shortcutRowText("voice") }}</span>
    <template v-if="captureTarget === 'voice'">
      <button v-if="voiceCaptured" class="shortcut-btn primary" @click="saveVoiceShortcut">保存</button>
      <button class="shortcut-btn" @click="cancelCapture">取消</button>
    </template>
    <button v-else class="shortcut-btn" @click="startCapture('voice')">设置快捷键</button>
  </div>
  <div class="shortcut-row">
    <span class="shortcut-label">截屏快捷键</span>
    <span class="key-badge" :class="{ capturing: captureTarget === 'screen' }">{{ shortcutRowText("screen") }}</span>
    <template v-if="captureTarget === 'screen'">
      <button v-if="screenCaptured" class="shortcut-btn primary" @click="saveScreenShortcut">保存</button>
      <button class="shortcut-btn" @click="cancelCapture">取消</button>
    </template>
    <button v-else class="shortcut-btn" @click="startCapture('screen')">设置快捷键</button>
  </div>
  <div class="voice-hint">截屏后宠物会开始聆听，直接说出你想问屏幕的问题即可</div>
</div>

  <!-- ★ 语音 Tab：开关/音色/语速 -->
  <div v-if="settingsTab === 'voice'" class="voice-settings">
    <div class="voice-row">
      <span class="voice-label">语音播报</span>
      <button class="voice-toggle" :class="{ off: !ttsEnabled }" @click="toggleTts">
        {{ ttsEnabled ? "开" : "关" }}
      </button>
    </div>
    <div class="voice-row">
      <span class="voice-label">音色</span>
      <GlassSelect v-model="selectedVoice" :options="voiceSelectOptions" />
    </div>
    <div class="voice-row">
      <span class="voice-label">语速 {{ ttsRate.toFixed(1) }}x</span>
      <input type="range" min="0.5" max="2" step="0.1" v-model.number="ttsRate"
        class="voice-slider" @input="onRateChange" />
    </div>
    <div class="voice-hint">语音由后端 TTS（CosyVoice2）合成，接口不可用时自动降级为系统TTS；音色选项仅对降级生效，API 音色在后端 application.properties 配置</div>
  </div>
  <!-- ★ 通用 Tab：窗口行为（位置锁定；开机自启后续可加在此处） -->
  <div v-if="settingsTab === 'general'" class="general-settings">
    <div class="voice-row">
      <span class="voice-label">锁定位置</span>
      <button class="voice-toggle" :class="{ off: !posLocked }" @click="togglePosLock">
        {{ posLocked ? "开" : "关" }}
      </button>
    </div>
    <div class="voice-hint">开启后宠物无法被拖动，但仍可点击互动。窗口位置会自动记忆，重启后回到原位；若原位置所在显示器已断开，则自动回到屏幕中央</div>
    <div class="voice-row" style="margin-top: 8px;">
      <span class="voice-label">鼠标穿透</span>
      <button class="voice-toggle" :class="{ off: !fullPass }" @click="toggleFullPass">
        {{ fullPass ? "开" : "关" }}
      </button>
    </div>
    <div class="voice-hint">开启后桌宠变为"挂件模式"：完全不响应鼠标，点击全部穿透到下层窗口（关闭请用右下角托盘菜单或本开关）</div>
  </div>

  </div>

  <!-- 宠物窗口 -->
  <div v-else class="pet-window">
    <div class="pet-body" :style="{ width: canvasSize.width + 'px', height: canvasSize.height + 'px' }">
      <div class="pet-drag-region" :style="dragStyle" @mousedown="onMouseDown"></div>
      <Live2DCanvas ref="live2dRef" :width="canvasSize.width"
        :height="canvasSize.height" :model-name="modelCfg.folder"
        :model-file="modelCfg.file" :scale="modelConfig.scale"
        @loaded="onModelLoaded" />

      <!-- ★ 对话气泡：动态贴合模型头顶；气泡与"✓"发送按钮分离，按钮独立在气泡右侧 -->
      <div v-if="hintText" class="bubble-wrap" :style="bubbleStyle">
        <div class="speech-hint" :class="{ 'asr-bubble': asrPending }">
          <!-- ★ 识别结果可直接在气泡里编辑：输入框透明融入气泡，回车即发送 -->
          <input v-if="asrPending" v-model="asrPending" class="asr-edit-input"
            placeholder="可编辑识别结果..." @keydown.enter="sendAsr" />
          <span v-else>{{ hintText }}</span>
          <!-- 识别结果气泡右上角的取消按钮：识别错误时清空内容并关闭气泡 -->
          <button v-if="asrPending" class="asr-cancel-btn" title="取消" @click="cancelAsr">×</button>
        </div>
        <button v-if="asrPending" class="asr-send-btn" title="发送" @click="sendAsr">✓</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// ★ 核心：通过 URL hash 判断当前模式
const isSettings = window.location.hash === "#settings"

import { ref, computed, watch, onMounted, onUnmounted, nextTick } from "vue"
import Live2DCanvas from "./components/Live2DCanvas.vue"
import GlassSelect from "./components/GlassSelect.vue"
import { modelConfig, emotionMap, modelEmotionMaps, touchLines, touchEmotions } from "./config"
import { SpeechManager, refreshTtsEngine } from "./live2d"
import { SentenceSplitter } from "./utils/sentenceSplitter"
import { EmotionTagExtractor, stripEmotionTags } from "./utils/emotionTag"
import { useAsr } from "./composables/useAsr"
import { useScreenshot } from "./composables/useScreenshot"
import { useIdleSpeak } from "./composables/useIdleSpeak"
import { useConversationSync, type Message } from "./composables/useConversationSync"
import { useModelManager } from "./composables/useModelManager"
import { useToolBridge } from "./composables/useToolBridge"
import { usePersona } from "./composables/usePersona"
import { useShortcutCapture } from "./composables/useShortcutCapture"
import { useTtsSettings } from "./composables/useTtsSettings"
import { useDrag } from "./composables/useDrag"

//获取electron的IPC对象（preload 白名单桥：contextIsolation 下渲染层不再直接持有 ipcRenderer/Node）
const ipcRenderer = (window as any).electronAPI ?? ({} as any)

const API = "http://localhost:8888/api/text-chat"

// ★ 画布尺寸：初始用配置值，模型加载后自适应缩小贴合模型本体（头顶预留气泡空间）
const canvasSize = ref({ ...modelConfig.canvas })
const hintText = ref("")   // ★ 气泡文字
//聊天输入框的内容
const textInput = ref("")
//聊天区域的DOM引入 用于滚动到最底部 --获取操作聊天区域
const chatArea = ref<HTMLDivElement>()
// ★ 消息标签清理兜底：历史加载/跨窗口同步的消息不经流式提取器，统一再剥离一次（幂等）
function cleanMsgTags(m: Message): Message {
  return { ...m, text: stripEmotionTags(m.text) }
}
//完整的聊天记录
const conversation = ref<Message[]>([])
//合并连续的pet消息，避免碎片化气泡 SSE；startIdx/endIdx 记录该显示条覆盖的 conversation 区间（供删除精确定位）
interface DisplayMessage { role: "user" | "pet"; text: string; startIdx: number; endIdx: number }
const displayConversation = computed(() => {
  const merged: DisplayMessage[] = []
  conversation.value.forEach((msg, idx) => {
    const last = merged[merged.length - 1]
    if (last && last.role === "pet" && msg.role === "pet") {
      last.text += msg.text
      last.endIdx = idx
    } else {
      merged.push({ ...msg, startIdx: idx, endIdx: idx })
    }
  })
  return merged
})

// ★ 语音播报：朗读节奏是气泡显示的"权威时间线"——
// 每句 onStart 时显示气泡，文字/语音/口型天然同步（TTS 关闭时走假朗读估时，节奏不变）
// ★ 唯一发声源是宠物窗口；设置窗口的实例强制静音（否则两窗口各读一遍=双播），
//   开关/语速/音色通过 IPC 持久化到主进程并广播同步
const speech = new SpeechManager(modelConfig.ttsProvider)
speech.setEnabled(isSettings ? false : modelConfig.ttsEnabled) // 设置窗口永不发声
// 口型驱动通过 live2dRef 间接访问模型（模型加载完成后才生效）
speech.setMouthDriver((v) => live2dRef.value?.live2d?.setMouthOpen(v))

//AI回复加载中
const loading = ref(false)
//宠物模型实例 引用
const live2dRef = ref<any>()

// ★ 语音识别域：识别回调/发送/取消/打断监听（composable）
const { recognizer, asrPending, sendAsr, cancelAsr, armBargeIn } = useAsr({
  isSettings, hintText, textInput, loading, ipc: ipcRenderer, speech,
  sendTextMessage: () => sendTextMessage(),
})

// ★ 截屏视觉感知（composable）：待发送截图 + 截屏快捷键显示值
const { pendingScreenshot, screenShortcut, clearScreenshot } = useScreenshot({ ipc: ipcRenderer })

// ★ 主动发言（composable）：空闲检测 + 时段问候 + markInteract
const { markInteract } = useIdleSpeak({ isSettings, speech, loading, asrPending })

// ★ 对话同步（composable）：历史加载/双窗口同步（回声抑制）/长期记忆上报 → notifyConvUpdate
const { notifyConvUpdate } = useConversationSync({ conversation, speech, ipc: ipcRenderer, cleanMsgTags })

// ★ 模型管理（composable）：当前模型/模型列表/切换广播
const { modelCfg, modelOptions, selectedModelKey } = useModelManager({ isSettings, ipc: ipcRenderer })

// ★ AI 工具桥（composable）：宠物窗口连 /ws/electron，执行 AI 的电脑操作
useToolBridge({ isSettings, ipc: ipcRenderer })

// ★ 角色人设（composable）：加载/编辑草稿/保存
const { personaText, personaDraft, savePersona } = usePersona({ ipc: ipcRenderer })

// ★ 快捷键分组捕获（composable）：语音/截屏两组，独立配置
const { captureTarget, voiceCaptured, screenCaptured, shortcutRowText, startCapture, cancelCapture, saveVoiceShortcut, saveScreenShortcut } =
  useShortcutCapture({ ipc: ipcRenderer, screenShortcut })

// ★ 语音设置（composable）：TTS 开关/音色/语速，变更 IPC 同步并持久化
const { ttsEnabled, ttsRate, selectedVoice, voiceOptions, refreshVoices, toggleTts, onRateChange } =
  useTtsSettings({ isSettings, ipc: ipcRenderer, speech })

// ★ 播报状态（本窗口发声 + 远端发声），驱动输入栏"停止语音"按钮显隐
const isSpeaking = ref(false)      // 本窗口正在播报
const remoteSpeaking = ref(false)  // 另一窗口正在播报（经 speech-state 广播得知）

// 气泡显示：由"开始说这句"的瞬间触发，而非独立的估时队列
speech.setStartListener((text) => {
  isSpeaking.value = true
  ipcRenderer?.send?.("speech-state", true) // 广播播报开始（另一窗口显示停止按钮）
  if (!isSettings) {
    asrPending.value = "" // AI 开始说话：清除识别结果按钮，避免残留
    hintText.value = text
    scrollDown()
    armBargeIn() // ★ 播报期间开启打断监听
  }
})
// ★ 播放全部结束（或被打断）：气泡自行消失，打断监听一并撤销；5s 后表情回落默认
speech.setFinishListener(() => {
  isSpeaking.value = false
  ipcRenderer?.send?.("speech-state", false) // 广播播报结束
  if (!isSettings) {
    hintText.value = ""
    recognizer.stopMonitor()
    scheduleEmotionReset()
  }
})
// ★ 远端停止语音请求：本窗口是发声方时立即中断播报
ipcRenderer?.on?.("speech-stop-req", () => { if (isSpeaking.value) speech.stop() })
// ★ 远端播报状态同步：驱动本窗口输入栏的停止按钮显隐
ipcRenderer?.on?.("speech-state", (_e: unknown, speaking: boolean) => { remoteSpeaking.value = !!speaking })
// ★ 点击"停止语音"：停本窗口播报，并请求另一窗口停止
function stopSpeech() {
  speech.stop() // finishListener 会同步置 isSpeaking=false 并广播
  ipcRenderer?.send?.("speech-stop-req", true)
}

//SSE的请求控制器，用于取消之前的请求（中断流式响应）
let sseAbort: AbortController | null = null
// ★ 请求代数：新消息打断旧流时，旧请求的 finally 据此识别自己已过期，不误清新请求的 loading
let reqSeq = 0

// ★ segment 统一出口：入库/广播/入队朗读前再幂等剥一次标签（防线统一）。
// 气泡与朗读的文字和聊天记录同源同净——即使提取器有跨块泄漏窗口，气泡也不会再闪现 [emotion:xx]
function pushSegment(segment: string) {
  const safe = stripEmotionTags(segment)
  console.log(`[TTS-D] pushSegment "${segment.slice(0, 16)}" -> safe "${safe.slice(0, 16)}"`)
  if (!safe) return
  conversation.value.push({ role: "pet", text: safe })
  notifyConvUpdate()
  speech.enqueue(safe) // ★ 句子入队，气泡由朗读节奏驱动（onStart 时显示）
  scrollDown()
}

//滚动的辅助函数
function scrollDown() {
  nextTick(() => {
    if (chatArea.value) {
      //滚动条当前的位置 = 滚动条的高度
      chatArea.value.scrollTop = chatArea.value.scrollHeight
    }
  })
}

//发送文本消息 --- js --- 业务 --- 逻辑
// ★ 流式切句器：每次发送新建（上一次的半句残留在打断时已随 stop 丢弃）
const splitter = new SentenceSplitter()
// ★ 情绪标签提取器：每次发送新建，剥离 [emotion:xxx] 标签并触发表情
let emotionExtractor = new EmotionTagExtractor()

// ★ 情绪驱动表情唯一入口：查 emotionMap 映射到当前模型表情，无映射/无表情时静默跳过
// ★ 表情自动回落：播报结束后 3s 重置回默认，避免一直停留在上一次对话的表情
const EMOTION_RESET_MS = 3000
let emotionResetTimer: number | null = null
function cancelEmotionReset() {
  if (emotionResetTimer !== null) { clearTimeout(emotionResetTimer); emotionResetTimer = null }
}
function scheduleEmotionReset() {
  cancelEmotionReset()
  emotionResetTimer = window.setTimeout(() => {
    emotionResetTimer = null
    live2dRef.value?.live2d?.resetExpression()
  }, EMOTION_RESET_MS)
}
function setPetEmotion(emotion: string) {
  if (isSettings) return
  // ★ 按当前模型查专属映射表，未收录的模型回退通用 emotionMap（乐正绫走默认表）
  const expr = (modelEmotionMaps[modelCfg.value.folder] ?? emotionMap)[emotion]
  console.log("[pet-emotion] 触发:", emotion, "-> 表情:", expr ?? "(无映射，跳过)")
  cancelEmotionReset() // 新表情出现：取消上一轮未到期的重置，回落时间重新计算
  if (expr) live2dRef.value?.live2d?.setExpression(expr)
  else if (emotion === "calm") live2dRef.value?.live2d?.resetExpression() // 平静：直接回默认
}
async function sendTextMessage() {
  // 从输入框获取文本并移除首尾空格
  const text = textInput.value.trim()
  if (!text) return
  textInput.value = "" // ★ 发送即清空输入框（含回车与按钮两条路径）
  // ★ 新消息=打断一切：上轮流未结束时不再静默丢弃（旧行为会让用户误以为已发送，
  //   而 AI 语音还在播上一轮的回答），改为中止旧流+停语音，新消息立即生效
  if (loading.value) sseAbort?.abort()
  const seq = ++reqSeq
  loading.value = true
  markInteract() // ★ 用户主动交互：刷新主动发言空闲计时
  speech.stop()     // ★ 打断旧朗读：避免新旧两段语音叠加播放
  splitter.reset()  // ★ 丢弃上一次未切完的半句，避免拼接错乱
  emotionExtractor = new EmotionTagExtractor() // ★ 情绪标签提取器同步重置

  // ★ 短期对话记忆：取最近 N 条历史快照（此时还没 push 当前消息），
  // 后端据此还原多轮上下文，AI 能记住本轮会话里说过的话
  const HISTORY_MAX = 20
  const history = conversation.value.slice(-HISTORY_MAX)

  // 添加用户消息
  conversation.value.push({ role: "user", text })
  notifyConvUpdate()
  scrollDown()
  hintText.value = "..."   // 宠物窗口显示加载指示

  try {
    //取消之前的请求，避免重复发送
    sseAbort = new AbortController()
    // ★ 截屏视觉感知：有待发送截图时附带（发送后立即消费，防止后续消息继续带图）
    const shot = pendingScreenshot.value
    if (shot) clearScreenshot()
    //fetch -- 流式ajax
    const res = await fetch(API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, persona: personaText.value, image: shot || undefined, history }),
      signal: sseAbort.signal
    })
    // ★ HTTP 状态校验：后端 500/404 时读到的会是 HTML 错误页，解析不出任何消息，
    //   表现为"发送后无回复也无报错"。此处显式 push 错误气泡，便于第一时间发现后端异常
    if (!res.ok) {
      // ★ 500 多为上游 AI 服务无响应触发超时兜底，给出更具体、可操作的提示
      const tip = res.status === 500
        ? "AI 服务暂时没有响应，请重试；若持续出现请重启程序"
        : `后端异常（HTTP ${res.status}），请检查后端服务`
      conversation.value.push({ role: "pet", text: tip })
      return
    }
    // 读取流式响应
    const reader = res.body!.getReader()
    const decoder = new TextDecoder()
    //SSE响应式流，处理数据块
    let buf = ""

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split("\n\n") //消息分隔符
      buf = parts.pop() || ""
      for (const part of parts) {
        // ★ SSE 解析：后端文本含换行时，WebFlux 会把一条消息拆成多个 data: 行，
        // 必须逐行剥掉前缀再拼接，否则 "data:" 会残留在气泡里
        const data = part
          .split("\n")
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).trim())
          .join("\n")
        if (!data || data === "[DONE]") continue

        // ★ 先剥离情绪标签（触发 Live2D 表情），再流式切句（三层规则）入队朗读
        const { text: safeData, emotion } = emotionExtractor.feed(data)
        if (emotion) {
          setPetEmotion(emotion) // 本窗口是宠物窗口时直接触发
          ipcRenderer?.send?.("pet-emotion", emotion) // 广播给另一窗口（设置窗口聊天时，表情要在宠物窗口生效）
        }
        if (!safeData) continue
        for (const segment of splitter.feed(safeData)) pushSegment(segment)
      }
    }
    // 吐出残留：标签提取器裁决扣留文本（剥离/丢弃残片并提取末尾情绪），再切句器残句
    const { text: tail, emotion: tailEmotion } = emotionExtractor.flush()
    if (tailEmotion) {
      setPetEmotion(tailEmotion)
      ipcRenderer?.send?.("pet-emotion", tailEmotion)
    }
    if (tail) {
      for (const segment of splitter.feed(tail)) pushSegment(segment)
    }
    const remain = splitter.flush()
    if (remain) {
      pushSegment(remain)
    }
  } catch (e: any) {
    if (e?.name !== "AbortError" && seq === reqSeq) {
      conversation.value.push({ role: "pet", text: "网络好像不太好，请稍后再试" })
    }
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

//设置窗口的Tab切换
const settingsTab = ref("chat")

// ★ AI 服务配置面板（托盘"配置"入口 → __aiConfig 独立视图）：四类服务各自独立 key/url/模型
interface AiEndpoint { baseUrl: string; apiKey: string; model: string; realtimeModel?: string; provider?: string; voice?: string; embeddingModel?: string }
const emptyEndpoint = (): AiEndpoint => ({ baseUrl: "", apiKey: "", model: "" })
const aiForm = ref<{ chat: AiEndpoint; vision: AiEndpoint; tts: AiEndpoint; asr: AiEndpoint }>({
  chat: emptyEndpoint(), vision: emptyEndpoint(), tts: emptyEndpoint(), asr: emptyEndpoint(),
})
// TTS 引擎选项：dashscope=百炼原生 / openai=OpenAI 兼容（硅基等） / edge=Edge-TTS（免费无额度）
const ttsEngineOptions = [
  { value: "dashscope", label: "百炼原生（qwen3-tts）" },
  { value: "openai", label: "OpenAI 兼容（硅基/CosyVoice2 等）" },
  { value: "edge", label: "Edge-TTS（微软免费，无需 key）" },
]
// 音色输入框 placeholder 随引擎变化
const ttsVoicePlaceholder = () =>
  aiForm.value.tts.provider === "edge" ? "zh-CN-XiaoxiaoNeural"
  : aiForm.value.tts.provider === "openai" ? "FunAudioLLM/CosyVoice2-0.5B:alex（音色需带模型名前缀）"
  : "Cherry"
const isEdgeEngine = () => aiForm.value.tts.provider === "edge"
// 分组渲染配置（label + 各输入框 placeholder）
const aiSections = [
  { key: "chat", label: "对话模型", urlPlaceholder: "https://dashscope.aliyuncs.com/compatible-mode", modelPlaceholder: "qwen3.7-plus" },
  { key: "vision", label: "视觉模型（截屏识图）", urlPlaceholder: "https://dashscope.aliyuncs.com/compatible-mode", modelPlaceholder: "qwen-vl-plus" },
  { key: "tts", label: "语音合成（TTS）", urlPlaceholder: "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", modelPlaceholder: "qwen3-tts-instruct-flash" },
  { key: "asr", label: "语音识别（ASR）", urlPlaceholder: "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", modelPlaceholder: "qwen3-asr-flash" },
] as const
const showApiKey = ref<Record<string, boolean>>({})
const aiSaving = ref(false)
const aiTesting = ref(false)
const aiTestMsg = ref("")
const aiTestOk = ref(false)
async function loadAiConfig() {
  try {
    const r = await fetch("http://localhost:8888/api/ai-config")
    if (r.ok) aiForm.value = await r.json()
  } catch { /* 后端未启动时静默，表单保持空 */ }
}
async function saveAiConfig() {
  if (aiSaving.value) return
  aiSaving.value = true
  try {
    if (!aiForm.value.tts.provider) aiForm.value.tts.provider = "dashscope"
    const r = await fetch("http://localhost:8888/api/ai-config", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(aiForm.value)
    })
    aiTestMsg.value = r.ok ? "已保存，新配置立即生效（无需重启后端）" : "保存失败（HTTP " + r.status + "）"
    aiTestOk.value = r.ok
    if (r.ok) ipcRenderer?.send?.("ai-config-changed") // 通知各窗口刷新 TTS 引擎缓存
  } catch (e: any) {
    aiTestMsg.value = "保存失败：后端未连接（" + (e?.message ?? e) + "）"
    aiTestOk.value = false
  } finally { aiSaving.value = false }
}
async function testAiConfig() {
  if (aiTesting.value) return
  aiTesting.value = true
  aiTestMsg.value = ""
  try {
    const r = await fetch("http://localhost:8888/api/ai-config/test", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(aiForm.value)
    })
    const data = await r.json()
    aiTestMsg.value = data.message || "无返回内容"
    aiTestOk.value = r.ok && (data.message || "").startsWith("连接成功")
  } catch (e: any) {
    aiTestMsg.value = "测试失败：后端未连接（" + (e?.message ?? e) + "）"
    aiTestOk.value = false
  } finally { aiTesting.value = false }
}

// ★ 视觉记忆：开关/间隔存主进程 cyberpet-config.json，变更即时保存并重新调度定时任务
const visualMem = ref({ enabled: false, intervalMin: 30 })
if (isSettings) {
  // 加载完成前禁止 watch 回传，避免"读配置→原样写回"触发主进程重复调度（重复 20s 首观察）
  let visualMemLoaded = false
  void ipcRenderer?.invoke?.("get-visual-mem-config").then((cfg: any) => {
    if (cfg) visualMem.value = { enabled: !!cfg.enabled, intervalMin: cfg.intervalMin || 30 }
    visualMemLoaded = true
  })
  watch(visualMem, (v) => {
    if (!visualMemLoaded) return
    ipcRenderer?.send?.("set-visual-mem-config", JSON.parse(JSON.stringify(v)))
  }, { deep: true })
}

// ★ MCP 工具服务器配置：读写 ~/.cyberpet-mcp.json（保存后后端立即重连并热替换工具集）
const mcpJson = ref("")
const mcpPlaceholder = JSON.stringify({
  mcpServers: {
    filesystem: { command: "npx", args: ["-y", "@modelcontextprotocol/server-filesystem", "C:/Users/你的用户名"] },
    remote: { url: "https://example.com/mcp", type: "streamable" }
  }
}, null, 2)
const mcpSaving = ref(false)
const mcpStatusMsg = ref("")
const mcpStatusOk = ref(false)
async function loadMcpConfig() {
  try {
    const r = await fetch("http://localhost:8888/api/mcp-config")
    if (!r.ok) return
    const data = await r.json()
    mcpJson.value = data.servers && Object.keys(data.servers).length
      ? JSON.stringify({ mcpServers: data.servers }, null, 2)
      : ""
    mcpStatusMsg.value = formatMcpStatus(data.status)
    mcpStatusOk.value = (data.status || []).every((s: any) => s.ok)
  } catch { /* 后端未启动时静默 */ }
}
function formatMcpStatus(status: any[]): string {
  if (!status || !status.length) return ""
  return status.map((s) => s.ok ? `${s.name}：已连接（${s.tools} 个工具）` : `${s.name}：连接失败（${s.error}）`).join("；")
}
async function saveMcpConfig() {
  if (mcpSaving.value) return
  mcpSaving.value = true
  mcpStatusMsg.value = ""
  try {
    let servers: any = {}
    if (mcpJson.value.trim()) {
      const parsed = JSON.parse(mcpJson.value)
      servers = parsed.mcpServers ?? parsed
    }
    const r = await fetch("http://localhost:8888/api/mcp-config", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ servers })
    })
    const data = await r.json()
    mcpStatusMsg.value = r.ok ? formatMcpStatus(data.status) || "已保存（无配置服务器）" : "保存失败（HTTP " + r.status + "）"
    mcpStatusOk.value = r.ok
  } catch (e: any) {
    mcpStatusMsg.value = e instanceof SyntaxError ? "JSON 格式错误：" + e.message : "保存失败：后端未连接（" + (e?.message ?? e) + "）"
    mcpStatusOk.value = false
  } finally { mcpSaving.value = false }
}

// ★ 位置锁定：锁定后宠物窗口拖动失效（仍可点击互动）；状态由主进程持久化并在两窗口间同步
const posLocked = ref(false)
function togglePosLock() {
  posLocked.value = !posLocked.value
  ipcRenderer?.send?.("set-pos-locked", posLocked.value)
}

// ★ 完全穿透（挂件模式）：开启后桌宠整体不响应鼠标，点击全部落到下层窗口；
//   状态由主进程持久化，托盘菜单与设置窗口双入口切换
const fullPass = ref(false)
function toggleFullPass() {
  fullPass.value = !fullPass.value
  ipcRenderer?.send?.("set-full-pass", fullPass.value)
}

// ★ 两个开关的初始状态从主进程读取（重启后恢复持久化值），并监听另一窗口/托盘的同步广播
posLocked.value = ipcRenderer?.sendSync?.("get-pos-locked") ?? false
ipcRenderer?.on?.("pos-locked-changed", (_e: unknown, v: boolean) => { posLocked.value = !!v })
fullPass.value = ipcRenderer?.sendSync?.("get-full-pass") ?? false
ipcRenderer?.on?.("full-pass-changed", (_e: unknown, v: boolean) => { fullPass.value = !!v })

// ★ 触摸互动：点击桌宠 → 随机表情 + 随机台词（正在说话/识别/回复时不插话）
function onTouchPet() {
  if (isSettings) return
  markInteract()
  const emo = touchEmotions[Math.floor(Math.random() * touchEmotions.length)]
  setPetEmotion(emo)
  if (speech.busy || loading.value || asrPending.value) return
  const line = touchLines[Math.floor(Math.random() * touchLines.length)]
  speech.enqueue(line)
}

// ★ 拖拽/鼠标穿透/触摸判定（composable）：document 级鼠标监听 + 位置锁定状态加载
const { onMouseDown } = useDrag({ isSettings, ipc: ipcRenderer, posLocked, fullPass, live2dRef, onTouchPet: () => onTouchPet() })

onMounted(() => {
  // ★ AI 配置变更（托盘"配置"面板保存后）：刷新 TTS 引擎缓存，切换引擎免重载页面即生效
  ipcRenderer?.on?.("ai-config-changed", () => { void refreshTtsEngine() })
  // ★ 通知 Electron 把窗口尺寸调整到和画布一致
  if (!isSettings && ipcRenderer?.send)
    ipcRenderer.send("resize-pet-window", canvasSize.value.width, canvasSize.value.height)
  // ★ 语音识别：全局快捷键由主进程发到宠物窗口；识别文字发到设置窗口填入输入框
  if (!isSettings && ipcRenderer?.on) {
    ipcRenderer.on("toggle-recording", () => {
      markInteract()
      if (speech.busy) speech.stop() // 播报中按快捷键：先打断闭嘴，避免麦克风录进宠物自己的声音
      void recognizer.toggle()
    })
    ipcRenderer.on("pet-emotion", (_e: any, emotion: string) => setPetEmotion(emotion)) // 设置窗口聊天时接收情绪广播驱动表情
  }
  if (isSettings && ipcRenderer?.on) {
    ipcRenderer.on("asr-result", (_e: any, text: string) => {
      textInput.value = text
    })
    // 桌宠气泡上已一键发送：清空输入框防止再次发送重复消息
    ipcRenderer.on("asr-sent", (_e: any, text: string) => {
      if (textInput.value === text) textInput.value = ""
    })
    // ★ 托盘"配置"入口：切到 AI 配置视图并加载当前配置
    ipcRenderer.on("open-ai-config", () => {
      settingsTab.value = "__aiConfig"
      loadAiConfig()
      loadMcpConfig()
    })
  }
})

//组件卸载时，移除所有监听事件（各 composable 内部各自清理自己的监听/定时器）
onUnmounted(() => {
  ipcRenderer?.removeAllListeners?.("toggle-recording")
  ipcRenderer?.removeAllListeners?.("pet-emotion")
  ipcRenderer?.removeAllListeners?.("asr-result")
  ipcRenderer?.removeAllListeners?.("asr-sent")
  ipcRenderer?.removeAllListeners?.("ai-config-changed")
  recognizer.stopMonitor() // 释放打断监听的麦克风资源
})

// 通知 Electron主进程 更新对话记录（回声抑制/记忆上报见 useConversationSync）

// ★ 删除聊天记录：右键气泡弹出菜单，确认后按区间整段删除（合并显示的一条可能对应多条连续记录）
const deleteTarget = ref<DisplayMessage | null>(null)
const ctxMenu = ref<{ x: number; y: number; msg: DisplayMessage } | null>(null)
function openDeleteMenu(e: MouseEvent, msg: DisplayMessage) {
  if (loading.value) return // 生成中禁止删除，避免与流式写入错位
  // 防止菜单贴出窗口边缘
  ctxMenu.value = {
    x: Math.min(e.clientX, window.innerWidth - 90),
    y: Math.min(e.clientY, window.innerHeight - 44),
    msg
  }
}
function menuDelete() {
  deleteTarget.value = ctxMenu.value?.msg ?? null
  ctxMenu.value = null
}
function confirmDelete() {
  const msg = deleteTarget.value
  deleteTarget.value = null
  if (!msg) return
  // 精确按该显示条覆盖的区间删除（合并的 AI 分句整段删）。
  // user 消息删除时连带其后紧邻的桌宠回复整段：否则回复会因与上一条 pet 记录连续
  // 而被合并渲染进上一个气泡（表现为"删掉的回复内容跑进上一个气泡"）
  let end = msg.endIdx
  if (msg.role === "user") {
    while (end + 1 < conversation.value.length && conversation.value[end + 1].role === "pet") end++
  }
  conversation.value.splice(msg.startIdx, end - msg.startIdx + 1)
  notifyConvUpdate() // 同步到主进程落盘并广播另一窗口
}

// ★ 气泡显示已由 speech.onStart 驱动（见上方 setStartListener），
// 旧的下短哈队列 bubbleQueue/showNextBubble 已移除

// ★ 气泡定位：模型头顶 y 坐标（画布 CSS 像素），首个气泡显示时测量一次
const headTop = ref(0)
// ★ 拖拽判定区域：贴合模型实际渲染包围盒，避免画布空白处误拖
const dragRect = ref<{ left: number; top: number; width: number; height: number } | null>(null)
function ensureHeadTop() {
  if (headTop.value) return
  const lm = live2dRef.value?.live2d
  const t = lm?.getVisualTop?.()
  if (t != null && isFinite(t)) {
    //clamp 到合理范围，防止 idle 摆动/特效导致包围盒偏差过大
    headTop.value = Math.round(Math.min(Math.max(t, 30), canvasSize.value.height * 0.6))
    dragRect.value = lm?.getVisualBounds?.() ?? null
  }
}
//气泡出现时确保已测量头顶位置（模型加载完成、姿势稳定后）
watch(hintText, (v) => { if (v) ensureHeadTop() })

// ★ 模型加载后：缩小画布/窗口贴合模型本体，消除大片透明空白挡鼠标
const BUBBLE_PAD_TOP = 100  // 头顶预留的气泡显示空间
const CANVAS_PAD = 10       // 模型四周余量
const BUBBLE_MAX_W = 280    // 气泡最大宽度（与 CSS .speech-hint 的 max-width 一致）
let petFitted = false       // ★ 仅首次 fit 补偿窗口位置；切换模型时窗口不动，否则默认放置点偏移会累积成左下漂移
function onModelLoaded() {
  const lm = live2dRef.value?.live2d
  const m = lm?.model
  if (lm && m) {
    const oldCX = m.x, oldCY = m.y  // fit 前模型中心（旧画布坐标）
    // 窗口宽度至少容纳完整气泡（气泡 280px + 两侧 8px 余量）
    const fit = lm.fitCanvas(BUBBLE_PAD_TOP, CANVAS_PAD, CANVAS_PAD, BUBBLE_MAX_W + 16)
    if (fit) {
      // 补偿窗口位置偏移，保持模型屏幕位置不动（旧/新画布中模型中心的差值）。
      // 切换模型时固定不补偿：fitCanvas 规则（水平居中+顶部 padTop）保证头顶位置稳定
      const dx = petFitted ? 0 : Math.round(oldCX - fit.width / 2)
      const dy = petFitted ? 0 : Math.round(oldCY - (BUBBLE_PAD_TOP + m.height / 2))
      petFitted = true
      canvasSize.value = fit
      // 旧画布上的测量结果作废，重测头顶/拖拽区
      headTop.value = 0
      dragRect.value = null
      ipcRenderer?.send?.("resize-pet-window", fit.width, fit.height, dx, dy)
      // ★ 画布尺寸落定后立即重测模型包围盒（拖拽区/自动穿透依赖它）——
      //   不再等首次气泡出现，否则在那之前 drag-region 保持 inset:0 = 整块画布拦截鼠标
      nextTick(() => setTimeout(ensureHeadTop, 300))
    }
  }
  ensureHeadTop()
}

// ★ 拖拽区样式：模型包围盒外扩 8px 余量（覆盖 idle 呼吸/摆动幅度），并 clamp 到画布内
const dragStyle = computed(() => {
  const r = dragRect.value
  if (!r) return {} //未测量时保持 CSS 默认 inset:0
  const W = canvasSize.value.width
  const H = canvasSize.value.height
  const PAD = 8
  const left = Math.max(0, Math.round(r.left - PAD))
  const top = Math.max(0, Math.round(r.top - PAD))
  return {
    left: left + "px",
    top: top + "px",
    width: Math.min(W - left, Math.round(r.width + PAD * 2)) + "px",
    height: Math.min(H - top, Math.round(r.height + PAD * 2)) + "px"
  }
})

// ★ 气泡样式：头顶上方空间充足时贴头顶；不足 80px 时紧贴头顶向下展开
const bubbleStyle = computed(() => {
  if (!headTop.value) return { top: "60px" } //未测量时的兜底位置
  const H = canvasSize.value.height
  const gap = 10
  const avail = headTop.value - gap
  if (avail >= 80) {
    //底边贴在模型头顶上方 gap 像素（气泡宽度自适应内容，超高自然向下展开）
    return { bottom: (H - headTop.value + gap) + "px" }
  }
  //降级：空间不足，紧贴头顶（最多上移 50px）向下展开，允许盖住头顶
  return { top: Math.max(6, headTop.value - 50) + "px" }
})

// ★ 音色下拉选项（GlassSelect 通用格式）
const voiceSelectOptions = computed(() =>
  voiceOptions.value.map((v) => ({ value: v.name, label: `${v.name}（${v.lang}）` })))

//切到语音Tab时刷新音色列表；切到聊天Tab时定位到最新消息
//immediate：settingsTab 初始即为 "chat"，首次打开窗口也要滚动到底部
watch(settingsTab, (tab) => {
  if (tab === "voice") refreshVoices()
  if (tab === "chat") scrollDown()
}, { immediate: true })
//聊天记录变化时（新消息/删除）保持视图在底部
watch(displayConversation, () => {
  if (settingsTab.value === "chat") scrollDown()
})
</script>

<style>

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body,
#app {
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: transparent;
}

body {
  font-family: "Microsoft YaHei", sans-serif;
}

/* ═══ 设置窗口（纯 CSS 玻璃：不依赖系统 acrylic——其灰 tint 在白色桌面下发灰；
   backdrop-filter 无法模糊桌面故移除，半透明白 + 饱和白边提供玻璃质感，背景色一致可控） ═══ */
.settings-window {
  width: 100vw;
  height: 100vh;
  background: rgba(255, 255, 255, 0.80); /* 加深白叠加制造轻雾感（Electron 无法单独模糊桌面，这是最接近"轻模糊"的方案） */
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 18px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.16);
  color: #131a26;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.settings-titlebar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px 6px;
  flex-shrink: 0;
  -webkit-app-region: drag;
}
.settings-titlebar span {
  font-size: 13px;
  font-weight: 600;
  color: #0d131d;
}
.titlebar-close {
  -webkit-app-region: no-drag;
  background: none;
  border: none;
  color: #2a3442;
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  line-height: 1;
}
.titlebar-close:hover {
  background: rgba(255, 80, 80, 0.15);
  color: #e5484d;
}
.settings-tabs {
  display: flex;
  padding: 0 6px;
  flex-shrink: 0;
}
.settings-tabs button {
  flex: 1;
  padding: 10px 0;
  background: transparent;
  border: none;
  border-radius: 8px;
  color: #131a26;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.settings-tabs button.active {
  color: #3b82f6;
  border-bottom-color: #3b82f6;
}

/* ═══ 聊天区域 ═══ */
.settings-chat {
  flex: 1;
  overflow-y: auto;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.settings-chat::-webkit-scrollbar {
  width: 4px;
}
.settings-chat::-webkit-scrollbar-thumb {
  background: #c0c8d4;
  border-radius: 2px;
}
/* ★ AI 配置面板滚动条：与设置窗口同风格的细滚动条 */
.ai-config-panel::-webkit-scrollbar {
  width: 4px;
}
.ai-config-panel::-webkit-scrollbar-thumb {
  background: #c0c8d4;
  border-radius: 2px;
}
.ai-config-panel::-webkit-scrollbar-thumb:hover {
  background: #9aa5b5;
}
.ai-config-panel::-webkit-scrollbar-track {
  background: transparent;
}
.chat-input-bar {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.25);
  border-radius: 14px 14px 0 0; /* 顶部两角圆角，底部贴窗口边缘 */
}
.chat-text-input {
  flex: 1;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  background: rgba(255, 255, 255, 0.5);
  color: #131a26;
  font-size: 13px;
  outline: none;
  font-family: inherit;
}
.chat-text-input:focus {
  border-color: rgba(59, 130, 246, 0.5);
}
.chat-text-input::placeholder {
  color: #4a5568; /* placeholder 比正文浅但仍清晰可读 */
}
/* ★ 截屏徽章：有待发送截图时显示在输入框左侧，点击清除 */
.shot-badge {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid rgba(59, 130, 246, 0.45);
  background: rgba(59, 130, 246, 0.15);
  color: #131a26;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
  transition: all 0.15s;
}
.shot-badge:hover {
  background: rgba(59, 130, 246, 0.3);
}
.chat-send-btn {
  padding: 8px 16px;
  border-radius: 8px;
  border: none;
  background: rgba(59, 130, 246, 0.85);
  color: white;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
  transition: all 0.15s;
}
.chat-send-btn:hover {
  background: #3b82f6;
}
.chat-send-btn:disabled {
  opacity: 0.4;
  cursor: default;
}
/* ★ 停止语音按钮：播报期间出现在发送按钮左侧，红色系提示"打断"语义 */
.chat-stop-speech-btn {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 8px;
  border: none;
  background: rgba(229, 72, 77, 0.85);
  color: white;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  transition: all 0.15s;
}
.chat-stop-speech-btn:hover {
  background: #e5484d;
}
.chat-stop-btn {
  padding: 8px 16px;
  border-radius: 8px;
  border: none;
  background: rgba(229, 72, 77, 0.85);
  color: white;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
  transition: all 0.15s;
}
.chat-stop-btn:hover {
  background: #e5484d;
}

/* ═══ 气泡（聊天记录，玻璃风） ═══ */
.bubble {
  position: relative;
  max-width: 85%;
  padding: 7px 12px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
  flex-shrink: 0;
}
/* ═══ 右键删除菜单 ═══ */
.ctx-mask {
  position: fixed;
  inset: 0;
  z-index: 90;
}
.ctx-menu {
  position: fixed;
  z-index: 91;
  min-width: 72px;
  padding: 4px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(24px) saturate(1.5);
  border: 1px solid rgba(255, 255, 255, 0.5);
  box-shadow: 0 8px 24px rgba(30, 40, 60, 0.22);
}
.ctx-item {
  display: block;
  width: 100%;
  padding: 6px 14px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: #ef4444;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.ctx-item:hover {
  background: rgba(239, 68, 68, 0.12);
}

/* ═══ 删除确认弹窗 ═══ */
.del-mask {
  position: fixed;
  inset: 0;
  background: rgba(20, 26, 38, 0.35);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.del-dialog {
  width: 240px;
  padding: 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(28px) saturate(1.5);
  border: 1px solid rgba(255, 255, 255, 0.45);
  box-shadow: 0 12px 32px rgba(30, 40, 60, 0.25);
}
.del-title {
  font-size: 14px;
  font-weight: 600;
  color: #1d2530;
  margin-bottom: 16px;
  text-align: center;
}
.del-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}
.del-cancel, .del-ok {
  min-width: 76px;
  padding: 6px 18px;
  border-radius: 12px;
  border: none;
  font-size: 13px;
  cursor: pointer;
}
.del-cancel {
  background: rgba(255, 255, 255, 0.6);
  color: #131a26;
}
.del-cancel:hover {
  background: rgba(255, 255, 255, 0.85);
}
.del-ok {
  background: #ef4444;
  color: white;
}
.del-ok:hover {
  background: #dc2626;
}
.bubble.user {
  align-self: flex-end;
  background: rgba(59, 130, 246, 0.55);
  backdrop-filter: blur(14px) saturate(1.6);
  -webkit-backdrop-filter: blur(14px) saturate(1.6);
  color: white;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-bottom-right-radius: 4px;
}
.bubble.pet {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.42);
  backdrop-filter: blur(14px) saturate(1.6);
  -webkit-backdrop-filter: blur(14px) saturate(1.6);
  border: 1px solid rgba(255, 255, 255, 0.45);
  color: #131a26;
  border-bottom-left-radius: 4px;
}
.loading-bubble {
  padding: 12px 16px;
}
.dots span {
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #3b82f6;
  border-radius: 50%;
  margin: 0 3px;
  animation: dot-bounce 1.2s infinite;
}
.dots span:nth-child(2) { animation-delay: 0.2s; }
.dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-bounce {
  0%, 100% { transform: translateY(0); opacity: 0.3; }
  50% { transform: translateY(-6px); opacity: 1; }
}

/* ═══ 宠物窗口 ═══ */
.pet-window {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  user-select: none;
}
.pet-body {
  position: relative;
  cursor: grab;
  flex-shrink: 0;
}
.pet-body:active { cursor: grabbing; }
.pet-drag-region {
  position: absolute;
  inset: 0;
  z-index: 1;
  cursor: grab;
}
.pet-drag-region:active { cursor: grabbing; }

/* ═══ 对话气泡容器（垂直位置由内联 bubbleStyle 动态控制，贴合模型头顶） ═══
   容器负责居中/动画/z-index，内部为消息气泡 + 独立的 ✓ 发送按钮；
   pointer-events 由子元素各自决定（普通气泡穿透，asr 气泡与按钮可点击） */
.bubble-wrap {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 6px;
  z-index: 10;
  pointer-events: none;
  animation: popIn 0.25s ease-out;
}
.speech-hint {
  width: max-content;
  max-width: 280px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(20px) saturate(1.4);
  -webkit-backdrop-filter: blur(20px) saturate(1.4);
  border: 1px solid rgba(255, 255, 255, 0.45);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  border-radius: 16px 16px 4px 16px;
  padding: 8px 14px;
  font-size: 12px;
  line-height: 1.6;
  color: #131a26;
  text-align: center;
  white-space: pre-wrap;
  word-break: break-word;
}
/* 语音识别结果气泡：可交互（右侧附"✓"发送按钮、右上角附"×"取消按钮） */
.speech-hint.asr-bubble {
  position: relative; /* 作为取消按钮的定位基准 */
  padding-right: 22px; /* 右上角给取消按钮留位，避免压住文字 */
  pointer-events: auto;
}
/* ★ 识别结果编辑输入框：透明融入气泡，像在原文本上直接改字 */
.asr-edit-input {
  /* field-sizing 让宽度随内容自适应（Chromium 123+）；不支持时退回固定宽度可滚动 */
  field-sizing: content;
  min-width: 56px; /* 短语音编辑时气泡贴合内容，不再强撑宽 */
  max-width: 230px;
  border: none;
  outline: none;
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: center;
  padding: 0;
  pointer-events: auto;
}
.asr-edit-input::placeholder {
  color: #4a5568;
}
/* × 取消按钮：气泡右上角半出血的红色小圆钮 */
.asr-cancel-btn {
  position: absolute;
  top: -9px;
  right: -9px;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 50%;
  background: #e5484d;
  color: #fff;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  pointer-events: auto;
  box-shadow: 0 1px 4px rgba(229, 72, 77, 0.45);
  transition: background 0.15s ease, transform 0.1s ease;
}
.asr-cancel-btn:hover { background: #d13438; }
.asr-cancel-btn:active { transform: scale(0.94); }
/* ✓ 发送按钮：独立于气泡右侧的圆形按钮 */
.asr-send-btn {
  flex: none;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 50%;
  background: #4f8cff;
  color: #fff;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  pointer-events: auto;
  box-shadow: 0 1px 4px rgba(79, 140, 255, 0.4);
  transition: background 0.15s ease, transform 0.1s ease;
}
.asr-send-btn:hover { background: #3d7bf5; }
.asr-send-btn:active { transform: scale(0.96); }
@keyframes popIn {
  from { opacity: 0; transform: translateX(-50%) translateY(8px) scale(0.9); }
  to   { opacity: 1; transform: translateX(-50%) translateY(0) scale(1); }
}

/* ★ 快捷键 Tab 容器：与窗口边缘留白 */
/* ★ AI 服务配置面板：表单字段 + 测试/保存按钮 + 结果提示 */
.ai-config-panel {
  flex: 1;
  padding: 18px 20px 24px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.ai-config-group {
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 10px;
  padding: 12px 14px 4px;
  margin-bottom: 14px;
  background: rgba(255, 255, 255, 0.35);
}
.ai-group-title {
  font-size: 13px;
  font-weight: 700;
  color: #131a26;
  margin-bottom: 10px;
}
.ai-config-field {
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ai-config-field label {
  font-size: 12.5px;
  color: #131a26;
  font-weight: 600;
}
.ai-config-field input {
  height: 34px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  padding: 0 10px;
  font-size: 13px;
  color: #131a26;
  outline: none;
}
.ai-config-field input:focus {
  border-color: #5b8def;
  background: #fff;
}
/* ★ MCP 服务器 JSON 编辑框：复用 input 的玻璃风格，等宽字体便于编辑 */
.ai-mcp-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  color: #131a26;
  font-size: 12px;
  font-family: Consolas, "Courier New", monospace;
  line-height: 1.5;
  resize: vertical;
}
.ai-mcp-textarea:focus {
  outline: none;
  border-color: #5b8def;
  background: #fff;
}
/* ★ 视觉记忆开关行：checkbox 与文字同行 */
.ai-check-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-weight: normal;
}
.ai-check-label input[type="checkbox"] {
  width: 15px;
  height: 15px;
  accent-color: #5b8def;
}
.ai-key-row {
  display: flex;
  gap: 6px;
}
.ai-engine-select {
  height: 34px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  padding: 0 8px;
  font-size: 12.5px;
  color: #131a26;
  outline: none;
}
.ai-key-row input { flex: 1; }
.ai-key-eye {
  height: 34px;
  padding: 0 10px;
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  color: #131a26;
  cursor: pointer;
}
.ai-config-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
}
.ai-test-msg {
  margin-top: 12px;
  font-size: 12.5px;
  color: #c0392b;
  word-break: break-all;
}
.ai-test-msg.ok { color: #1e8e4e; }

.shortcut-settings {
  flex: 1;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
}

/* ★ 快捷键分组行：标签 + 当前按键徽章 + 独立"设置快捷键"按钮 */
.shortcut-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.shortcut-label { font-size: 14px; color: #131a26; flex-shrink: 0; }
.shortcut-row .key-badge { margin-left: auto; }

/* ═══ 语音设置（玻璃风） ═══ */
.voice-settings {
  flex: 1;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
/* ★ 通用 Tab：与语音 Tab 同规格留白 */
.general-settings {
  flex: 1;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.voice-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.voice-label {
  font-size: 14px;
  color: #131a26;
  min-width: 90px;
}
.voice-toggle {
  padding: 5px 18px;
  border-radius: 14px;
  border: 1px solid rgba(34, 170, 100, 0.4);
  background: rgba(34, 170, 100, 0.15);
  color: #1d9e5f;
  cursor: pointer;
  font-size: 13px;
}
.voice-toggle.off {
  border-color: rgba(229, 72, 77, 0.4);
  background: rgba(229, 72, 77, 0.12);
  color: #e5484d;
}
/* ★ 下拉框已替换为 GlassSelect 组件（组件内自带玻璃风外观），这里只控制布局 */
.voice-row .glass-select,
.model-select-row .glass-select {
  flex: 1;
  min-width: 0; /* 音色名很长时不撑破窗口 */
}
.voice-slider {
  flex: 1;
  accent-color: #3b82f6;
}
.voice-hint {
  font-size: 12px;
  color: #1d2530;
  margin-top: 4px;
}
.key-badge.capturing { color: #e58a3d; border-color: rgba(229, 138, 61, 0.5); }
.shortcut-btn {
  padding: 8px 18px; border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  background: rgba(255, 255, 255, 0.5); color: #131a26;
  cursor: pointer; font-size: 13px;
}
.shortcut-btn:hover { background: rgba(255, 255, 255, 0.7); }
.shortcut-btn.primary {
  background: rgba(59, 130, 246, 0.85);
  border-color: transparent; color: white;
}
@keyframes pulse-text {
  0%, 100% { opacity: 0.6; }
  50%      { opacity: 1; }
}
.key-badge {
  display: inline-block; background: rgba(59, 130, 246, 0.12);
  color: #3b82f6; padding: 2px 8px; border-radius: 6px;
  font-family: monospace; font-size: 13px;
}

/* ═══ 人设设置（玻璃风） ═══ */
.persona-settings {
  flex: 1;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* ★ 角色Tab：模型选择行 */
.model-select-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.model-label {
  font-size: 13px;
  font-weight: 500;
  color: #131a26;
  flex-shrink: 0;
}
.persona-textarea {
  flex: 1;
  resize: none;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  background: rgba(255, 255, 255, 0.5);
  color: #131a26;
  font-size: 13px;
  line-height: 1.6;
  outline: none;
  font-family: inherit;
}
.persona-textarea:focus {
  border-color: rgba(59, 130, 246, 0.5);
}
.persona-actions {
  display: flex;
  justify-content: flex-end;
}

</style>