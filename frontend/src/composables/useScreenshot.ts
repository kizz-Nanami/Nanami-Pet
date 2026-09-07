// ★ 截屏视觉感知：全局截屏快捷键触发主进程截屏后广播到两窗口，各自持有待发送截图，
// 下一条消息（语音 ✓ 或输入框）发送时附带 base64 图；60s 未发送自动清除防遗忘
import { ref, onMounted, onUnmounted } from "vue"

export function useScreenshot(opts: { ipc: any }) {
  const ipc = opts.ipc
  const pendingScreenshot = ref("")
  let shotTimeout: number | null = null
  const screenShortcut = ref("Ctrl+Alt+K")
  function clearScreenshot() {
    pendingScreenshot.value = ""
    if (shotTimeout !== null) { clearTimeout(shotTimeout); shotTimeout = null }
  }
  function onScreenCaptured(_e: any, p: { base64: string }) {
    clearScreenshot()
    pendingScreenshot.value = p.base64
    // 60s 后自动清除：避免截图"滞留"，后续无关消息误带图
    shotTimeout = window.setTimeout(clearScreenshot, 60_000)
  }
  onMounted(() => {
    // 两窗口都接收截图广播（各自持有，各自发送时附带）
    if (ipc?.on) {
      ipc.on("screen-captured", onScreenCaptured)
      screenShortcut.value = ipc.sendSync?.("get-screen-shortcut") || "Ctrl+Alt+K"
    }
  })
  onUnmounted(() => {
    if (shotTimeout !== null) { clearTimeout(shotTimeout); shotTimeout = null }
    ipc?.removeListener?.("screen-captured", onScreenCaptured)
  })
  return { pendingScreenshot, screenShortcut, clearScreenshot }
}
