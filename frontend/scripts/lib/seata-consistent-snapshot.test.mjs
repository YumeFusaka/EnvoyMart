import {test} from 'node:test'
import assert from 'node:assert/strict'
import * as local from './write-receipt-seata-local.mjs'

test('库存、回执、undo由一个SELECT取得；回滚在采样之后发生不能混入该快照',()=>{
  let stock=494,undo=['x'],calls=0
  const execute=(query)=>{
    calls++
    assert.match(query,/^SELECT /)
    assert.match(query,/UNION ALL/)
    assert.doesNotMatch(query,/;/)
    const rows=['stock\t'+JSON.stringify([stock]),'undo\t'+JSON.stringify(undo)]
    stock=495;undo=[]
    return rows.join('\n')
  }
  const queries={stock:{columns:['stock'],source:'FROM envoymart_product.product_sku'},undo:{columns:['xid'],source:'FROM envoymart_order.undo_log'}}
  const snapshot=local.readConsistentSections(queries,execute)
  assert.equal(calls,1)
  assert.deepEqual(snapshot,{stock:[['494']],undo:[['x']]})
  assert.equal(stock,495)
})
test('永久版本与任务bigint以SQL字符串返回，超过2的53次方不截断',()=>{
  const exact='9007199254740993'
  const snapshot=local.readConsistentSections({guard:{columns:['CAST(last_applied_task_id AS CHAR)'],source:'FROM envoymart_product.product_projection_spu_guard'}},query=>{
    assert.match(query,/CAST\(last_applied_task_id AS CHAR\)/)
    return 'guard\t'+JSON.stringify([exact])
  })
  assert.equal(snapshot.guard[0][0],exact)
})
