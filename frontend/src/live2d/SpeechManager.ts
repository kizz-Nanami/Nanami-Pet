// ★ 语音播报管理器（方案B：真实 TTS API + 真实音频播放 + 真实口型同步）
// 职责：句子队列、串行朗读、朗读期间驱动口型、发出 onStart 事件
// 设计：朗读节奏是"权威时间线"——App.vue 订阅 onStart 来显示气泡，
//       文字/语音/口型天然同步。对外接口与方案A完全一致。
// 三级降级链：API TTS 失败（无 key/网络错/余额不足）→ 本句用浏览器
//       speechSynthesis → 再失败 → 假朗读（估时器）。事件流始终不变。

// 口型驱动回调：参数为张嘴幅度 0~1
type MouthDriver = (open: number) => void
// 句子开始回调（真朗读=实际发声瞬间，假朗读=计时开始瞬间）
type StartListener = (text: string) => void
// 播放全部结束回调（队列耗尽或被 stop 打断时触发一次）
type FinishListener = () => void

export interface VoiceOption {
  name: string
  lang: string
}

// TTS 来源：api=后端转发 SiliconFlow CosyVoice2（真实音频+真实口型），
//           browser=系统语音包（方案A）
export type TtsProvider = "api" | "browser"

const TTS_API = "http://localhost:8888/api/tts"
const SENTENCE_GAP_MS = 300 // 句间停顿时长（ms），营造自然说话节奏

// ★ TTS 引擎（配置面板"语音合成"组选择）：dashscope=百炼原生 / openai=OpenAI 兼容 /audio/speech（硅基等）/
//   edge=Edge-TTS 微软免费引擎（主进程合成，无 key 无额度）
export type TtsEngine = "dashscope" | "openai" | "edge"
let ttsEngine: TtsEngine = "dashscope"
let ttsEngineVoice = "" // edge 引擎音色（如 zh-CN-XiaoxiaoNeural）
async function refreshTtsEngine() {
  try {
    const r = await fetch("http://localhost:8888/api/ai-config")
    if (!r.ok) return
    const cfg = await r.json()
    const p = cfg?.tts?.provider
    if (p === "openai" || p === "edge" || p === "dashscope") ttsEngine = p
    ttsEngineVoice = cfg?.tts?.voice || ""
  } catch { /* 后端未启动时保持缓存 */ }
}
export { refreshTtsEngine }
void refreshTtsEngine()

export class SpeechManager {
  private queue: string[] = []
  private speaking = false            // 队列是否在运转（含假朗读）
  private enabled = true              // TTS 开关：false 时走假朗读（估时）
  private provider: TtsProvider       // 首选 TTS 来源
  private mouthDriver: MouthDriver | null = null
  private startListener: StartListener | null = null
  private finishListener: FinishListener | null = null
  private mouthTimer: number | null = null
  private fakeTimer: number | null = null  // 假朗读定时器
  private voice: SpeechSynthesisVoice | null = null
  private rate = 1.0                  // 语速
  private voices: SpeechSynthesisVoice[] = []

  // -- 真实音频播放的资源（方案B）--
  private audio: HTMLAudioElement | null = null
  private audioUrl: string | null = null   // blob objectURL（用完回收）
  private audioCtx: AudioContext | null = null
  private analyser: AnalyserNode | null = null
  private rafId: number | null = null      // 口型采样循环
  // 预取缓存：播放当前句时提前合成下一句，消除句间网络等待
  private _prefetch: { text: string; url: string } | null = null
  private gapTimer: number | null = null  // 句间停顿定时器
  // ★ 播代会话代数：stop()/新句子开始时递增；旧异步链的收尾动作据此识别自己已过期
  private _gen = 0
  // ★ 当前播放 Promise 的 resolve：stop() 释放音频时手动放行，防止 await 永久挂起（内存泄漏）
  private _playCtl: { resolve: () => void } | null = null

  constructor(provider: TtsProvider = "api") {
    this.provider = provider
    // 中文音色是异步加载的，监听 voiceschanged 才能拿到（降级时用）
    const loadVoices = () => {
      this.voices = window.speechSynthesis.getVoices()
      if (!this.voice) this.voice = this.voices.find((v) => v.lang.startsWith("zh")) ?? null
    }
    loadVoices() // 部分环境首次调用就能返回
    window.speechSynthesis.onvoiceschanged = loadVoices
  }

  // -- 配置 --
  setEnabled(on: boolean): void {
    if (this.enabled === on) return
    this.enabled = on
    if (!on && this.speaking) this.stop() // 从开切到关：中断当前朗读（后续句子走假朗读）
  }
  setRate(r: number): void {
    this.rate = Math.min(2, Math.max(0.5, r))
    // 实时生效：正在播放的音频直接变速
    if (this.audio) this.audio.playbackRate = this.rate
  }
  setVoiceByName(name: string): void {
    this.voice = this.voices.find((v) => v.name === name) ?? this.voice
  }
  /** 列出可用音色（设置界面用，降级链的浏览器TTS音色） */
  getVoiceOptions(): VoiceOption[] {
    return this.voices.map((v) => ({ name: v.name, lang: v.lang }))
  }

  // -- 挂载回调 --
  setMouthDriver(fn: MouthDriver | null): void {
    this.mouthDriver = fn
  }
  setStartListener(fn: StartListener | null): void {
    this.startListener = fn
  }
  setFinishListener(fn: FinishListener | null): void {
    this.finishListener = fn
  }

  get busy(): boolean { return this.speaking }

  /** 句子入队；空闲时立即开始 */
  enqueue(text: string): void {
    const t = text.trim()
    if (!t) return
    this.queue.push(t)
    console.log(`[TTS-D] enqueue "${t.slice(0, 16)}" speaking=${this.speaking} queue=${this.queue.length}`)
    if (!this.speaking) this._next()
  }

  /** 立即闭嘴：清空队列并终止（假朗读/真实音频/浏览器TTS全部终止） */
  stop(): void {
    console.log(`[TTS-D] stop() called, speaking=${this.speaking}, stack: ${new Error().stack?.split("\n").slice(2, 4).join(" <- ")}`)
    this.queue = []
    this._gen++ // ★ 使旧异步链的收尾（降级/句间停顿）全部失效
    if (this.gapTimer !== null) {
      clearTimeout(this.gapTimer)
      this.gapTimer = null
    }
    if (!this.speaking) return
    this.speaking = false
    this._dropPrefetch()
    this._releaseAudio()             // 停止真实音频播放并回收资源
    try { window.speechSynthesis.cancel() } catch { /* 语音引擎未就绪时忽略 */ }
    if (this.fakeTimer !== null) {
      clearTimeout(this.fakeTimer)
      this.fakeTimer = null
    }
    this._stopMouth()
    this.finishListener?.() // 打断也视为结束（前端清气泡）
  }

  // 取下一句；队列空则结束
  private _next(): void {
    const text = this.queue.shift()
    if (text === undefined) {
      this.speaking = false
      this._stopMouth()
      this.finishListener?.() // 全部播完：通知前端清气泡
      return
    }
    this.speaking = true
    this._gen++ // ★ 新句子开启新代次：上一句残留的异步收尾不再生效
    console.log(`[TTS-D] _next -> onStart "${text.slice(0, 16)}"`)
    this.startListener?.(text) // 气泡在"开始说这句"的瞬间显示

    if (!this.enabled) {
      this._fakeSpeak(text)
      return
    }
    if (this.provider === "api") this._speakWithFallback(text)
    else this._speakBrowser(text)
  }

  // ★ 降级链调度：API失败 → 浏览器TTS → 假朗读（同一句内最多降两级）
  private async _speakWithFallback(text: string): Promise<void> {
    const gen = this._gen // 捕获当前代次：stop()/新句子会使其失效
    console.log(`[TTS-D] speakWithFallback gen=${gen} "${text.slice(0, 16)}"`)
    try {
      // 上一句播放期间预取的音频命中 → 直接播，句间零等待
      const prefetched = this._takePrefetch(text)
      if (prefetched) {
        console.log(`[TTS-D] prefetch hit`)
        await this._playUrl(prefetched)
      } else {
        await this._speakApi(text)
      }
      this._sentenceDone(gen)
      return
    } catch (e) {
      console.log(`[TTS-D] api/browser level1 failed: ${e}`)
      this._dropPrefetch() // 落到下一级
      if (gen !== this._gen) return // ★ 已被打断/被新会话取代：不再降级，直接结束
    }
    try {
      await this._speakBrowser(text)
      this._sentenceDone(gen)
      return
    } catch (e) {
      console.log(`[TTS-D] browser tts failed: ${e}`) /* 落到假朗读 */
    }
    if (gen !== this._gen) return // ★ 已被打断：不再启动假朗读定时器
    this._fakeSpeak(text)
  }

  private _sentenceDone(gen: number = this._gen): void {
    this._stopMouth()
    if (gen !== this._gen) return // ★ 旧代次的收尾（被打断后异步链才走到这里）：直接丢弃
    if (!this.speaking) return
    // 句间自然停顿：预取消除了合成等待后，补回符合说话节奏的间隙
    this.gapTimer = window.setTimeout(() => {
      this.gapTimer = null
      if (this.speaking) this._next() // stop() 会置 false，从而切断链
    }, SENTENCE_GAP_MS)
  }

  // ★ 合成一段文本为音频 blob URL（api 路径）：dashscope/openai 走后端，edge 走主进程 IPC。
  // 主链与预取共用，保证预取与实际播放的引擎一致
  private async _synthesizeUrl(text: string): Promise<string> {
    if (ttsEngine === "edge") {
      const api = (window as any).electronAPI
      if (!api?.invoke) throw new Error("edge-tts unavailable")
      const bytes: Uint8Array = await api.invoke("edge-tts", { text, voice: ttsEngineVoice })
      const blob = new Blob([bytes as BlobPart], { type: "audio/mpeg" })
      return URL.createObjectURL(blob)
    }
    const res = await fetch(TTS_API, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
      signal: AbortSignal.timeout(20000) // ★ 合成超时保护：TTS 挂起不再拖死整个朗读队列
    })
    console.log(`[TTS-D] tts api http ${res.status}`)
    const blob = await res.blob()
    if (!blob.type.startsWith("audio/") && blob.size < 512) {
      throw new Error("tts api returned non-audio body")
    }
    return URL.createObjectURL(blob)
  }

  // ★ 方案B主路径：后端转发百炼 TTS → 音频 → 播放（engine=edge 时主进程本地合成，不经后端）
  private async _speakApi(text: string): Promise<void> {
    await this._playUrl(await this._synthesizeUrl(text))
  }

  // 播放一段音频 blob URL：挂口型分析 → 播放 → 播放期间预取下一句
  private async _playUrl(url: string): Promise<void> {
    const audio = new Audio(url)
    audio.playbackRate = this.rate
    this.audio = audio
    this.audioUrl = url

    // ★ 事件监听必须先于 play() 挂载：极短音频可能在 play() resolve 前
    //   就已播完，后挂的 onended 永不触发 → await 永久挂起 → 队列死锁
    const playback = new Promise<void>((resolve, reject) => {
      // ★ 保存 resolve：stop() 释放音频时手动放行，防止 pause() 不触发事件导致 await 永久挂起
      this._playCtl = { resolve }
      audio.onended = () => { console.log("[TTS-D] audio onended"); this._releaseAudio(); resolve() }
      audio.onerror = () => { console.log("[TTS-D] audio onerror"); this._releaseAudio(); reject(new Error("audio playback error")) }
    })

    // 挂真实口型分析（AudioContext 被策略挂起时退回模拟口型，声音不受影响）
    const wired = await this._wireAnalyser(audio)
    if (wired) this._startMouthFromAnalyser()
    else this._startMouthFake()

    try {
      console.log("[TTS-D] audio.play() ...")
      await audio.play() // 播放失败（自动播放策略等）→ 释放资源后抛给降级链
      console.log("[TTS-D] audio.play() started")
    } catch (e) {
      this._releaseAudio()
      throw e
    }

    // 播放已开始：利用播放窗口后台合成下一句，本句播完即可无缝衔接
    this._prefetchNext()

    await playback
  }

  // 后台预取队列中的下一句；失败静默（届时正常走 _speakApi 主链）
  private _prefetchNext(): void {
    if (this._prefetch) return
    const next = this.queue[0]
    if (next === undefined || !this.enabled || this.provider !== "api") return
    void (async () => {
      try {
        // ★ 与主链共用同一合成路径：edge 引擎下也走主进程 IPC，不再固定打后端 /api/tts
        const url = await this._synthesizeUrl(next)
        // 合成期间用户可能已停止或队列已变：校验后才缓存（过期则回收 URL 防泄漏）
        if (this._prefetch || !this.speaking || this.queue[0] !== next) {
          try { URL.revokeObjectURL(url) } catch { /* ignore */ }
          return
        }
        this._prefetch = { text: next, url }
      } catch { /* 预取失败不影响主链 */ }
    })()
  }

  private _takePrefetch(text: string): string | null {
    if (this._prefetch && this._prefetch.text === text) {
      const url = this._prefetch.url
      this._prefetch = null
      return url
    }
    return null
  }

  private _dropPrefetch(): void {
    if (this._prefetch) {
      try { URL.revokeObjectURL(this._prefetch.url) } catch { /* ignore */ }
      this._prefetch = null
    }
  }

  // 建立 Audio → MediaElementSource → Analyser → destination 链
  // 返回 false 表示分析链不可用（此时不接链，音频走默认输出）
  private async _wireAnalyser(audio: HTMLAudioElement): Promise<boolean> {
    try {
      // 用局部变量持有，避免 TS 对类属性的空值窄化在 await 后失效
      let ctx = this.audioCtx
      let analyser = this.analyser
      if (!ctx || !analyser) {
        ctx = new AudioContext()
        analyser = ctx.createAnalyser()
        analyser.fftSize = 256
        analyser.smoothingTimeConstant = 0.5
        analyser.connect(ctx.destination)
        this.audioCtx = ctx
        this.analyser = analyser
      }
      if (ctx.state === "suspended") {
        await ctx.resume()
      }
      if (ctx.state !== "running") return false
      const src = ctx.createMediaElementSource(audio)
      src.connect(analyser)
      return true
    } catch {
      return false
    }
  }

  // ★ 真实口型：AnalyserNode 时域数据 → RMS 音量 → 平滑映射到 0~1
  private _startMouthFromAnalyser(): void {
    this._stopMouth()
    const analyser = this.analyser!
    const buf = new Uint8Array(analyser.fftSize)
    let smoothed = 0
    const loop = () => {
      if (!this.analyser) return
      analyser.getByteTimeDomainData(buf)
      let sum = 0
      for (let i = 0; i < buf.length; i++) {
        const d = (buf[i] - 128) / 128
        sum += d * d
      }
      const rms = Math.sqrt(sum / buf.length)
      const open = Math.min(1, rms * 4) // 人声 RMS 通常 0.05~0.3，放大到满幅
      smoothed += (open - smoothed) * 0.35 // 平滑，避免口型抖动
      this.mouthDriver?.(smoothed)
      this.rafId = requestAnimationFrame(loop)
    }
    this.rafId = requestAnimationFrame(loop)
  }

  // 浏览器 TTS（方案A实现，降级链第二级）；返回 Promise，onerror 时 reject
  private _speakBrowser(text: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const u = new SpeechSynthesisUtterance(text)
      u.lang = "zh-CN"
      if (this.voice) u.voice = this.voice
      u.rate = this.rate
      const done = () => { this._stopMouth(); resolve() }
      const fail = () => { this._stopMouth(); reject(new Error("speechSynthesis error")) }
      u.onend = done
      u.onerror = fail
      this._startMouthFake()
      try {
        window.speechSynthesis.speak(u)
      } catch {
        fail()
      }
    })
  }

  // 假朗读：TTS 关闭时用估时器维持同样的 onStart 节奏（中文~170ms/字，英文/数字~50ms/字）
  private _fakeSpeak(text: string): void {
    const cjk = (text.match(/[\u4e00-\u9fff]/g) || []).length
    const eng = (text.match(/[a-zA-Z0-9]/g) || []).length
    const ms = Math.max(1500, Math.min(8000, (cjk * 170 + eng * 50) / this.rate))
    this._startMouthFake()
    this.fakeTimer = window.setTimeout(() => {
      this.fakeTimer = null
      this._sentenceDone()
    }, ms)
  }

  // 模拟口型：无真实音频流可用时的兜底（~100ms 周期随机开合）
  private _startMouthFake(): void {
    this._stopMouth()
    this.mouthTimer = window.setInterval(() => {
      this.mouthDriver?.(0.15 + Math.random() * 0.7)
    }, 100)
  }

  private _stopMouth(): void {
    if (this.mouthTimer !== null) {
      clearInterval(this.mouthTimer)
      this.mouthTimer = null
    }
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId)
      this.rafId = null
    }
    this.mouthDriver?.(0)
  }

  // 停止并回收当前真实音频
  private _releaseAudio(): void {
    if (this.audio) {
      this.audio.onended = null
      this.audio.onerror = null
      this.audio.pause()
      this.audio = null
    }
    if (this.audioUrl) {
      URL.revokeObjectURL(this.audioUrl)
      this.audioUrl = null
    }
    // ★ 放行等待中的播放 Promise（stop() 打断时 pause 不触发 onended，需手动 resolve）；
    // Promise 已 settle 时再次 resolve 为无操作，正常结束路径也安全
    if (this._playCtl) {
      this._playCtl.resolve()
      this._playCtl = null
    }
  }
}
