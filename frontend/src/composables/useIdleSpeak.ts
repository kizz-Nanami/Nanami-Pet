// ★ 主动发言：空闲检测 + 时段问候 + 防重复（仅宠物窗口）；
// markInteract 供发送/触摸/录音等任意用户交互刷新空闲计时
import { onMounted, onUnmounted, type Ref } from "vue"
import { idleSpeakMinutes, idleLines, IDLE_RECENT } from "../config"

export function useIdleSpeak(opts: {
  isSettings: boolean
  speech: any
  loading: Ref<boolean>
  asrPending: Ref<string>
}) {
  const { isSettings, speech, loading, asrPending } = opts
  let lastInteract = Date.now()
  let idleTimer: number | null = null
  const recentIdleLines: string[] = []

  // 任何用户交互（发送/触摸/录音）都刷新空闲计时
  function markInteract() { lastInteract = Date.now() }

  // 从当前时段台词池挑一句（优先排除最近说过的，池耗尽时允许重复）
  function pickIdleLine(): string | null {
    const h = new Date().getHours()
    const slot = idleLines.find((s) => s.from <= s.to ? (h >= s.from && h < s.to) : (h >= s.from || h < s.to))
    if (!slot) return null
    const pool = slot.lines.filter((l) => !recentIdleLines.includes(l))
    const candidates = pool.length ? pool : slot.lines
    const line = candidates[Math.floor(Math.random() * candidates.length)]
    recentIdleLines.push(line)
    if (recentIdleLines.length > IDLE_RECENT) recentIdleLines.shift()
    return line
  }

  onMounted(() => {
    // 仅宠物窗口启动空闲检测；每 30s 检查一次，空闲超时且不在说话时插一句
    if (!isSettings && idleSpeakMinutes > 0) {
      idleTimer = window.setInterval(() => {
        if (speech.busy || loading.value || asrPending.value) return
        if (Date.now() - lastInteract < idleSpeakMinutes * 60000) return
        const line = pickIdleLine()
        if (line) {
          speech.enqueue(line)
          lastInteract = Date.now() // 说完重新计时，避免下个检查周期连环触发
        }
      }, 30 * 1000)
    }
  })
  onUnmounted(() => {
    if (idleTimer !== null) { clearInterval(idleTimer); idleTimer = null }
  })
  return { markInteract, pickIdleLine }
}
