/**
 * 验收脚本的公共工具。
 *
 * 这些函数原先在每个脚本里各写一份，而它们是**踩出来的**，不是随手写的：
 * 谁复制一份、谁就会把当初踩出来的细节漏掉一处，然后那个脚本开始偶发假红。
 * 抽成一份的唯一理由就是让「怎么等」「怎么定位一行」只有一个正确答案。
 */

/**
 * 轮询到条件成立为止，返回最后一次的取值。
 * <p>
 * <b>等到的是「状态真的变了」，不是「大概过了多久」。</b>固定 sleep 的写法在本机快时
 * 能过、机器一忙就差一口气，于是断言读到的是改动前的值 —— 一次假失败比不测更费时间。
 * <p>
 * 超时后**返回最后一次取值而不是抛异常**：让调用方的断言去报「实际是什么」，
 * 比这里抛一个看不出实际值的 TimeoutError 有用得多。
 *
 * @param fn        每次调用取一次当前值（可以是同步值或 Promise）
 * @param predicate 判定是否已满足
 * @param opts.timeoutMs 最长等待，默认 12000
 * @param opts.intervalMs 轮询间隔，默认 400
 */
export async function poll(fn, predicate, { timeoutMs = 12000, intervalMs = 400 } = {}) {
  let last
  for (let waited = 0; waited <= timeoutMs; waited += intervalMs) {
    last = await fn()
    if (predicate(last)) {
      return last
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }
  return last
}

/**
 * 在一组候选里找出**全库唯一**的一行 —— 用列表接口的 keyword 反查命中数来判定。
 * <p>
 * 为什么需要它：管理台的表格没有把 id 显示出来，脚本只能按可见文字（评价内容、
 * 用户名……）定位行。而「在这一页里唯一」不等于「在全库唯一」——列表默认混着全部状态，
 * 一条已隐藏的孪生数据完全可能与它同内容，此时 `.first()` 选中的是另一条，
 * 点下去是另一个按钮，等「隐藏」等到超时：看着像功能坏了，其实是定位错了行。
 * <p>
 * 只有「反查命中数恰为 1、且就是这一条」的候选才会返回；一个都没有时抛错，
 * 而不是退而求其次取第一个 —— 后者会把一次定位失败伪装成一次功能失败。
 *
 * @param list     候选数组
 * @param describe 取候选的比对字段（默认取 `.content`）
 * @param probe    (text) => Promise<{total, records}>，用 keyword 反查
 */
export async function uniqueRow(list, describe, probe) {
  for (const candidate of list) {
    const text = describe(candidate)
    if (!text) continue
    const hits = await probe(text)
    if (hits.total === 1 && hits.records[0]?.id === candidate.id) {
      return candidate
    }
  }
  throw new Error('没有全库唯一的候选行可用来定位')
}
