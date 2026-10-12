import assert from 'node:assert/strict'
import test from 'node:test'
import { validateCombinationEvidence } from './combination-evidence.mjs'

function fixture() {
  const quote = '钙剂、铁剂、锌三者同时补充时，在肠道吸收阶段会发生竞争并彼此抑制，建议不要同时服用，至少错开两小时。'
  const content = `本品为SPU26儿童钙软糖。${quote}`
  const edge = {
    head: { name: 'combo:钙|铁剂|锌', kind: 'COMBINATION' }, relation: 'COMBINED_WITH',
    tail: { name: '锌' }, effect: '在肠道吸收阶段会发生竞争并彼此抑制', quote,
    docId: 'KB-0053', docTitle: '钙铁锌三者同补注意事项（专项证据）', chunkId: 'KB-0053_0',
    quoteStart: content.indexOf(quote), quoteEnd: content.length,
  }
  const document = { docNo: edge.docId, title: edge.docTitle, content }
  const chunk = { docNo: edge.docId, chunkId: edge.chunkId, documentContent: content,
    content, charOffset: 0, charEnd: content.length }
  return { edge, document, chunk }
}

test('现有三矿物原句的完整文档切片偏移契约通过', () => {
  const { edge, document, chunk } = fixture()
  assert.equal(validateCombinationEvidence(edge, document, chunk), edge)
})

test('缺少组合成员或改成两两关系必须拒绝', () => {
  const { edge, document, chunk } = fixture()
  assert.throws(() => validateCombinationEvidence({ ...edge, head: { ...edge.head, name: 'combo:钙|铁剂' } }, document, chunk), /完整覆盖/)
  assert.throws(() => validateCombinationEvidence({ ...edge, relation: 'INTERACTS_WITH' }, document, chunk), /真实组合/)
})

test('尾端或后果不能借全文其他句子或改写', () => {
  const { edge, document, chunk } = fixture()
  assert.throws(() => validateCombinationEvidence({ ...edge, tail: { name: '镁' } }, document, chunk), /尾端/)
  for (const effect of ['', '引起结石', '存在吸收竞争']) {
    assert.throws(() => validateCombinationEvidence({ ...edge, effect }, document, chunk), /后果/)
  }
})

test('错文档错切片或不同版本正文必须拒绝', () => {
  const { edge, document, chunk } = fixture()
  assert.throws(() => validateCombinationEvidence(edge, { ...document, docNo: 'KB-0047' }, chunk), /原文身份/)
  assert.throws(() => validateCombinationEvidence(edge, document, { ...chunk, chunkId: 'KB-0053_1' }), /切片身份/)
  assert.throws(() => validateCombinationEvidence(edge, document, { ...chunk, documentContent: '旧正文' }), /同一版/)
})

test('引文存在但偏移或同一切片范围不匹配必须拒绝', () => {
  const { edge, document, chunk } = fixture()
  assert.throws(() => validateCombinationEvidence({ ...edge, quoteStart: edge.quoteStart + 1 }, document, chunk), /偏移/)
  assert.throws(() => validateCombinationEvidence(edge, document, { ...chunk, charEnd: edge.quoteEnd - 1 }), /同一切片/)
  assert.throws(() => validateCombinationEvidence(edge, document, { ...chunk, content: '没有引文' }), /切片原文/)
})
