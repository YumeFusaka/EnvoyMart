import test from 'node:test'
import assert from 'node:assert/strict'
import { prepareCase } from './complex-agent-live.mjs'
test('兼容入口拒绝缺失执行契约',()=>assert.throws(()=>prepareCase({caseId:'CA-001'}),/执行契约/))
