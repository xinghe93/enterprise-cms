import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createLatestRequestGuard, isValidLogId, normalizeTotal, validateLogFilters } from '../src/api/log.js'
import { buildLogQuery, formatLocalDateTime, formatLogTime } from '../src/api/log.js'

test('默认分页参数为第一页、每页 10 条，空筛选项不传给后端', () => {
  const query = buildLogQuery({})
  assert.deepEqual(query, { current: 1, size: 10 })
  assert.deepEqual(buildLogQuery({ userId: '', operation: '   ', result: '' }), { current: 1, size: 10 })
})

test('有效筛选项被保留，操作类型去掉首尾空白，操作人 ID 按字符串保留', () => {
  const query = buildLogQuery({ userId: '990101', operation: '  article.approve  ', result: 'FAILURE', current: 2, size: 20 })
  assert.deepEqual(query, {
    userId: '990101',
    operation: 'article.approve',
    result: 'FAILURE',
    current: 2,
    size: 20,
  })
  assert.deepEqual(buildLogQuery({ userId: 'abc', result: 'OTHER' }), { current: 1, size: 10 })
})

test('超出 Number 安全整数范围的合法 Long ID 仍作为字符串保留', () => {
  assert.deepEqual(buildLogQuery({ userId: '9007199254740993' }), { userId: '9007199254740993', current: 1, size: 10 })
  assert.deepEqual(buildLogQuery({ userId: '9223372036854775807' }), { userId: '9223372036854775807', current: 1, size: 10 })
  assert.deepEqual(buildLogQuery({ userId: '9223372036854775808' }), { current: 1, size: 10 })
})

test('时间范围转成本地格式且不带 Z，边界值可左闭右开', () => {
  const from = new Date(2026, 10, 8, 10, 0, 0)
  const to = new Date(2026, 10, 8, 11, 0, 0)
  const query = buildLogQuery({ timeRange: [from, to] })
  assert.equal(query.createdFrom, '2026-11-08T10:00:00')
  assert.equal(query.createdTo, '2026-11-08T11:00:00')
  assert.ok(!JSON.stringify(query).includes('Z'))
})

test('时间范围也接受日期选择器返回的字符串，毫秒被截断', () => {
  const query = buildLogQuery({ timeRange: ['2026-11-08T10:00:00.120', '2026-11-08T11:00:00'] })
  assert.equal(query.createdFrom, '2026-11-08T10:00:00')
  assert.equal(query.createdTo, '2026-11-08T11:00:00')
})

test('formatLocalDateTime 对无效输入返回 null', () => {
  assert.equal(formatLocalDateTime(null), null)
  assert.equal(formatLocalDateTime('not-a-date'), null)
  assert.equal(formatLocalDateTime(new Date('invalid')), null)
})

test('formatLogTime 显示到秒，空值显示破折号', () => {
  assert.equal(formatLogTime('2026-11-08T10:00:00.000'), '2026-11-08 10:00:00')
  assert.equal(formatLogTime('2026-11-08T10:00:00'), '2026-11-08 10:00:00')
  assert.equal(formatLogTime(null), '—')
  assert.equal(formatLogTime(''), '—')
  assert.equal(formatLogTime(undefined), '—')
})

test('isValidLogId 按 Long 契约接受 1-19 位正整数，拒绝前导零/0/负数/0x 前缀/超 Long 上限', () => {
  assert.equal(isValidLogId(990001), true)
  assert.equal(isValidLogId('990001'), true)
  assert.equal(isValidLogId(1), true)
  assert.equal(isValidLogId('9007199254740993'), true)
  assert.equal(isValidLogId('9223372036854775807'), true)
  assert.equal(isValidLogId('9223372036854775808'), false)
  assert.equal(isValidLogId('000123'), false)
  assert.equal(isValidLogId(undefined), false)
  assert.equal(isValidLogId(null), false)
  assert.equal(isValidLogId(''), false)
  assert.equal(isValidLogId(0), false)
  assert.equal(isValidLogId(-1), false)
  assert.equal(isValidLogId('0x1'), false)
  assert.equal(isValidLogId('abc'), false)
})

test('normalizeTotal 把非法或负值收敛为 0，其余取整', () => {
  assert.equal(normalizeTotal(3), 3)
  assert.equal(normalizeTotal('12'), 12)
  assert.equal(normalizeTotal('12.9'), 12)
  assert.equal(normalizeTotal(undefined), 0)
  assert.equal(normalizeTotal(null), 0)
  assert.equal(normalizeTotal(NaN), 0)
  assert.equal(normalizeTotal(-5), 0)
  assert.equal(normalizeTotal(Infinity), 0)
})

test('列表请求守卫只允许最新一次请求回写页面状态（乱序返回保护）', () => {
  const guard = createLatestRequestGuard()
  const page2 = guard.next()
  const page3 = guard.next()
  const applied = []
  // 模拟第 3 页先返回、第 2 页后返回：只有最新请求允许写 rows/total/loading。
  if (guard.isLatest(page3)) applied.push('page-3')
  if (guard.isLatest(page2)) applied.push('page-2')
  assert.deepEqual(applied, ['page-3'])
})

test('validateLogFilters 拦截非法操作人 ID 与非递增时间区间，避免静默丢弃误导查询', () => {
  assert.deepEqual(validateLogFilters({}), [])
  assert.deepEqual(validateLogFilters({ userId: '', timeRange: null }), [])
  assert.deepEqual(validateLogFilters({
    userId: '9223372036854775807',
    timeRange: ['2026-11-08T10:00:00', '2026-11-08T10:00:01'],
  }), [])

  for (const badId of ['0', '001', '9223372036854775808', 'abc']) {
    const errors = validateLogFilters({ userId: badId })
    assert.equal(errors.length, 1, `非法 ID ${badId} 应返回校验错误`)
    assert.match(errors[0], /操作人 ID 无效/)
  }

  const sameTime = validateLogFilters({ timeRange: ['2026-11-08T10:00:00', '2026-11-08T10:00:00'] })
  assert.equal(sameTime.length, 1)
  assert.match(sameTime[0], /起始时间必须早于结束时间/)

  const reversed = validateLogFilters({ timeRange: ['2026-11-08T12:00:00', '2026-11-08T10:00:00'] })
  assert.equal(reversed.length, 1)
  assert.match(reversed[0], /起始时间必须早于结束时间/)
})

test('LogListView 按 session.permissionStatus 严格门控，并在查询前调用 validateLogFilters', () => {
  const source = readFileSync(new URL('../src/views/LogListView.vue', import.meta.url), 'utf8')
  assert.match(source, /session\.permissionStatus === 'loaded'/)
  assert.doesNotMatch(source, /session\.permissionCodes\.length > 0/)
  assert.match(source, /validateLogFilters\(/)
  assert.match(source, /appliedFilters/)
})
