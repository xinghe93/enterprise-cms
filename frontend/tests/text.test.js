import test from 'node:test'
import assert from 'node:assert/strict'
import { clampCodePoints, countCodePoints, syncClampedInput, trimOnBlur } from '../src/utils/text.js'

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

test('syncClampedInput 把截断结果同步写回 DOM，显示值与提交值一致', () => {
  // 老师复审 #2：model 已是 128 个代码点时再敲第 129 字，clamp 返回相同值
  // → Vue 不重渲染 → 输入框显示 129 字但提交 128 字。处理器必须写回 DOM。
  const input = { value: '𠮷'.repeat(128) + 'x' }
  const result = syncClampedInput({ target: input }, 128)
  assert.equal(result, '𠮷'.repeat(128))
  assert.equal(input.value, result, 'DOM 输入框必须同步为实际提交值')
  assert.equal(countCodePoints(input.value), 128)

  // 未超限时不改动 DOM（交给 Vue 的正常渲染流程）。
  const normal = { value: '𠮷'.repeat(10) }
  assert.equal(syncClampedInput({ target: normal }, 64), '𠮷'.repeat(10))
  assert.equal(normal.value, '𠮷'.repeat(10))

  // 输入过程中不得 trim：逐键输入「Mary 」时尾随空格必须保留，否则永远打不出
  // 带内部空格的姓名（老师复审 P2）。首尾空白只在 @blur / 提交时处理。
  const withSpace = { value: '  Mary Jane  ' }
  assert.equal(syncClampedInput({ target: withSpace }, 100), '  Mary Jane  ')
  assert.equal(withSpace.value, '  Mary Jane  ')
})

test('逐键输入带空格的姓名/部门名/关键字：输入期间空格原样保留', () => {
  // 老师复审 P2 的复现路径：旧实现每次 @input 都 trim，敲完 "Mary " 空格立即
  // 消失，继续输入最终得到 "MaryJane"。现在逐键模拟整个输入过程。
  for (const sample of ['Mary Jane', '销售 一部', '王 小明']) {
    let value = ''
    for (const char of sample) {
      const input = { value: value + char }
      value = syncClampedInput({ target: input }, 100)
      assert.equal(input.value, value, `输入「${char}」后 DOM 必须等于 model`)
    }
    assert.equal(value, sample, '内部空格必须与输入完全一致')
  }

  // 尾随空格在逐键输入阶段同样保留；失焦时才去掉首尾空白。
  let value = ''
  for (const char of 'Mary Jane ') {
    const input = { value: value + char }
    value = syncClampedInput({ target: input }, 100)
  }
  assert.equal(value, 'Mary Jane ')
  const blurred = { value }
  assert.equal(trimOnBlur({ target: blurred }), 'Mary Jane')
  assert.equal(blurred.value, 'Mary Jane', '失焦后 DOM 同步为裁剪结果')

  // 逐键输入到达代码点上限时，空格也参与计数，不因截断丢失已输入的合法内容。
  const nearLimit = '𠮷'.repeat(63) + ' '
  const input = { value: nearLimit + 'X' }
  assert.equal(syncClampedInput({ target: input }, 64), nearLimit)
  assert.equal(input.value, nearLimit)
})

test('trimOnBlur 只在失焦时裁剪首尾空白（含全角空白），不触碰内部空格', () => {
  assert.equal(trimOnBlur({ target: { value: '  Mary Jane  ' } }), 'Mary Jane')
  assert.equal(trimOnBlur({ target: { value: '\u3000销售一部\u3000' } }), '销售一部')
  // 未超限的输入不改动 DOM；裁剪后的值与 DOM 始终一致。
  const clean = { value: 'Mary Jane' }
  assert.equal(trimOnBlur({ target: clean }), 'Mary Jane')
  assert.equal(clean.value, 'Mary Jane')
})
