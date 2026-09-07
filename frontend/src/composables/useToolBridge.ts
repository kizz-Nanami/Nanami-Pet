// ★ AI 工具桥：连后端 /ws/electron，AI 需要操作电脑（打开网页/本地应用）时经此执行
// 链路：后端工具方法 → 本 WS 下发 {id,action,args} → 渲染进程 invoke 主进程执行 → 回传 {id,ok,result}
import { onMounted, onUnmounted } from "vue"

export function useToolBridge(opts: { isSettings: boolean; ipc: any }) {
  const { isSettings, ipc } = opts
  let toolWs: WebSocket | null = null
  let toolWsRetry: ReturnType<typeof setTimeout> | null = null

  function connectToolBridge() {
    if (isSettings) return // 只有宠物窗口承担工具执行
    try {
      toolWs = new WebSocket("ws://localhost:8888/ws/electron")
    } catch {
      scheduleToolReconnect()
      return
    }
    toolWs.onmessage = async (ev) => {
      let msg: { id: string; action: string; args?: any }
      try { msg = JSON.parse(ev.data) } catch { return }
      let result = "执行失败"
      try {
        result = await ipc.invoke("tool-execute", msg.action, msg.args ?? {})
      } catch (e: any) {
        result = "执行失败：" + (e?.message ?? e)
      }
      try {
        toolWs?.send(JSON.stringify({ id: msg.id, ok: true, result: String(result) }))
      } catch { /* 桥已断开，后端会按超时收尾 */ }
    }
    toolWs.onclose = () => { toolWs = null; scheduleToolReconnect() }
    toolWs.onerror = () => { try { toolWs?.close() } catch { /* noop */ } }
  }

  function scheduleToolReconnect() {
    if (isSettings || toolWsRetry) return
    toolWsRetry = setTimeout(() => { toolWsRetry = null; connectToolBridge() }, 5000)
  }

  onMounted(connectToolBridge)
  onUnmounted(() => {
    if (toolWsRetry) { clearTimeout(toolWsRetry); toolWsRetry = null }
    if (toolWs) { toolWs.onclose = null; try { toolWs.close() } catch { /* noop */ } toolWs = null }
  })
}
