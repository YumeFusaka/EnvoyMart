import { createHash } from 'node:crypto'

export const WRITE_TOOLS = ['cart_add', 'cart_update', 'cart_remove', 'cart_checkout', 'order_cancel', 'after_sale_apply', 'ticket_create', 'coupon_receive']
export function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]))
  return value
}
export const digest = (value) => createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex')
export const parameterHash = (userId, toolName, parameters) => digest({ version: 1, userId, toolName, parameters })
export function assertReceipt(result, action, userId, replayed) {
  const expected = parameterHash(userId, action.tool, action.parameters)
  if (result.status !== 200 || result.body?.code !== 200 || result.headers['x-operation-id'] !== action.operationId
    || result.headers['x-operation-parameters-hash'] !== expected || result.headers['x-operation-receipt-version'] !== '1'
    || result.headers['x-operation-replayed'] !== String(replayed)
    || (action.tool !== 'cart_remove' && result.body.data == null)) throw new Error('回执契约不符：' + action.tool)
  return digest(result.body)
}
export function restartVerified(evidence) {
  return ['order', 'promotion'].every((service) => {
    const item = evidence?.services?.find((record) => record.service === service)
    return Number.isInteger(item?.beforePid) && Number.isInteger(item?.afterPid) && item.beforePid !== item.afterPid
      && item.afterReady === true && item.beforePid > 0 && item.afterPid > 0
  })
}
