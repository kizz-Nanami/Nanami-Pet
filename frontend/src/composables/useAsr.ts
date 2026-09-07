// ★ 语音识别域：识别器回调、识别结果气泡（发送/取消）、打断监听（barge-in）
// 从 App.vue 原样搬运，行为不变——依赖经参数注入
import { ref, type Ref } from "vue"
import { RecognizerManager } from "../live2d/RecognizerManager"

export function useAsr(opts: {
  isSettings: boolean
  hintText: Ref<string>
  textInput: Ref<string>
  loading: Ref<boolean>
  ipc: any
  speech: any
  sendTextMessage: () => Promise<void>
}) {
  const { isSettings, hintText, textInput, loading, ipc, speech } = opts
  const recognizer = new RecognizerManager()
  const asrPending = ref("")  // 识别完成待发送的文字（气泡右侧显示"发送"按钮）
  recognizer.onStateChange = (s) => {
    if (isSettings) return
    if (s !== "idle") asrPending.value = ""
    // idle 时若还有待发送的识别结果，气泡保留显示供用户点击发送
    hintText.value = s === "listening" ? "聆听中…" : s === "processing" ? "识别中…" : asrPending.value
  }
  recognizer.onPartial = (text) => {
    if (isSettings) return
    // B2 实时中间结果：气泡随说话内容实时刷新
    hintText.value = `聆听中：${text}`
  }
  recognizer.onResult = (text) => {
    if (isSettings) { textInput.value = text; return }
    ipc?.send?.("asr-result", text)
    // 桌宠气泡保留识别结果 + 发送按钮，一键直发免开设置窗口
    asrPending.value = text
    hintText.value = text
  }

  // 气泡"发送"按钮：宠物窗口直接发起对话（对话状态经 IPC 双窗口同步）
  function sendAsr() {
    const text = asrPending.value.trim()
    if (!text) return // loading 时不再拦截：sendTextMessage 内部会打断旧流后发送
    asrPending.value = ""
    hintText.value = ""
    textInput.value = text
    ipc?.send?.("asr-sent", text) // 通知设置窗口清空输入框，防止重复发送
    void opts.sendTextMessage()
  }

  // 气泡"取消"按钮：识别错误时清空结果并关闭气泡，同时同步清空设置窗口输入框
  function cancelAsr() {
    asrPending.value = ""
    hintText.value = ""
    ipc?.send?.("asr-result", "") // 设置窗口收到空识别结果即清空输入框
  }
  recognizer.onError = (e) => {
    if (isSettings) return
    hintText.value = e.message
    setTimeout(() => { if (!recognizer.busy) hintText.value = "" }, 2000)
  }
  // ★ Barge-in 启动（防竞态）：监听启动完成时若播报已结束则撤销，避免残留常驻监听
  function armBargeIn() {
    void recognizer.startMonitor().then(() => {
      if (!speech.busy) recognizer.stopMonitor()
    })
  }
  // ★ 用户插嘴：宠物立即闭嘴并自动进入聆听状态
  recognizer.onBargeIn = () => {
    if (isSettings) return
    speech.stop()           // 清队列/停音频/清气泡（finishListener 内同步撤销监听）
    void recognizer.toggle() // 此刻 idle → 开始录音
  }
  return { recognizer, asrPending, sendAsr, cancelAsr, armBargeIn }
}
