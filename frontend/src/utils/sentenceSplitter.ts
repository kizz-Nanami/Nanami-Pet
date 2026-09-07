// ★ 流式文本切句器：把 AI 流式返回的字符流切成"语义完整"的短句
// 规则（三层，优先级从高到低）：
//   1. 句末标点（。！？；\n）→ 无论第几个字，立即切出
//   2. 逗号等软标点（，、）→ 仅当标点前累计字数已超过 SOFT_LIMIT 才切
//   3. 硬上限 HARD_LIMIT   → 防超长无标点句，兜底腰斩
// 用法：流式场景持续 feed()，结束时 flush() 拿残句

const SOFT_LIMIT = 18   // 超过此字数，遇到软标点才切
const HARD_LIMIT = 50   // 无任何标点时的兜底上限

const SENTENCE_END = /[。！？；!?;\n]/   // 句末标点：无条件切
const SOFT_PAUSE = /[,，、]/             // 软标点：自然停顿点

export class SentenceSplitter {
  private buffer = ""

  /** 喂入一个流式数据块，返回本次能切出的完整句子（可能为空数组） */
  feed(chunk: string): string[] {
    this.buffer += chunk
    return this._drain()
  }

  /** 流结束：吐出剩余残句（不满足切分条件的尾巴），无残句返回 null */
  flush(): string | null {
    const t = this.buffer.trim()
    this.buffer = ""
    return t || null
  }

  /** 重置（打断时丢弃未切分的半句） */
  reset(): void {
    this.buffer = ""
  }

  // 反复扫描 buffer，切出所有满足条件的句子，返回剩余不完整的部分
  private _drain(): string[] {
    const out: string[] = []
    let start = 0 // 当前句子的起点
    for (let i = 0; i < this.buffer.length; i++) {
      const ch = this.buffer[i]
      const isEnd = SENTENCE_END.test(ch)
      const isSoft = SOFT_PAUSE.test(ch)
      // 切分条件：句末标点 / 超软上限后的软标点 / 达到硬上限
      if (isEnd || (isSoft && i - start + 1 > SOFT_LIMIT) || (i - start + 1 >= HARD_LIMIT)) {
        const segment = this.buffer.substring(start, i + 1).trim()
        if (segment) out.push(segment)
        start = i + 1
      }
    }
    this.buffer = this.buffer.substring(start)
    return out
  }
}
