import { test } from 'node:test'
import assert from 'node:assert/strict'
import { assessNaturalRollback } from './write-receipt-seata.mjs'
const facts=()=>({beforeCoupon:{status:'USED',orderNo:'YS-FIRST'},afterCoupon:{status:'USED',orderNo:'YS-FIRST'},
  beforeDatabase:{engine:'MySQL',seataEnabled:true,stock:20},afterDatabase:{receiptCount:0,operationOrderCount:0,stock:20},
  waiting:{waiting:true,table:'user_coupon',blockingTransactionId:'abc'},receipt:{code:404},
  seata:{xid:'tc:123',atBranchRegistered:true,globalRolledBack:true},checkoutCode:409})
test('真实全局失败证据通过且不代替窄phase1窗口',()=>assert.equal(assessNaturalRollback(facts()).status,'PASS'))
test('只凭Seata配置或H2不允许通过',()=>{const f=facts();f.beforeDatabase.engine='H2';assert.equal(assessNaturalRollback(f).status,'BLOCKED')})
test('原首单券被错误退回或库存多加必须FAIL',()=>{const f=facts();f.afterCoupon.status='UNUSED';assert.equal(assessNaturalRollback(f).status,'FAIL');const g=facts();g.afterDatabase.stock=21;assert.equal(assessNaturalRollback(g).status,'FAIL')})
test('持有行锁但UPDATE未实际等待或没同XID回滚属于未覆盖',()=>{const f=facts();f.waiting.waiting=false;assert.equal(assessNaturalRollback(f).status,'BLOCKED');const g=facts();g.seata.xid=null;assert.equal(assessNaturalRollback(g).status,'BLOCKED')})
