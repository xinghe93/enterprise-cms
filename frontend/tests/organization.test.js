import test from 'node:test'
import assert from 'node:assert/strict'
import {
  collectSubtreeIds, compactParams, findDepartment, flattenDepartments, isValidId,
  normalizeRoleIds, parentOptions, sortDepartmentTree,
} from '../src/utils/organization.js'

const node = (id, parentId, deptName, sortOrder, status, children = []) => ({
  id, parentId, deptName, deptCode: `D${id}`, sortOrder, status, children,
})

/** 覆盖：数值 ID 顺序、停用节点、停用祖先下的启用子节点、自身/后代防环。 */
const tree = [
  node('1', null, '总部', 0, 1, [
    node('10', '1', '技术部', 2, 1, [
      node('3', '10', '停用小组', 0, 0),
    ]),
    node('2', '1', '人事部', 1, 1),
  ]),
  node('20', null, '停用事业部', 0, 0, [
    node('21', '20', '下属组', 0, 1),
  ]),
  node('9', null, '财务部', 0, 1),
]

test('树各层按 sortOrder ASC、id 数值 ASC 排序，不按字符串字典序', () => {
  const sorted = sortDepartmentTree(tree)
  assert.deepEqual(sorted.map((n) => n.id), ['1', '9', '20'])
  assert.deepEqual(sorted[0].children.map((n) => n.id), ['2', '10'])
  assert.deepEqual(sorted[0].children[1].children.map((n) => n.id), ['3'])
  // 不修改原树
  assert.equal(tree[0].children[0].id, '10')
})

test('扁平化保留深度与父链真实状态，停用祖先不被伪装成启用', () => {
  const rows = flattenDepartments(sortDepartmentTree(tree))
  assert.equal(rows.length, 7)
  const leaf = rows.find((row) => row.node.id === '3')
  assert.equal(leaf.depth, 2)
  assert.deepEqual(leaf.ancestors.map((n) => n.id), ['1', '10'])
  const underDisabled = rows.find((row) => row.node.id === '21')
  assert.equal(underDisabled.node.status, 1)
  assert.equal(underDisabled.ancestors[0].status, 0)
  assert.equal(findDepartment(tree, '21').deptName, '下属组')
  assert.equal(findDepartment(tree, '999'), null)
})

test('后代集合用于防环：包含自身与全部后代，不存在的节点返回空', () => {
  assert.deepEqual(collectSubtreeIds(tree, '10'), ['10', '3'])
  assert.deepEqual(collectSubtreeIds(tree, '1'), ['1', '10', '3', '2'])
  assert.deepEqual(collectSubtreeIds(tree, '999'), [])
})

test('父部门候选排除自身及后代，并要求目标与全部祖先启用', () => {
  const options = parentOptions(tree, { editingId: '10' })
  assert.deepEqual(options.map((option) => option.id), ['1', '2', '9'])
  for (const excluded of ['10', '3', '20', '21']) {
    assert.equal(options.some((option) => option.id === excluded), false)
  }
  assert.equal(options.find((option) => option.id === '1').depth, 0)
  assert.equal(options.find((option) => option.id === '2').depth, 1)
  assert.match(options.find((option) => option.id === '2').label, /D2/)
})

test('原父引用即使停用也保留，符合「原引用不变可保留停用引用」', () => {
  const options = parentOptions(tree, { editingId: '21', keepParentId: '20' })
  assert.equal(options.some((option) => option.id === '20'), true)
  assert.equal(options.some((option) => option.id === '21'), false)
  // 其它停用节点仍不可选
  assert.equal(options.some((option) => option.id === '3'), false)
})

test('业务 ID 校验：正整数十进制字符串且不超过 Long.MAX_VALUE', () => {
  for (const value of ['1', '9', '20', '9223372036854775807']) assert.equal(isValidId(value), true)
  for (const value of ['0', '01', '-1', '1.0', 'abc', '', ' 1', '9223372036854775808', '12345678901234567890', 1, null, undefined]) {
    assert.equal(isValidId(value), false, `${String(value)} 应被拒绝`)
  }
})

test('可选查询条件保留 0 与 false，只省略空值', () => {
  assert.deepEqual(compactParams({ status: 0, sortOrder: 0, flag: false, keyword: '', departmentId: null, x: undefined, keyword2: 'a' }), {
    status: 0, sortOrder: 0, flag: false, keyword2: 'a',
  })
})

test('角色全量替换去重、限 100 项、空数组表示清空', () => {
  assert.deepEqual(normalizeRoleIds(['3', '3', '9']), ['3', '9'])
  assert.deepEqual(normalizeRoleIds([]), [])
  assert.deepEqual(normalizeRoleIds(Array.from({ length: 100 }, (_, i) => String(i + 1))).length, 100)
  assert.throws(() => normalizeRoleIds('3'), TypeError)
  assert.throws(() => normalizeRoleIds(['0']), TypeError)
  assert.throws(() => normalizeRoleIds(Array.from({ length: 101 }, (_, i) => String(i + 1))), RangeError)
})
