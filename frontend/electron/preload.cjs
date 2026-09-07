// ★ 安全桥：contextIsolation 下向渲染层暴露白名单化 IPC 接口
// 渲染层通过 window.electronAPI 访问，不再拥有 Node 环境（修复 RCE 风险面）
const { contextBridge, ipcRenderer } = require("electron")

// 允许渲染层使用的 channel 白名单（与主进程注册的 IPC 一一对应）
const SEND_CHANNELS = new Set([
  "asr-result", "asr-sent", "pet-emotion", "set-pos-locked", "set-model-config",
  "set-ignore-mouse", "move-window", "resize-pet-window", "conv-update",
  "set-shortcut", "set-screen-shortcut", "set-persona", "set-tts-config",
  "set-visual-mem-config", "speech-state", "speech-stop-req",
  "ai-config-changed", "set-full-pass",
])
const SEND_SYNC_CHANNELS = new Set([
  "get-model-config", "list-models", "get-conversation", "get-shortcut",
  "get-pos-locked", "get-screen-shortcut", "get-persona", "get-tts-config",
  "get-full-pass",
])
const INVOKE_CHANNELS = new Set(["tool-execute", "edge-tts", "get-visual-mem-config"])
const ON_CHANNELS = new Set([
  "model-changed", "conv-update", "toggle-recording", "pet-emotion",
  "pos-locked-changed", "full-pass-changed", "screen-captured", "asr-result", "asr-sent", "tts-config-changed",
  "ai-config-changed", "open-ai-config", "global-cursor", "speech-state", "speech-stop-req",
])

// channel -> Map<原始回调, 包装回调>，支持 removeListener / removeAllListeners
const listeners = new Map()

contextBridge.exposeInMainWorld("electronAPI", {
  send: (channel, ...args) => {
    if (!SEND_CHANNELS.has(channel)) throw new Error("IPC channel not allowed: " + channel)
    ipcRenderer.send(channel, ...args)
  },
  sendSync: (channel) => {
    if (!SEND_SYNC_CHANNELS.has(channel)) throw new Error("IPC channel not allowed: " + channel)
    return ipcRenderer.sendSync(channel)
  },
  invoke: (channel, ...args) => {
    if (!INVOKE_CHANNELS.has(channel)) throw new Error("IPC channel not allowed: " + channel)
    return ipcRenderer.invoke(channel, ...args)
  },
  // 包装回调签名保持 (event, data) 形态（event 恒为 null），渲染层业务代码无需改动
  on: (channel, cb) => {
    if (!ON_CHANNELS.has(channel)) throw new Error("IPC channel not allowed: " + channel)
    const wrapped = (_e, data) => cb(null, data)
    if (!listeners.has(channel)) listeners.set(channel, new Map())
    listeners.get(channel).set(cb, wrapped)
    ipcRenderer.on(channel, wrapped)
  },
  removeListener: (channel, cb) => {
    const m = listeners.get(channel)
    const wrapped = m?.get(cb)
    if (wrapped) {
      ipcRenderer.removeListener(channel, wrapped)
      m.delete(cb)
    }
  },
  removeAllListeners: (channel) => {
    if (!ON_CHANNELS.has(channel)) throw new Error("IPC channel not allowed: " + channel)
    ipcRenderer.removeAllListeners(channel)
    listeners.delete(channel)
  },
})
