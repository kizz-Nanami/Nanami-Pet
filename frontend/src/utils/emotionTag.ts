// ★ 流式安全的情绪标签提取器（v2：兼容约定格式与模型自创变体）
// 兼容格式：
//   1. [emotion:happy] —— system prompt 约定的标准格式
//   2. [love:温柔]     —— 模型偶发自创的"情绪:修饰"倒装格式（情绪名在前）
//   3. 中文情绪名变体：[开心:xx] / [emotion:开心] 等，统一归一化为英文情绪键
// 其他不认识的 [xxx:yyy]（如数学区间 [0:5]）原样保留，不误伤。
// 处理标签跨 chunk 截断：尾部疑似未闭合标签时扣住该尾部，等下一块拼接。

// 中文/表情名 → 标准情绪键（与 config.ts 的 emotionMap 键对应）
const EMOTION_ALIAS: Record<string, string> = {
  happy: "happy", 开心: "happy", 高兴: "happy", 快乐: "happy",
  sad: "sad", 难过: "sad", 伤心: "sad", 沮丧: "sad",
  angry: "angry", 生气: "angry", 愤怒: "angry",
  surprise: "surprise", 惊讶: "surprise", 震惊: "surprise", 兴奋: "surprise",
  love: "love", 爱: "love", 喜爱: "love", 害羞: "love",
  sleepy: "sleepy", 困: "sleepy", 疲惫: "sleepy",
  calm: "calm", 平静: "calm", 安心: "calm",
  // 模型可能直接输出表情名（emotionMap 的 value），同样剥离并就近映射
  star: "love", dizzy: "sleepy", dall: "calm",
  // 角色扮演式括号动作标记中高频出现的纯名（qwen3.5 实测混入，如"(微笑)"）
  微笑: "happy",
}
const NAMES = Object.keys(EMOTION_ALIAS).join("|")
// 完整标签三种格式（v3：新增无冒号纯名变体 [happy]/[calm]——模型偶发自创，实测会泄漏到气泡）：
//   1. [emotion:X]（标准）  2. [X:修饰]（倒装）  3. [X]（纯名）；冒号前后允许空白
const TAG_RE = new RegExp(`\\[(?:emotion\\s*:\\s*(${NAMES})|(${NAMES})\\s*:\\s*[^\\]\\n]{0,16}|(${NAMES}))\\]`, "i")
// v4：圆括号变体兜底（qwen3.5 实测混入角色扮演式括号标记）：
//   (love) / （微笑）/ (emotion:happy) / 空括号 () （）
// 窄规则防误伤：括号内必须是已知情绪名或纯空括号；"(他微笑着说)" 这类叙述性括号不受影响
const PAREN_RE = new RegExp(`[（(]\\s*(?:emotion\\s*:\\s*)?(${NAMES})\\s*[)）]`, "i")
const EMPTY_PAREN_RE = /[（(]\s*[)）]/
// 未闭合的潜在标签开头：[ 后只含字母/中文/冒号/空白（"emotion : love" 空格变体也扣住，防止跨块放行泄漏）
const PARTIAL_RE = /\[[a-zA-Z\u4e00-\u9fff:\s]{1,24}$/
// 已知情绪标签的残片开头（如 "[love:温"、"[emotion:ha"、"[calm"）：流结束时可直接丢弃
const PARTIAL_TAG_PREFIX_RE = new RegExp(`^\\[(?:emotion\\s*:\\s*|(${NAMES})\\s*:|(${NAMES}))`, "i")

/** ★ 任意文本的标签清理兜底（幂等）：用于历史消息加载/跨窗口同步等不经流式提取器的路径 */
export function stripEmotionTags(text: string): string {
  let out = text
  let m: RegExpExecArray | null
  while ((m = TAG_RE.exec(out) || PAREN_RE.exec(out) || EMPTY_PAREN_RE.exec(out))) {
    out = out.slice(0, m.index) + out.slice(m.index + m[0].length)
  }
  return out
}

export interface EmotionFeedResult {
  /** 剥离标签后的安全文本（可直接送切句器/气泡） */
  text: string
  /** 本块中提取到的第一个情绪名（英文小写），无则 null */
  emotion: string | null
}

export class EmotionTagExtractor {
  private pending = ""

  feed(chunk: string): EmotionFeedResult {
    let buf = this.pending + chunk
    this.pending = ""
    let emotion: string | null = null

    while (true) {
      const m = TAG_RE.exec(buf)
      if (!m) break
      // 组1 = [emotion:X] 的 X；组2 = [X:修饰] 的 X；组3 = [X] 纯名变体的 X
      const e = EMOTION_ALIAS[(m[1] || m[2] || m[3]).toLowerCase()]
      if (!emotion && e) emotion = e
      buf = buf.slice(0, m.index) + buf.slice(m.index + m[0].length)
    }
    // v4：圆括号变体（(love)/(微笑)/()）同样剥离，情绪可就近映射（(love) 视为 love 表情）
    while (true) {
      const m = PAREN_RE.exec(buf) || EMPTY_PAREN_RE.exec(buf)
      if (!m) break
      if (!emotion && m[1]) {
        const e = EMOTION_ALIAS[m[1].toLowerCase()]
        if (e) emotion = e
      }
      buf = buf.slice(0, m.index) + buf.slice(m.index + m[0].length)
    }

    // 尾部疑似未闭合标签（如 "[emotion:lo"、"[love:温"）：扣住等下一块拼接。
    // 误扣普通文本（如"[备注"）最坏延迟一块，下块拼齐后不匹配即原样输出，无丢失。
    const pm = PARTIAL_RE.exec(buf.slice(-32))
    if (pm) {
      this.pending = pm[0]
      buf = buf.slice(0, buf.length - pm[0].length)
    }
    return { text: buf, emotion }
  }

  /**
   * 流结束时对扣留残片做最终裁决（修复：旧版原样返回导致标签泄露 + 情绪丢失）：
   *   1. 残片补上 "]" 后能匹配完整标签（如 "[love:温柔" 缺尾）→ 剥离并返回情绪
   *   2. 以已知情绪标签开头（如 "[love:温"）→ 被截断的标签残片，直接丢弃
   *   3. 其他（如 "[备注"）→ 正常文本，原样返回
   */
  flush(): EmotionFeedResult {
    let rest = this.pending
    this.pending = ""
    let emotion: string | null = null
    const closed = TAG_RE.exec(rest + "]")
    if (closed && closed.index === 0) {
      const e = EMOTION_ALIAS[(closed[1] || closed[2] || closed[3]).toLowerCase()]
      if (!emotion && e) emotion = e
      rest = rest.slice(closed[0].length - 1)
    } else if (PARTIAL_TAG_PREFIX_RE.test(rest)) {
      rest = ""
    }
    return { text: rest, emotion }
  }
}
