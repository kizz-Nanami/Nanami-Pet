// ★ 语音设置状态（设置窗口编辑，变更经 IPC 同步给宠物窗口 + 主进程持久化）
import { ref, watch } from "vue"
import { modelConfig } from "../config"

export function useTtsSettings(opts: { isSettings: boolean; ipc: any; speech: any }) {
  const { isSettings, ipc, speech } = opts
  const ttsEnabled = ref(modelConfig.ttsEnabled)
  const ttsRate = ref(1.0)
  const selectedVoice = ref("")
  const voiceOptions = ref<{ name: string; lang: string }[]>([])
  //★ 启动时从主进程加载持久化的 TTS 配置（开关/语速/音色），重启后仍生效
  if (ipc?.sendSync) {
    const cfg = ipc.sendSync("get-tts-config") as { enabled?: boolean; rate?: number; voice?: string }
    if (typeof cfg.enabled === "boolean") ttsEnabled.value = cfg.enabled
    if (cfg.rate) ttsRate.value = cfg.rate
    if (cfg.voice) selectedVoice.value = cfg.voice
    speech.setRate(ttsRate.value)
    if (selectedVoice.value) speech.setVoiceByName(selectedVoice.value)
    //注意：enabled 的应用见下方 tts-config-changed 监听处的集中处理
  }
  //★ 配置变更统一出口：本地应用（若是宠物窗口）+ IPC 广播（另一窗口应用 + 持久化）
  function applyTtsConfig(patch: { enabled?: boolean; rate?: number; voice?: string }) {
    if (ipc?.send) ipc.send("set-tts-config", patch)
    //本地立即应用（设置窗口本地实例是静音的，无副作用；宠物窗口直接生效）
    if (patch.enabled !== undefined) speech.setEnabled(isSettings ? false : patch.enabled)
    if (patch.rate !== undefined) speech.setRate(patch.rate)
    if (patch.voice !== undefined) speech.setVoiceByName(patch.voice)
  }
  //★ 监听另一窗口改的配置（自己发出的变更主进程也会广播回来，重复应用幂等无妨）
  ipc?.on?.("tts-config-changed", (_e: any, cfg: { enabled?: boolean; rate?: number; voice?: string }) => {
    if (cfg.enabled !== undefined) {
      ttsEnabled.value = cfg.enabled
      speech.setEnabled(isSettings ? false : cfg.enabled) // 设置窗口实例保持静音
    }
    if (cfg.rate !== undefined) { ttsRate.value = cfg.rate; speech.setRate(cfg.rate) }
    if (cfg.voice !== undefined) speech.setVoiceByName(cfg.voice)
  })
  //音色列表是异步加载的，切到语音Tab时拉取一次
  function refreshVoices() {
    const opts = speech.getVoiceOptions()
    voiceOptions.value = opts
    if (opts.length && !selectedVoice.value) {
      selectedVoice.value = opts.find((v: { lang: string }) => v.lang.startsWith("zh"))?.name ?? opts[0].name
    }
  }
  function toggleTts() {
    ttsEnabled.value = !ttsEnabled.value
    applyTtsConfig({ enabled: ttsEnabled.value })
  }
  function onRateChange() {
    applyTtsConfig({ rate: ttsRate.value })
  }
  //选择音色时应用到语音管理器并广播
  watch(selectedVoice, (name) => {
    if (name) applyTtsConfig({ voice: name })
  })
  return { ttsEnabled, ttsRate, selectedVoice, voiceOptions, refreshVoices, toggleTts, onRateChange }
}
