// ★ 语音识别管理器（方案B2：WebSocket 实时流式 + B1 非实时自动降级）
// B2 主路径：16kHz PCM 逐帧经后端 /ws/asr 桥接百炼 paraformer-realtime-v2，
//   partial 中间结果实时回调（onPartial）驱动桌宠气泡，stop 后收 final 定稿。
// B1 降级路径：WS 连接失败/识别出错时自动回退——整段攒 PCM → 编码 WAV
//   → POST /api/asr（百炼 qwen3-asr-flash），与 B1 版行为一致。
// 对外接口：toggle 启停、onStateChange 状态、onPartial 中间文字、onResult 定稿。

export type RecognizerState = "idle" | "listening" | "processing"

const ASR_API = "http://localhost:8888/api/asr"       // B1 非实时
const ASR_WS = "ws://localhost:8888/ws/asr"           // B2 实时
const SAMPLE_RATE = 16000
const MAX_MS = 60_000        // 单次录音上限
const MIN_MS = 500           // 短于此时长视为误触
const STOP_TIMEOUT_MS = 10_000 // 发送 stop 后等待 final 的兜底时长

// -- 打断监听（Barge-in）参数 --
const VAD_THRESHOLD = 0.05    // 人声 RMS 阈值：0.07 时稍远距离/音量小的插话漏检，放宽到 0.05
const VAD_HOLD_MS = 160       // 人声需持续高于阈值此时长才触发打断：250ms 会导致"停"这类单字短音不触发
const MONITOR_WARMUP_MS = 350 // 监听热身期：启动后此时长内不判定（防监听启动瞬间的杂音误触）

export class RecognizerManager {
  private state: RecognizerState = "idle"
  private toggling = false // 防止快速连按导致并发启停

  // -- 音频采集（两种模式共用） --
  private ctx: AudioContext | null = null
  private stream: MediaStream | null = null
  private processor: ScriptProcessorNode | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private startTime = 0
  private maxTimer: number | null = null

  // -- B2 WS 实时模式 --
  private ws: WebSocket | null = null
  private wsMode = false           // 当前会话是否处于 WS 模式（false = B1 降级）
  private wsFinal = false          // 是否已收到 final
  private wsStarted = false        // 后端任务是否已就绪（start 指令发出即置位）
  private stopWaiter: number | null = null

  // -- B1 攒帧（WS 未就绪期间也持续攒，保证降级无缝） --
  private chunks: Float32Array[] = []

  // -- 回调 --
  onStateChange: ((s: RecognizerState) => void) | null = null
  onPartial: ((text: string) => void) | null = null   // B2 中间结果（实时刷新气泡）
  onResult: ((text: string) => void) | null = null    // 定稿结果（B2 final / B1 HTTP 响应）
  onError: ((e: Error) => void) | null = null

  private setState(s: RecognizerState): void {
    this.state = s
    this.onStateChange?.(s)
  }

  get busy(): boolean { return this.state !== "idle" }

  /** 快捷键触发：空闲→开始录音；录音中→停止并识别 */
  async toggle(): Promise<void> {
    if (this.toggling) return
    this.toggling = true
    try {
      if (this.state === "idle") await this._start()
      else await this._stopAndRecognize()
    } finally {
      this.toggling = false
    }
  }

  // -- 开始：采集 + 尝试建立 WS --

  private async _start(): Promise<void> {
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }
      })
    } catch {
      this.onError?.(new Error("无法访问麦克风，请检查系统权限"))
      return
    }
    // 直接以 16kHz 创建上下文，省去重采样
    this.ctx = new AudioContext({ sampleRate: SAMPLE_RATE })
    if (this.ctx.state === "suspended") await this.ctx.resume()
    this.chunks = []
    this.wsMode = false
    this.wsFinal = false
    this.wsStarted = false
    this.source = this.ctx.createMediaStreamSource(this.stream)
    this.processor = this.ctx.createScriptProcessor(2048, 1, 1) // 2048采样@16kHz = 128ms/帧
    this.processor.onaudioprocess = (e) => this._onAudio(e)
    this.source.connect(this.processor)
    this.processor.connect(this.ctx.destination)
    this.startTime = Date.now()
    this.maxTimer = window.setTimeout(() => { void this.toggle() }, MAX_MS)

    // 先进入 listening（B1 兜底形态），WS 建立成功后自动切换到实时模式
    this.setState("listening")
    this._connectWs()
  }

  // 采集回调：WS 就绪则逐帧发送，否则攒帧（供 B1 降级使用）
  private _onAudio(e: AudioProcessingEvent): void {
    const f = e.inputBuffer.getChannelData(0)
    if (this.wsMode && this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(this._floatToInt16(f))
    } else {
      this.chunks.push(new Float32Array(f))
    }
  }

  // 建立 WS 并发起识别任务；失败则静默保持 B1 攒帧
  private _connectWs(): void {
    let ws: WebSocket
    try {
      ws = new WebSocket(ASR_WS)
      ws.binaryType = "arraybuffer"
    } catch {
      return // wsMode 保持 false → B1
    }
    this.ws = ws
    // 连接超时兜底：2s 内没就绪即降级（WS 对象置空，后续按 B1 走）
    const timeout = window.setTimeout(() => {
      if (this.ws === ws && ws.readyState !== WebSocket.OPEN) {
        try { ws.close() } catch { /* ignore */ }
        if (this.ws === ws) this.ws = null
      }
    }, 2000)
    ws.onopen = () => {
      clearTimeout(timeout)
      ws.send(JSON.stringify({ type: "start" }))
      this.wsStarted = true
    }
    ws.onmessage = (ev) => {
      let msg: any
      try { msg = JSON.parse(ev.data) } catch { return }
      if (msg.type === "ready") {
        // 后端识别任务已就绪：切换到 WS 逐帧发送模式（丢弃攒下的帧，从此实时发送）
        if (!this.wsMode) {
          this.wsMode = true
          this.chunks = []
        }
      } else if (msg.type === "partial") {
        if (!this.wsMode) {
          this.wsMode = true
          this.chunks = []
        }
        this.onPartial?.(String(msg.text ?? ""))
      } else if (msg.type === "final") {
        this.wsFinal = true
        this._finishWs(String(msg.text ?? ""))
      } else if (msg.type === "error") {
        // 后端识别出错：降级为 B1（采集从未中断，chunks 已持续累积）
        this._fallbackToB1(String(msg.message ?? "识别失败"))
      }
    }
    ws.onerror = () => { /* 连接层错误由 onclose/timeout 统一处理 */ }
    ws.onclose = () => {
      if (this.ws === ws) this.ws = null
      // 任务进行中连接断开且未收到结果 → 降级 B1
      if (this.state === "listening" && !this.wsFinal) this._fallbackToB1(null)
    }
  }

  // WS 出错后的降级：恢复攒帧模式，气泡回到"聆听中"
  private _fallbackToB1(reason: string | null): void {
    if (this.ws) {
      try { this.ws.close() } catch { /* ignore */ }
      this.ws = null
    }
    this.wsMode = false
    if (this.state === "listening") {
      this.onError?.(new Error(reason ? `${reason}，已切换为非实时识别` : "实时识别不可用，已切换为非实时识别"))
    }
  }

  // -- 停止：按模式分支 --

  private async _stopAndRecognize(): Promise<void> {
    const durationMs = Date.now() - this.startTime
    this._stopCapture()
    if (durationMs < MIN_MS) {
      this._teardownWs()
      this.chunks = []
      this.onError?.(new Error("说话时间太短"))
      this.setState("idle")
      return
    }

    if (this.wsMode && this.ws && this.wsStarted) {
      // ★ B2：通知后端结束任务，等待 final（onmessage 里完成收尾）
      this.setState("processing")
      this.ws.send(JSON.stringify({ type: "stop" }))
      this.stopWaiter = window.setTimeout(() => {
        // final 超时：按错误收尾
        this._teardownWs()
        this.onError?.(new Error("识别超时，请重试"))
        this.setState("idle")
      }, STOP_TIMEOUT_MS)
      return
    }

    // ★ B1：整段 WAV 一次性识别
    this._teardownWs()
    if (this.chunks.length === 0) {
      this.onError?.(new Error("没有录到声音"))
      this.setState("idle")
      return
    }
    this.setState("processing")
    const wav = this._encodeWav(this._mergeChunks())
    this.chunks = []
    try {
      const res = await fetch(ASR_API, {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: wav
      })
      if (!res.ok) throw new Error(`识别服务不可用 (${res.status})`)
      const data = await res.json()
      const text = (data.text || "").trim()
      if (!text) throw new Error("没有听清，请再试一次")
      this.onResult?.(text)
    } catch (e: any) {
      this.onError?.(e instanceof Error ? e : new Error(String(e)))
    } finally {
      this.setState("idle")
    }
  }

  // B2 收到 final 或超时后的统一收尾
  private _finishWs(text: string): void {
    if (this.stopWaiter !== null) {
      clearTimeout(this.stopWaiter)
      this.stopWaiter = null
    }
    this._teardownWs()
    this.chunks = []
    this.setState("idle")
    const t = text.trim()
    if (t) this.onResult?.(t)
    else this.onError?.(new Error("没有听清，请再试一次"))
  }

  private _teardownWs(): void {
    if (this.ws) {
      try { this.ws.close() } catch { /* ignore */ }
      this.ws = null
    }
    this.wsMode = false
  }

  // -- 打断监听（Barge-in）：TTS 播报期间低功耗监听麦克风，检测用户插嘴 --
  private monActive = false
  private monCtx: AudioContext | null = null
  private monStream: MediaStream | null = null
  private monSource: MediaStreamAudioSourceNode | null = null
  private monAnalyser: AnalyserNode | null = null
  private monRafId: number | null = null
  private monStartAt = 0   // 监听启动时刻（热身期基准）
  private monLoudSince = 0 // 人声持续高于阈值的起始时刻；0=当前低于阈值
  /** 检测到用户插嘴时回调（监听已自动停止，由外部决定打断播报/开始录音） */
  onBargeIn: (() => void) | null = null

  /** 开启打断监听（幂等）；正式录音中不开启。启动完成后 resolve */
  async startMonitor(): Promise<void> {
    if (this.monActive || this.busy) return
    try {
      // echoCancellation 消除同页面 Audio 元素播出的宠物 TTS 回声，防止自打断
      this.monStream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }
      })
    } catch {
      return // 麦克风不可用：放弃监听，不影响正常播报
    }
    this.monCtx = new AudioContext({ sampleRate: SAMPLE_RATE })
    if (this.monCtx.state === "suspended") await this.monCtx.resume()
    this.monSource = this.monCtx.createMediaStreamSource(this.monStream)
    this.monAnalyser = this.monCtx.createAnalyser()
    this.monAnalyser.fftSize = 1024
    this.monSource.connect(this.monAnalyser) // 只分析不输出，无回放声音
    this.monStartAt = Date.now()
    this.monLoudSince = 0
    this.monActive = true

    const buf = new Uint8Array(this.monAnalyser.fftSize)
    const loop = () => {
      if (!this.monActive || !this.monAnalyser) return
      this.monAnalyser.getByteTimeDomainData(buf)
      let sum = 0
      for (let i = 0; i < buf.length; i++) {
        const d = (buf[i] - 128) / 128
        sum += d * d
      }
      const rms = Math.sqrt(sum / buf.length)
      const now = Date.now()
      if (rms >= VAD_THRESHOLD) {
        if (this.monLoudSince === 0) this.monLoudSince = now
        // 热身期之外的持续人声 → 触发打断
        if (this.monLoudSince - this.monStartAt >= MONITOR_WARMUP_MS
            && now - this.monLoudSince >= VAD_HOLD_MS) {
          this.stopMonitor()
          this.onBargeIn?.()
          return
        }
      } else {
        this.monLoudSince = 0
      }
      this.monRafId = requestAnimationFrame(loop)
    }
    this.monRafId = requestAnimationFrame(loop)
  }

  /** 停止打断监听并释放采集资源（幂等） */
  stopMonitor(): void {
    if (!this.monActive && this.monRafId === null && !this.monCtx) return
    this.monActive = false
    if (this.monRafId !== null) {
      cancelAnimationFrame(this.monRafId)
      this.monRafId = null
    }
    try { this.monSource?.disconnect() } catch { /* ignore */ }
    this.monStream?.getTracks().forEach((t) => t.stop())
    void this.monCtx?.close().catch(() => {})
    this.monSource = null
    this.monAnalyser = null
    this.monStream = null
    this.monCtx = null
    this.monLoudSince = 0
  }

  // -- 采集资源 --

  private _stopCapture(): void {
    if (this.maxTimer !== null) {
      clearTimeout(this.maxTimer)
      this.maxTimer = null
    }
    try { this.processor?.disconnect() } catch { /* ignore */ }
    try { this.source?.disconnect() } catch { /* ignore */ }
    this.stream?.getTracks().forEach((t) => t.stop())
    void this.ctx?.close().catch(() => {})
    this.processor = null
    this.source = null
    this.stream = null
    this.ctx = null
  }

  // -- 音频工具 --

  // Float32 [-1,1] → Int16LE ArrayBuffer（后端/百炼要求的 PCM 格式）
  private _floatToInt16(f: Float32Array): ArrayBuffer {
    const buf = new ArrayBuffer(f.length * 2)
    const view = new DataView(buf)
    for (let i = 0; i < f.length; i++) {
      const s = Math.max(-1, Math.min(1, f[i]))
      view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true)
    }
    return buf
  }

  private _mergeChunks(): Float32Array {
    const total = this.chunks.reduce((n, c) => n + c.length, 0)
    const out = new Float32Array(total)
    let off = 0
    for (const c of this.chunks) {
      out.set(c, off)
      off += c.length
    }
    return out
  }

  // Float32 PCM → 16bit 单声道 WAV（仅 B1 降级路径使用）
  private _encodeWav(samples: Float32Array): Blob {
    const buf = new ArrayBuffer(44 + samples.length * 2)
    const view = new DataView(buf)
    const writeStr = (off: number, s: string) => {
      for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i))
    }
    writeStr(0, "RIFF")
    view.setUint32(4, 36 + samples.length * 2, true)
    writeStr(8, "WAVE")
    writeStr(12, "fmt ")
    view.setUint32(16, 16, true)          // fmt chunk 长度
    view.setUint16(20, 1, true)           // PCM
    view.setUint16(22, 1, true)           // 单声道
    view.setUint32(24, SAMPLE_RATE, true)
    view.setUint32(28, SAMPLE_RATE * 2, true) // 字节率
    view.setUint16(32, 2, true)           // 块对齐
    view.setUint16(34, 16, true)          // 位深
    writeStr(36, "data")
    view.setUint32(40, samples.length * 2, true)
    let off = 44
    for (let i = 0; i < samples.length; i++, off += 2) {
      const s = Math.max(-1, Math.min(1, samples[i]))
      view.setInt16(off, s < 0 ? s * 0x8000 : s * 0x7fff, true)
    }
    return new Blob([buf], { type: "audio/wav" })
  }
}
