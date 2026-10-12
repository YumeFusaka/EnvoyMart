import assert from 'node:assert/strict'

export const COMBINATION_QUERY = '钙,铁剂,锌'
const MEMBERS = ['钙', '铁剂', '锌']
const normalize = (text) => String(text ?? '').replace(/\s+/g, '').toLowerCase()

export function validateCombinationEvidence(edge, document, chunk) {
  assert.equal(edge.head?.kind, 'COMBINATION', '组合头必须使用 COMBINATION 类型')
  assert.equal(edge.head?.name, `combo:${MEMBERS.join('|')}`, '专项组合必须完整覆盖钙、铁剂、锌')
  assert.equal(edge.relation, 'COMBINED_WITH', '必须是真实组合关系')
  assert.ok(edge.quote && edge.docId && edge.docTitle && edge.chunkId, '组合边必须包含完整出处')
  assert.equal(document.docNo, edge.docId, '原文身份必须与图谱一致')
  assert.equal(document.title, edge.docTitle, '原文标题必须与图谱一致')
  assert.equal(chunk.docNo, edge.docId, '切片必须属于同一文档')
  assert.equal(chunk.chunkId, edge.chunkId, '切片身份必须与图谱一致')
  assert.equal(chunk.documentContent, document.content, '切片与文档必须使用同一版正文')
  assert.ok(Number.isInteger(edge.quoteStart) && Number.isInteger(edge.quoteEnd)
    && edge.quoteStart >= 0 && edge.quoteEnd > edge.quoteStart, '引文偏移必须合法')
  assert.equal(document.content.slice(edge.quoteStart, edge.quoteEnd), edge.quote, '偏移必须精确指向逐字原文')
  assert.ok(Number.isInteger(chunk.charOffset) && Number.isInteger(chunk.charEnd)
    && chunk.charOffset <= edge.quoteStart && chunk.charEnd >= edge.quoteEnd, '全部引文必须落在同一切片范围内')
  assert.ok(chunk.content.includes(edge.quote), '切片原文必须逐字包含组合引文')
  const quote = normalize(edge.quote)
  for (const member of MEMBERS) assert.ok(quote.includes(normalize(member)), `引文缺少成员：${member}`)
  assert.ok(edge.tail?.name && quote.includes(normalize(edge.tail.name)), '尾端必须出现在同一引文中')
  assert.ok(typeof edge.effect === 'string' && edge.effect.trim()
    && edge.quote.includes(edge.effect), '后果必须是同一引文中的非空逐字片段')
  return edge
}

export async function loadCombinationEvidence(gateway) {
  async function read(path) {
    const response = await fetch(`${gateway}${path}`)
    assert.ok(response.ok, `只读接口 HTTP ${response.status}：${path}`)
    const body = await response.json()
    assert.ok(body.code === 200 && body.data, `只读接口失败：${path}，${body.msg ?? '无结果'}`)
    return body.data
  }
  const report = await read(`/knowledge/graph/interactions?items=${encodeURIComponent(COMBINATION_QUERY)}`)
  assert.ok(report.available, `图谱不可用：${report.note ?? '未说明'}`)
  assert.ok(Array.isArray(report.items) && report.items.length === 3
    && report.items.every((item) => item.found), '专项三个实体必须全部收录并逐项返回')
  const unique = new Map()
  for (const edge of report.items.flatMap((item) => item.risks ?? [])) {
    if (edge.relation !== 'COMBINED_WITH') continue
    unique.set([edge.head?.name, edge.tail?.name, edge.docId, edge.chunkId, edge.quoteStart, edge.quoteEnd].join('|'), edge)
  }
  assert.ok(unique.size, '生产图谱未返回钙、铁剂、锌的组合注意事项；不能用两两关系或临时伪边代替')
  const evidence = []
  for (const edge of unique.values()) {
    const document = await read(`/knowledge/documents/${encodeURIComponent(edge.docId)}`)
    const chunk = await read(`/knowledge/chunks/${encodeURIComponent(edge.chunkId)}`)
    evidence.push(validateCombinationEvidence(edge, document, chunk))
  }
  return { report, combinations: evidence }
}
