import test from 'node:test'
import assert from 'node:assert/strict'
import {
  collectSubtreeIds, compactParams, findDepartment, flattenDepartments, isValidId,
  normalizeRoleIds, parentOptions, partitionAssignedRoles, roleSaveBlocked, sortDepartmentTree,
  treeFailureFilterPatch, validateAccountForm,
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

test('账号表单：新建校验账号/密码/姓名/部门，长度与纯空白按后端契约判定', () => {
  const allowed = new Set(['3'])
  const valid = { username: 'zhangsan', password: 'Passw0rd!', realName: '张三', departmentId: '3' }
  assert.equal(validateAccountForm('create', valid, allowed), '')

  // 必填项与长度：与 UserCreateRequestDeserializer 一致（1-64 / 8-128 / 1-100）。
  assert.match(validateAccountForm('create', { ...valid, username: '' }), /登录账号/)
  assert.match(validateAccountForm('create', { ...valid, username: 'x'.repeat(65) }), /登录账号/)
  assert.match(validateAccountForm('create', { ...valid, password: 'short' }), /密码/)
  assert.match(validateAccountForm('create', { ...valid, password: 'x'.repeat(129) }), /密码/)
  assert.match(validateAccountForm('create', { ...valid, realName: '' }), /姓名/)
  assert.match(validateAccountForm('create', { ...valid, realName: 'x'.repeat(101) }), /姓名/)

  // 纯空白（含 U+3000 全角空格）必须拒绝，与后端 strip() 判定一致。
  assert.match(validateAccountForm('create', { ...valid, realName: '　　　' }), /姓名/)
  assert.match(validateAccountForm('create', { ...valid, username: '   ' }), /登录账号/)

  // 部门可空；非空必须是合法 ID 且在候选集合内。
  assert.equal(validateAccountForm('create', { ...valid, departmentId: '' }), '')
  assert.equal(validateAccountForm('create', { ...valid, departmentId: null }), '')
  assert.match(validateAccountForm('create', { ...valid, departmentId: '0' }), /部门 ID/)
  assert.match(validateAccountForm('create', { ...valid, departmentId: '9' }, allowed), /所属部门不可选/)
})

test('账号表单：字段长度按 Unicode 代码点计数，覆盖新建与编辑路径', () => {
  // 老师复审 P2：输入框已按代码点允许输入，保存校验若仍按 UTF-16 length()
  // 就会拒绝合法值（64 个「𠮷」用户名 / 100 个「𠮷」姓名 / 128 个「𠮷」密码），
  // 或放行不足下限的 4 个「𠮷」密码（raw.length=8 却只有 4 个字符）。
  const supplementary = '𠮷'
  const valid = {
    username: supplementary.repeat(64), // raw.length()=128
    password: supplementary.repeat(128), // raw.length()=256
    realName: supplementary.repeat(100), // raw.length()=200
    departmentId: null,
  }
  assert.equal(validateAccountForm('create', valid), '', '创建：上限内的代码点值必须放行')

  assert.match(validateAccountForm('create', { ...valid, username: supplementary.repeat(65) }), /登录账号/)
  assert.match(validateAccountForm('create', { ...valid, password: supplementary.repeat(129) }), /密码/)
  assert.match(validateAccountForm('create', { ...valid, realName: supplementary.repeat(101) }), /姓名/)
  assert.match(validateAccountForm('create', { ...valid, password: supplementary.repeat(4) }), /密码/,
    '4 个代码点的密码不足 8 位，不能靠代理对凑够 raw.length()=8')

  // 编辑路径：完整 PUT 只校验姓名/部门，100 个代码点的姓名同样必须放行。
  assert.equal(validateAccountForm('edit', { realName: supplementary.repeat(100), departmentId: null }), '',
    '编辑：上限内的代码点姓名必须放行')
  assert.match(validateAccountForm('edit', { realName: supplementary.repeat(101), departmentId: null }), /姓名/)
})

test('账号表单：部门树加载失败时编辑可保留详情原部门，换部门仍拒绝', () => {
  const emptyTree = new Set()
  assert.equal(validateAccountForm('edit', { realName: '张三', departmentId: '3' }, emptyTree, '3'), '',
    '只改姓名、保留详情原部门应允许保存')
  assert.match(validateAccountForm('edit', { realName: '张三', departmentId: '9' }, emptyTree, '3'),
    /所属部门不可选/, '换成其他部门时候选缺失，无法验证启用链，仍须拒绝')
  assert.match(validateAccountForm('create',
    { username: 'zhangsan', password: 'Passw0rd!', realName: '张三', departmentId: '3' },
    emptyTree, '3'), /所属部门不可选/, '新建没有详情原部门可依据')
  // 树成功但原部门停用 / 已不在候选时，只要仍提交详情原值就允许保留。
  assert.equal(validateAccountForm('edit', { realName: '张三', departmentId: '3' }, new Set(['9']), '3'), '',
    '原引用不变可保留停用/不可选部门')
  // 换成其他部门时仍按候选规则校验，不能因传入 retained 而放宽。
  assert.match(validateAccountForm('edit', { realName: '张三', departmentId: '9' }, new Set(['3']), '3'),
    /所属部门不可选/)
})

test('账号表单：编辑只校验姓名与部门，不要求账号/密码，保留原停用部门引用', () => {
  const allowed = new Set(['3'])
  assert.equal(validateAccountForm('edit', { realName: '张三', departmentId: '3' }, allowed), '')
  // 编辑模式不校验 username/password——它们不在完整 PUT 的请求体里。
  assert.equal(validateAccountForm('edit', { realName: '张三', departmentId: '', username: '', password: '' }), '')
  assert.match(validateAccountForm('edit', { realName: '', departmentId: '3' }), /姓名/)
  assert.match(validateAccountForm('edit', { realName: '张三', departmentId: '9' }, allowed), /所属部门不可选/)
  assert.match(validateAccountForm('unknown', { realName: '张三' }), /模式/)
})

test('角色保存门禁：目录失败/详情未就绪/启用角色缺失任一成立即阻止全量替换', () => {
  // 纯函数行为断言，取代对 .vue 源码的正则匹配（DeepSeek 审查：正则证明不了门禁）。
  assert.equal(roleSaveBlocked({ rolesLoadFailed: false, roleDetailFailed: false, missingEnabledCount: 0 }), false)
  assert.equal(roleSaveBlocked({ rolesLoadFailed: true, roleDetailFailed: false, missingEnabledCount: 0 }), true)
  assert.equal(roleSaveBlocked({ rolesLoadFailed: false, roleDetailFailed: true, missingEnabledCount: 0 }), true)
  assert.equal(roleSaveBlocked({ rolesLoadFailed: false, roleDetailFailed: false, missingEnabledCount: 1 }), true)
  assert.equal(roleSaveBlocked({ rolesLoadFailed: true, roleDetailFailed: true, missingEnabledCount: 2 }), true)
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

test('部门树失败后：已选部门筛选必须重置回全部部门并回到第 1 页', () => {
  // 老师复审 P2 路径：先选部门 → 刷新时部门树请求失败 → 清除筛选并查询全部员工。
  // 下拉在 treeFailed 时被禁用，旧 ID 若不清除会永久锁死过滤条件。
  assert.deepEqual(
    treeFailureFilterPatch({ keyword: '', departmentId: '7', status: '', current: 3, size: 10 }),
    { departmentId: '', current: 1 },
    '旧部门 ID 必须清除，且页码回到第 1 页重新查询',
  )
  // 数字型脏值（理论上 select 只产字符串）同样被视为「已选部门」需要重置。
  assert.deepEqual(treeFailureFilterPatch({ departmentId: 7 }), { departmentId: '', current: 1 })
  // 防御：缺失 filters 对象不抛错，视为无需处置。
  assert.equal(treeFailureFilterPatch(), null)
  assert.equal(treeFailureFilterPatch(null), null)
  // 本来就是「全部部门」：返回 null，不触发重复请求（首次进入页面树失败的场景）。
  assert.equal(treeFailureFilterPatch({ departmentId: '' }), null)
  assert.equal(treeFailureFilterPatch({ departmentId: null }), null)
  assert.equal(treeFailureFilterPatch({ departmentId: undefined }), null)
})
