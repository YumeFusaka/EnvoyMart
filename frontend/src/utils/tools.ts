/**
 * 工具名的展示层映射。
 * <p>
 * 抽出来是因为有两个地方要看它：消息里的工具轨迹，和待确认的高危操作卡片。
 * 各存一份的话，服务端加个新工具就会一边显示中文、另一边显示 `coupon_query`。
 */

const TOOL_LABELS: Record<string, string> = {
  product_search: '商品检索',
  order_query: '订单查询',
  logistics_query: '物流查询',
  order_cancel: '订单取消',
}

/** 未知工具名原样显示 —— 服务端加了新工具而前端还没跟上时，看到 `coupon_query` 也比看到空白强 */
export function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] ?? tool
}

/**
 * 高危操作描述的展示化：`order_cancel(orderId=12)` → `订单取消(orderId=12)`。
 * <p>
 * <b>只翻译括号前的工具名，参数原样保留。</b>参数是用户判断「要动的是哪一个对象」的唯一依据，
 * 翻译、截断或省略它都是在替用户改授权对象——一个写着「订单取消」却不说是哪一单的卡片，
 * 点了确认等于盲签。没有括号时整串按工具名处理。
 */
export function actionLabel(action: string): string {
  const at = action.indexOf('(')
  if (at < 0) {
    return toolLabel(action)
  }
  return toolLabel(action.slice(0, at)) + action.slice(at)
}
