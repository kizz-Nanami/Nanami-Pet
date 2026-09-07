// ★ 对话记录同步：主进程历史加载、双窗口实时同步（回声抑制）、长期记忆上报
import { onMounted, onUnmounted, type Ref } from "vue"

export interface Message { role: "user" | "pet"; text: string }

export function useConversationSync(opts: {
  conversation: Ref<Message[]>
  speech: { enqueue: (t: string) => void }
  ipc: any
  cleanMsgTags: (m: Message) => Message
}) {
  const { conversation, speech, ipc, cleanMsgTags } = opts

  // 通知 Electron主进程 更新对话记录
  // ★ 回声抑制：主进程会把 conv-update 广播给所有窗口（含发送者自己），
  // 记录自己发出的次数，回声到达时只同步显示、不再入队朗读（否则每句播两遍）
  let pendingEcho = 0
  function notifyConvUpdate() {
    if (ipc?.send) {
      pendingEcho++
      const snapshot = JSON.parse(JSON.stringify(conversation.value))
      ipc.send("conv-update", snapshot)
      syncMemory(snapshot) // ★ 同步给后端长期记忆（删除也会同步，保证记忆随之修正）
    }
  }

  // ★ 长期记忆同步：把完整对话发给后端（fire-and-forget，失败静默）。
  // 后端哈希去重 + 防抖提炼：纯追加走增量提炼，检测到删除走全量重提炼
  const MEMORY_API = "http://localhost:8888/api/memory/sync"
  let memorySyncTimer: ReturnType<typeof setTimeout> | null = null
  let memoryPendingSnapshot: Message[] | null = null
  function syncMemory(snapshot: Message[]) {
    memoryPendingSnapshot = snapshot // 只保留最新一份，防抖期间覆盖
    if (memorySyncTimer) return
    memorySyncTimer = setTimeout(() => {
      memorySyncTimer = null
      const data = memoryPendingSnapshot
      memoryPendingSnapshot = null
      if (!data) return
      fetch(MEMORY_API, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: data })
      }).catch(() => { /* 后端未启动/网络异常时静默，不影响聊天 */ })
    }, 3000) // 3s 节流：SSE 期间每句都更新，压缩请求频率
  }

  onMounted(() => {
    // ★ 从主进程拿历史对话 + 监听实时同步
    //历史对话：从主进程获取历史对话记录 sendSync 阻塞当前线程，直到主进程返回
    if (ipc?.sendSync) {
      // ★ 历史消息兜底清理标签（conversation.json 中可能存有旧版未剥离的 [love:xx] 脏数据）
      conversation.value = (ipc.sendSync("get-conversation") || []).map(cleanMsgTags)
    }
    //实时同步：监听主进程发送的 conv-update 事件，更新对话记录
    //任何一个窗口调用 notifyConvUpdate() 都会触发这个事件
    ipc?.on?.("conv-update", (_e: any, msgs: Message[]) => {
      //记录更新之前的程度，用于判断哪些是新消息
      const prevLen = conversation.value.length
      conversation.value = [...msgs].map(cleanMsgTags)
      // ★ 回声抑制：自己刚广播出去的更新会原样弹回来，只同步显示不入队；
      // 只有"别的窗口新增的消息"（pendingEcho 为 0）才入队朗读
      if (pendingEcho > 0) {
        pendingEcho--
        return
      }
      // 取新增的 pet 消息，入队按朗读节奏逐句显示/朗读（仅在宠物窗口发声）
      for (let i = prevLen; i < msgs.length; i++) {
        const msg = msgs[i]
        if (msg && msg.role === "pet") speech.enqueue(msg.text)
      }
    })
  })
  onUnmounted(() => {
    ipc?.removeAllListeners?.("conv-update")
  })
  return { notifyConvUpdate }
}
