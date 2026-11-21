import test from 'node:test'
import assert from 'node:assert/strict'
import {
  collectSubtreeIds, compactParams, findDepartment, flattenDepartments, isValidId,
  normalizeRoleIds, parentOptions, partitionAssignedRoles, sortDepartmentTree,
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

test('sortOrder 脏值（非数字）回退 0，排序结果仍然确定', () => {
  // 服务端 sortOrder 恒为合法整数；脏值转 NaN 会让 sort 行为未定义，统一兜底 0。
  const dirty = [
    { id: '3', sortOrder: 'abc' },
    { id: '1', sortOrder: 5 },
    { id: '2', sortOrder: null },
  ]
  const sorted = sortDepartmentTree(dirty)
  assert.deepEqual(sorted.map((n) => n.id), ['2', '3', '1'])
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
  // 先 trim 再去重：' 5 ' 与 '5' 是同一角色，不裁剪会因空白产生重复项或难定位的非法 ID 报错。
  assert.deepEqual(normalizeRoleIds([' 5 ', '5']), ['5'])
  assert.deepEqual(normalizeRoleIds([' 9 ']), ['9'])
  assert.deepEqual(normalizeRoleIds([]), [])
  assert.deepEqual(normalizeRoleIds(Array.from({ length: 100 }, (_, i) => String(i + 1))).length, 100)
  assert.throws(() => normalizeRoleIds('3'), TypeError)
  assert.throws(() => normalizeRoleIds(['0']), TypeError)
  assert.throws(() => normalizeRoleIds(Array.from({ length: 101 }, (_, i) => String(i + 1))), RangeError)
  // null/undefined 显式报错并指出位置，不能变成 'null'/'undefined' 的含糊错误。
  assert.throws(() => normalizeRoleIds(['3', null]), (error) => error instanceof TypeError && /第 2 项/.test(error.message))
  assert.throws(() => normalizeRoleIds([undefined]), (error) => error instanceof TypeError && /第 1 项/.test(error.message))
})

test('角色回显按完整启用目录拆分，类型不一致不误判、停用项进待移除', () => {
  // 详情中的 id 可能是数字，目录里的 id 是字符串；统一转字符串后才不会把有效项误删。
  const assigned = [
    { id: '2', roleName: '编辑' },
    { id: 9, roleName: '管理员' },
    { id: '5', roleName: '已停用', status: 0 },
    { id: '101', roleName: '第二页角色' },
  ]
  const assignable = [{ id: '2' }, { id: '9' }, { id: '101' }]
  const { selectedRoleIds, removedRoles } = partitionAssignedRoles(assigned, assignable)
  assert.deepEqual(selectedRoleIds.sort(), ['101', '2', '9'])
  assert.deepEqual(removedRoles.map((role) => role.roleName), ['已停用'])
})

test('候选目录为空或被截断时不会把第二页启用角色当作可保留项', () => {
  const assigned = [{ id: '101', roleName: '第二页角色', status: 1 }]
  // 目录只加载到第一页（100 项以内），101 被误判为 removed——这正是必须分页拉全的原因。
  const truncated = partitionAssignedRoles(assigned, [{ id: '2' }])
  assert.deepEqual(truncated.selectedRoleIds, [])
  // 启用缺失项进 missingEnabledRoles（阻止保存），不再混入「将被移除」集合误导用户。
  assert.equal(truncated.removedRoles.length, 0)
  assert.deepEqual(truncated.missingEnabledRoles.map((role) => role.id), ['101'])
  const complete = partitionAssignedRoles(assigned, [{ id: '2' }, { id: '101' }])
  assert.deepEqual(complete.selectedRoleIds, ['101'])
  assert.equal(complete.removedRoles.length, 0)
  assert.equal(complete.missingEnabledRoles.length, 0)
})

test('员工持有的启用角色缺失于目录时单独标记，调用方必须阻止全量替换', () => {
  const assigned = [
    { id: '2', roleName: '编辑', status: 1 },
    { id: '9', roleName: '目录漏掉的启用角色', status: 1 },
    { id: '5', roleName: '已停用', status: 0 },
  ]
  const { selectedRoleIds, removedRoles, missingEnabledRoles } = partitionAssignedRoles(
    assigned,
    [{ id: '2' }, { id: '7' }],
  )
  assert.deepEqual(selectedRoleIds, ['2'])
  // 缺失的启用项只进 missingEnabledRoles（触发保存门禁），不混入「将被移除」集合；
  // 停用项仍进 removed。
  assert.deepEqual(removedRoles.map((role) => role.id), ['5'])
  assert.deepEqual(missingEnabledRoles.map((role) => role.id), ['9'])
})

test('候选目录含 null 项或缺失 ID 的记录时不进可用集合，避免占位键混淆', () => {
  // 目录侧脏数据：null 项、缺 id 的对象都不能参与匹配，否则 String(null)='null'
  // 这样的占位键会混进可用集合（DeepSeek 审查）。
  const assigned = [
    { id: '2', roleName: '编辑' },
    { id: '9', roleName: '管理员' },
  ]
  const { selectedRoleIds, removedRoles } = partitionAssignedRoles(assigned, [
    { id: '2' },
    null,
    { roleName: '缺 ID 的脏记录' },
    { id: null },
  ])
  assert.deepEqual(selectedRoleIds, ['2'])
  assert.deepEqual(removedRoles.map((role) => role.roleName), ['管理员'])
  // 分配列表里的 null 项同样被过滤，不产生 'null' 占位勾选。
  const withNullAssigned = partitionAssignedRoles([{ id: '2' }, null], [{ id: '2' }, { id: '9' }])
  assert.deepEqual(withNullAssigned.selectedRoleIds, ['2'])
  assert.equal(withNullAssigned.removedRoles.length, 0)
})
