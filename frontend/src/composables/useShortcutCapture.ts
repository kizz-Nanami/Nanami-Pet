// ★ 快捷键分组捕获（设置窗口）：voice=语音录音键 / screen=截屏键，各自独立配置
import { ref, onMounted, type Ref } from "vue"

export function useShortcutCapture(opts: { ipc: any; screenShortcut: Ref<string> }) {
  const { ipc, screenShortcut } = opts
  const currentShortcut = ref("Ctrl+Alt+L")
  const captureTarget = ref<"" | "voice" | "screen">("")
  const voiceCaptured = ref("")
  const screenCaptured = ref("")

  // 行内 badge 文案：未捕获=当前生效值；捕获中=提示或已捕获的新值
  function shortcutRowText(target: "voice" | "screen"): string {
    const active = captureTarget.value === target
    const captured = target === "voice" ? voiceCaptured.value : screenCaptured.value
    if (active && captured) return captured
    if (active) return "按下组合键…"
    return target === "voice" ? currentShortcut.value : screenShortcut.value
  }

  //监听按键，更新快捷键
  let captureListener: ((e: KeyboardEvent) => void) | null = null // ★ 持有引用：cancelCapture 时也能移除（修复监听器泄漏）
  function startCapture(target: "voice" | "screen") {
    //进入捕获状态
    captureTarget.value = target
    if (target === "voice") voiceCaptured.value = ""
    else screenCaptured.value = ""
    //监听按键事件，更新快捷键
    const onKeyDown = (e: KeyboardEvent) => {
      e.preventDefault(); e.stopPropagation()
      if (e.key === "Escape") {
        cancelCapture()
        window.removeEventListener("keydown", onKeyDown, true)
        return
      }
      if (["Control", "Alt", "Shift", "Meta"].includes(e.key)) return
      //如果按的是其他键，拼接成快捷键，必须组合键按下
      const parts: string[] = []
      if (e.ctrlKey) parts.push("Ctrl")
      if (e.altKey) parts.push("Alt")
      if (e.shiftKey) parts.push("Shift")
      if (e.metaKey) parts.push("Meta")
      parts.push(e.key.length === 1 ? e.key.toUpperCase() : e.key)
      // ★ 强制组合键：无修饰键时拒绝（单键全局快捷键会吞全局按键，如按 L 打字被拦截）
      if (parts.length < 2) return
      if (captureTarget.value === "voice") voiceCaptured.value = parts.join("+")
      else screenCaptured.value = parts.join("+")
      //捕获完成，退出监听（保留捕获值等待用户点"保存"）
      window.removeEventListener("keydown", onKeyDown, true)
      captureListener = null
    }
    captureListener = onKeyDown
    window.addEventListener("keydown", onKeyDown, true)
  }

  //取消捕获
  function cancelCapture() {
    // ★ 移除可能仍在挂着的捕获监听（用户点"取消"而非按 Esc 时，旧代码不清理会泄漏）
    if (captureListener) {
      window.removeEventListener("keydown", captureListener, true)
      captureListener = null
    }
    captureTarget.value = ""
    voiceCaptured.value = ""
    screenCaptured.value = ""
  }

  //保存语音快捷键
  function saveVoiceShortcut() {
    if (!voiceCaptured.value || !ipc?.send) return
    ipc.send("set-shortcut", voiceCaptured.value)
    currentShortcut.value = voiceCaptured.value
    cancelCapture()
  }

  //保存截屏快捷键
  function saveScreenShortcut() {
    if (!screenCaptured.value || !ipc?.send) return
    ipc.send("set-screen-shortcut", screenCaptured.value)
    screenShortcut.value = screenCaptured.value
    cancelCapture()
  }

  onMounted(() => {
    if (ipc?.sendSync) currentShortcut.value = ipc.sendSync("get-shortcut")
  })

  return {
    currentShortcut, captureTarget, voiceCaptured, screenCaptured,
    shortcutRowText, startCapture, cancelCapture, saveVoiceShortcut, saveScreenShortcut,
  }
}
