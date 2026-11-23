import test from 'node:test'
import assert from 'node:assert/strict'
import { clampCodePoints, countCodePoints } from '../src/utils/text.js'

test('countCodePoints 按 Unicode 代码点计数，与后端 codePointCount 同口径', () => {
  // 「𠮷」是增补平面字符，raw.length()=2 但代码点数是 1。
  assert.equal(countCodePoints('𠮷'), 1)
  assert.equal(countCodePoints('𠮷'.repeat(64)), 64)
  assert.equal(countCodePoints('editor_demo'), 11)
  assert.equal(countCodePoints(''), 0)
  assert.equal(countCodePoints(null), 0)
  assert.equal(countCodePoints(undefined), 0)
})

test('clampCodePoints 按代码点截断：BMP 行为与 maxlength 一致', () => {
  assert.equal(clampCodePoints('x'.repeat(101), 100), 'x'.repeat(100))
  assert.equal(clampCodePoints('x'.repeat(100), 100), 'x'.repeat(100))
  assert.equal(clampCodePoints('', 100), '')
  assert.equal(clampCodePoints(null, 100), '')
})

test('clampCodePoints 按代码点截断：增补平面字符不被按 UTF-16 代码单元截半', () => {
  // 64 个「𠮷」的 raw.length()=128，maxlength=64 会截到 32 个字；代码点口径必须完整保留。
  assert.equal(clampCodePoints('𠮷'.repeat(64), 64), '𠮷'.repeat(64))
  // 65 个代码点截到 64，丢弃整字而不是半个代理对。
  const clamped = clampCodePoints('𠮷'.repeat(65), 64)
  assert.equal(clamped, '𠮷'.repeat(64))
  assert.equal(countCodePoints(clamped), 64)
  // 半个代理对（孤立代理项）按 1 个代码点参与计数，截断结果总是合法字符串。
  assert.equal(clampCodePoints('\uD842', 10), '\uD842')
})
