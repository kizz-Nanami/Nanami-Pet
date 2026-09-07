// ★ 角色人设：启动时从主进程加载（宠物窗口发送消息时随请求携带，设置窗口负责编辑保存）
import { ref } from "vue"

export function usePersona(opts: { ipc: any }) {
  const { ipc } = opts
  const personaText = ref("")   // 已保存生效的人设
  const personaDraft = ref("")  // 编辑中的草稿（点保存才落盘）
  if (ipc?.sendSync) {
    personaText.value = ipc.sendSync("get-persona") || ""
    personaDraft.value = personaText.value
  }
  function savePersona() {
    personaText.value = personaDraft.value
    if (ipc?.send) ipc.send("set-persona", personaText.value)
  }
  return { personaText, personaDraft, savePersona }
}
