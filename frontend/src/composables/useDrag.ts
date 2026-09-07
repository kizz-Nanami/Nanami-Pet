// ★ 窗口拖拽 + 鼠标穿透 + 触摸互动判定（宠物窗口）
// 从 App.vue 原样搬运：mousedown（拖拽区元素）/ mousemove+mouseup（document 级）
import { onMounted, onUnmounted, type Ref } from "vue"

export function useDrag(opts: {
  isSettings: boolean
  ipc: any
  posLocked: Ref<boolean>
  fullPass: Ref<boolean>
  live2dRef: Ref<any>
  onTouchPet: () => void
}) {
  const { isSettings, ipc, posLocked, fullPass, live2dRef } = opts
  let dragging = false
  let lastScreenX = 0
  let lastScreenY = 0
  // ★ 点击 vs 拖拽判定：mousedown 记录起点，mouseup 时位移小且时间短视为"触摸桌宠"
  let downX = 0
  let downY = 0
  let downTime = 0

  // ★ 鼠标穿透状态：true = 窗口点击穿透（指针在透明空白区），false = 正常交互（指针在桌宠上）
  let mousePassing = false
  function setPassthrough(pass: boolean) {
    if (pass === mousePassing || !ipc?.send) return
    mousePassing = pass
    ipc.send("set-ignore-mouse", pass)
  }

  //添加拖拽方法
  function onMouseDown(e: MouseEvent) {
    dragging = true
    setPassthrough(false) // 拖动期间绝不穿透，防止 mouseup 丢失导致拖拽卡死
    lastScreenX = e.screenX
    lastScreenY = e.screenY
    downX = e.clientX
    downY = e.clientY
    downTime = Date.now()
  }

  // ★ 眼球追踪：全局鼠标移动 → lookAt()
  function onMouseMove(e: MouseEvent) {
    live2dRef.value?.live2d?.lookAt(e.clientX, e.clientY)
    // ★ 穿透判定：命中桌宠拖拽区（模型包围盒）或气泡容器（含"✓"发送按钮）才拦截鼠标，
    //   其余透明区域放行点击。气泡不豁免的话窗口会被穿透，按钮永远点不到
    if (!isSettings && !fullPass.value) {
      // 全穿透（挂件）模式下跳过自动判定：主进程已整体 setIgnoreMouseEvents(true)
      const el = e.target as HTMLElement | null
      const onPet = dragging || !!el?.closest?.(".pet-drag-region") || !!el?.closest?.(".bubble-wrap")
      setPassthrough(!onPet)
    }
    if (dragging && ipc?.send) {
      // ★ 位置锁定：手势保留（触摸互动判定不变），只是不移动窗口
      if (posLocked.value) return
      const dx = e.screenX - lastScreenX
      const dy = e.screenY - lastScreenY
      lastScreenX = e.screenX
      lastScreenY = e.screenY
      //通过IPC把位置偏移量发送给electron主进程
      ipc.send("move-window", dx, dy)
    }
  }

  function onMouseUp(e: MouseEvent) {
    if (dragging) {
      // ★ 点击判定：位移 <5px 且按住 <500ms → 触发触摸互动（否则视为拖拽结束）
      const moved = Math.hypot(e.clientX - downX, e.clientY - downY)
      if (moved < 5 && Date.now() - downTime < 500) opts.onTouchPet()
    }
    dragging = false
  }

  // ★ 全局鼠标追踪：主进程轮询推送窗口外光标位置（窗口内 mousemove 被 forward 转发，窗口外收不到）
  function onGlobalCursor(_e: unknown, data: { x: number; y: number }) {
    live2dRef.value?.live2d?.lookAt(data.x, data.y)
  }

  onMounted(() => {
    document.addEventListener("mousemove", onMouseMove)
    document.addEventListener("mouseup", onMouseUp)
    ipc?.on?.("global-cursor", onGlobalCursor)
  })
  onUnmounted(() => {
    document.removeEventListener("mousemove", onMouseMove)
    document.removeEventListener("mouseup", onMouseUp)
    ipc?.removeListener?.("global-cursor", onGlobalCursor)
  })
  return { onMouseDown }
}
