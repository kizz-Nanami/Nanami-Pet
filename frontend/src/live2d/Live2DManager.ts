import * as PIXI from "pixi.js"
import { Live2DModel } from "pixi-live2d-display/cubism4"
import type { PixiManager } from "./PixiManager"
import { modelParamOverrides } from "../config"

export const Param = {
  MouthOpenY: "ParamMouthOpenY",
  MouthForm: "ParamMouthForm",
  EyeBallX: "ParamEyeBallX",
  EyeBallY: "ParamEyeBallY",
  AngleX: "ParamAngleX",
  AngleY: "ParamAngleY",
  AngleZ: "ParamAngleZ",
  BodyAngleX: "ParamBodyAngleX",
} as const

export class Live2DManager {
  private _pixi: PixiManager
  private _model: Live2DModel | null = null
  private _currentModelName = ""
  private _currentExpressions: string[] = []   // ★ 当前模型可用的表情名列表（换模型后校验映射）
  private _scale = 0.18
  private _baseX = 0
  private _baseY = 0
  private _refW = 0   // ★ 首次加载时的画布参考尺寸：切换模型时按它做缩放保护，行为与首载一致
  private _refH = 0
  private _paramOverrides: Record<string, number> | null = null  // ★ 当前模型的特殊参数覆盖（如六初水印开关）

  // Smooth eye tracking state
  private _targetAngle = 0    // rad
  private _targetDist = 0     // 0..1
  private _curAngle = 0
  private _curDist = 0
  private _tickerFn: ((dt: number) => void) | null = null

  constructor(pixi: PixiManager) {
    this._pixi = pixi
    Live2DModel.registerTicker(PIXI.Ticker)
  }

  get loaded(): boolean { return this._model !== null }
  get modelName(): string { return this._currentModelName }
  get model(): Live2DModel | null { return this._model }
  /** 当前模型注册的表情名列表（model3.json 的 Expressions） */
  get expressions(): string[] { return this._currentExpressions }
  get scale(): number { return this._scale }
  get canvasRect(): DOMRect | null {
    return this._pixi.canvas.getBoundingClientRect()
  }

  /** 模型渲染区域顶部在画布中的 y 坐标（CSS 像素），模型未加载时返回 NaN */
  getVisualTop(): number {
    const b = this.getVisualBounds()
    return b ? b.top : NaN
  }

  /** 模型渲染包围盒（画布 CSS 像素），模型未加载时返回 null */
  getVisualBounds(): { left: number; top: number; width: number; height: number } | null {
    if (!this._model) return null
    try {
      const b = (this._model as any).getBounds?.()
      if (b && isFinite(b.x) && isFinite(b.y)) {
        return { left: b.x, top: b.y, width: b.width, height: b.height }
      }
    } catch { /* 走兜底计算 */ }
    // 兜底：anchor 为 (0.5, 0.5)，包围盒以模型中心对称
    return {
      left: this._model.x - this._model.width / 2,
      top: this._model.y - this._model.height / 2,
      width: this._model.width,
      height: this._model.height
    }
  }

  /** 缩小画布贴合模型：水平居中，顶部留 padTop（气泡空间），四周留 pad；宽度不小于 minWidth（容纳气泡）。返回新画布尺寸 */
  fitCanvas(padTop: number, padSide: number, padBottom: number, minWidth = 0): { width: number; height: number } | null {
    if (!this._model) return null
    const w = Math.max(Math.ceil(this._model.width + padSide * 2), minWidth)
    const h = Math.ceil(this._model.height + padTop + padBottom)
    // 更新基准位置：模型在新画布中水平居中、顶部留 padTop（center() 会恢复到这里）
    this._baseX = w / 2
    this._baseY = padTop + this._model.height / 2
    this._model.x = this._baseX
    this._model.y = this._baseY
    return { width: w, height: h }
  }

  async loadModel(folderName: string, modelFile?: string, options?: {
    scale?: number
    xMultiplier?: number
    yMultiplier?: number
  }): Promise<void> {
    if (options?.scale !== undefined) this._scale = options.scale
    const xMul = options?.xMultiplier ?? 1 / 2.4
    const yMul = options?.yMultiplier ?? 0.65
    if (this._model) {
      this._pixi.app.stage.removeChild(this._model)
      this._model.destroy()
      this._model = null
    }

    const base = import.meta.env.BASE_URL
    const fileName = modelFile || `${folderName}.model3.json`
    const url = `${base}live2d/${folderName}/${fileName}`

    this._model = await Live2DModel.from(url, { autoInteract: false })
    this._model.eventMode = "none"
    this._model.interactiveChildren = false

    // ★ 自适应缩放保护：模型缩放后若超出参考画布（首次加载时的尺寸）则自动缩小到完整可显示
    if (this._refW === 0) { this._refW = this._pixi.width; this._refH = this._pixi.height }
    const rawW = this._model.width   // scale=1 时的原始宽度
    const rawH = this._model.height
    if (rawW > 0 && rawH > 0) {
      const fitScale = Math.min(this._refW / rawW, this._refH / rawH)
      this._scale = Math.min(this._scale, fitScale)
    }
    this._model.scale.set(this._scale)
    this._model.anchor.set(0.5, 0.5)
    this._baseX = this._pixi.width * xMul
    this._baseY = this._pixi.height * yMul
    this._model.x = this._baseX
    this._model.y = this._baseY
    this._pixi.app.stage.addChild(this._model)
    this._currentModelName = folderName
    this._paramOverrides = modelParamOverrides[folderName] ?? null
    // ★ 记录当前模型的表情名列表（供情绪映射校验，换模型后不存在的表情会被跳过）
    try {
      const em = (this._model.internalModel as any)?.motionManager?.expressionManager
      this._currentExpressions = (em?.definitions ?? [])
        .map((d: any) => String(d?.Name ?? "")).filter(Boolean)
    } catch { this._currentExpressions = [] }
    this._startTicker()
    console.log("[Live2D] 模型可用表情:", this._currentExpressions.join(", ") || "(空)")
  }

  // -- Smooth ticker --
  private _startTicker(): void {
    if (this._tickerFn) return
    this._tickerFn = (dt: number) => {
      // Smooth lerp：dt 是帧数（60fps 时≈1），除以收敛帧数得到每帧插值比例（帧率无关）
      const f = Math.min(1, dt / 8)
      // Wrap angle for shortest path
      let da = this._targetAngle - this._curAngle
      while (da > Math.PI) da -= Math.PI * 2
      while (da < -Math.PI) da += Math.PI * 2
      this._curAngle += da * f
      this._curDist += (this._targetDist - this._curDist) * f

      // Convert angle+dist to parameter values
      const nx = Math.cos(this._curAngle) * this._curDist
      const ny = Math.sin(this._curAngle) * this._curDist
      this.setParameter(Param.EyeBallX, nx * 1.2)
      // 屏幕坐标 y 向下为正，Cubism 眼珠/角度 Y 正值向上，故取反
      this.setParameter(Param.EyeBallY, -ny * 1.2)
      this.setParameter(Param.AngleX, nx * 30)
      this.setParameter(Param.AngleY, -ny * 30)
      this.setParameter(Param.AngleZ, nx * ny * -15)
      this.setParameter(Param.BodyAngleX, nx * 10)
      // 特殊参数覆盖（每帧写入，防止被动作/表情系统重置）
      if (this._paramOverrides) {
        for (const id in this._paramOverrides) this.setParameter(id, this._paramOverrides[id])
      }
    }
    this._pixi.app.ticker.add(this._tickerFn)
  }

  private _stopTicker(): void {
    if (this._tickerFn) {
      this._pixi.app.ticker.remove(this._tickerFn)
      this._tickerFn = null
    }
  }

  lookAt(screenX: number, screenY: number): void {
    const rect = this.canvasRect
    if (!rect || rect.width === 0 || rect.height === 0) return
    const cx = rect.left + rect.width / 2
    const cy = rect.top + rect.height * 0.3
    const dx = screenX - cx
    const dy = screenY - cy
    const maxR = Math.max(rect.width, rect.height) * 1.2
    const r = Math.min(Math.sqrt(dx * dx + dy * dy), maxR)
    this._targetAngle = Math.atan2(dy, dx)
    this._targetDist = r / maxR
  }

  // -- Parameters --
  private getCore(): any {
    return (this._model?.internalModel as any)?.coreModel ?? null
  }

  setParameter(id: string, value: number): void {
    const core = this.getCore()
    if (core && typeof core.setParameterValueById === "function") {
      core.setParameterValueById(id, value)
    }
  }

  setMouthOpen(open: number): void {
    this.setParameter(Param.MouthOpenY, open)
  }

  // -- Motions --
  playMotion(group: string, index: number): void {
    this._model?.motion(group, index)
  }

  randomMotion(): void {
    if (!this._model) return
    this._model.motion("Tap", Math.floor(Math.random() * 5))
  }

  /** 触发表情：模型不存在该表情时静默跳过（pixi-live2d-display 找不到会 reject） */
  setExpression(name: string): void {
    if (!this._model) {
      console.warn("[Live2D] 表情跳过（模型未加载）:", name)
      return
    }
    if (this._currentExpressions.length && !this._currentExpressions.includes(name)) {
      console.warn(`[Live2D] 表情跳过（模型无此表情）: ${name}，可用: [${this._currentExpressions.join(", ")}]`)
      return
    }
    try {
      const r = this._model.expression(name) as any
      console.log("[Live2D] 切换表情:", name)
      r?.catch?.((e: any) => console.warn("[Live2D] 表情加载失败:", name, e))
    } catch (e) {
      console.warn("[Live2D] 表情调用异常:", name, e)
    }
  }

  /** 重置表情回模型默认状态（对话结束后的平静回落） */
  resetExpression(): void {
    if (!this._model) return
    try {
      const em = (this._model.internalModel as any)?.motionManager?.expressionManager
      em?.resetExpression?.()
      console.log("[Live2D] 表情已重置回默认")
    } catch { /* 静默 */ }
  }

  setScale(s: number): void {
    this._scale = s
    this._model?.scale.set(s)
  }

  setPosition(x: number, y: number): void {
    if (this._model) {
      this._model.x = x
      this._model.y = y
    }
  }

  hitTest(x: number, y: number): string[] {
    return this._model?.hitTest(x, y) ?? []
  }

  onHit(callback: (hitAreas: string[]) => void): void {
    this._model?.on("hit", (areas) => callback(areas as string[]))
  }

  center(): void {
    if (this._model) {
      this._model.x = this._baseX
      this._model.y = this._baseY
    }
  }

  destroy(): void {
    this._stopTicker()
    if (this._model) {
      this._pixi.app.stage.removeChild(this._model)
      this._model.destroy()
      this._model = null
    }
  }
}

