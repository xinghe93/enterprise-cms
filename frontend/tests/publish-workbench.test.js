import assert from 'node:assert/strict'
import { test } from 'node:test'
import * as workflow from '../src/api/review.js'

test('发布工作台按单篇与批量权限分别提供合法的状态操作', () => {
  const can = (code) => ['article:publish-batch', 'article:offline'].includes(code)
  assert.equal(workflow.canChangePublication('publish', 'APPROVED', true, can), true)
  assert.equal(workflow.canChangePublication('publish', 'APPROVED', false, can), false)
  assert.equal(workflow.canChangePublication('publish', 'OFFLINE', true, can), false)
  assert.equal(workflow.canChangePublication('offline', 'PUBLISHED', false, can), true)
  assert.equal(workflow.canChangePublication('offline', 'PUBLISHED', true, can), false)
  assert.equal(workflow.canChangePublication('offline', 'APPROVED', false, can), false)
})

test('批量请求保留当前记录的字符串 ID 和版本，且拒绝重复选择', () => {
  const first = { id: '9007199254740993', version: 0 }
  const second = { id: '9223372036854775807', version: 12 }
  assert.deepEqual(workflow.buildBatchItemsPayload([first, second]), { items: [first, second] })
  assert.throws(() => workflow.buildBatchItemsPayload([first, { ...first, version: 1 }]), /重复/)
})
