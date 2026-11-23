<script setup>
// 「角色与权限」管理页（B 模块前端，OpenAPI /api/admin/roles、/api/admin/permissions）。
//
// 权限：进入页面先按 OpenAPI GET /api/auth/permissions（x-owner B）加载当前账号
// 有效权限写入会话，再按权限编码严格门控各操作（沿用 D 的 ReviewListView 先例：
// 每次进入页面都重新拉取，不信任旧快照）。评审 P2：区分 idle / loading /
// loaded / unavailable 四态——只有 loaded 才按本次结果门控，空数组（无任何权限）
// 隐藏全部操作且不发列表请求；加载中与加载失败均隐藏操作，失败提供重试入口，
// 不再"未下发即放行"发出会被拒绝的请求。首屏查询在本次权限加载完成后发起。
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElLoading,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus'
import 'element-plus/dist/index.css'
import { api } from '../../api/index.js'
import { createPermissionApi, fetchMyPermissions } from '../../api/permission.js'
import { useSessionStore } from '../../stores/session.js'
import {
  HTTP_METHODS,
  PERMISSION_TYPES,
  buildPermissionCreatePayload,
  buildPermissionUpdatePayload,
  buildRoleCreatePayload,
  buildRoleUpdatePayload,
  findStatusConflicts,
  findUnpreselectableAssigned,
  formatDateTime,
  isAssignSessionCurrent,
  normalizePermissionIds,
  shouldReleaseAssignSaving,
  permissionBaseline,
  permissionBaselineIds,
  validatePermissionCreateForm,
  validateRoleForm,
} from '../../utils/rolePermission.js'

const vLoading = ElLoading.directive

const session = useSessionStore()
const permissionApi = createPermissionApi(api)

// ── 权限编码：每次进入页面重新拉取，管理员增删权限后旧快照不能作为「已加载」依据 ──
// idle / loading / loaded / unavailable：区分「加载中」「已加载但为空」「加载失败」。
// 评审 P2：只有 loaded 才是「本次已确认」，此时严格按权限码门控（空数组=无任何
// 权限，隐藏全部操作、不发列表请求）；其余三态一律隐藏操作，加载失败提供重试。
const permissionStatus = ref('idle')
let permissionRequestSeq = 0
let permissionAbortController = null
let disposed = false

const permissionsKnown = computed(() => permissionStatus.value === 'loaded')
const can = (code) => permissionsKnown.value && session.hasPermission(code)

const canListRoles = computed(() => can('role:list'))
const canCreateRole = computed(() => can('role:create'))
const canUpdateRole = computed(() => can('role:update'))
const canSetRoleStatus = computed(() => can('role:status'))
const canReadRolePermissions = computed(() => can('role:read-permission'))
const canAssignRolePermissions = computed(() => can('role:assign-permission'))
const canListPermissions = computed(() => can('permission:list'))
const canCreatePermission = computed(() => can('permission:create'))
const canUpdatePermission = computed(() => can('permission:update'))
const canSetPermissionStatus = computed(() => can('permission:status'))

async function loadPermissions() {
  const requestSeq = ++permissionRequestSeq
  const tokenAtStart = session.accessToken
  permissionAbortController?.abort()
  permissionAbortController = new AbortController()
  permissionStatus.value = 'loading'
  try {
    const codes = await fetchMyPermissions({ signal: permissionAbortController.signal })
    if (disposed || requestSeq !== permissionRequestSeq || tokenAtStart !== session.accessToken) return
    session.setPermissions(Array.isArray(codes) ? codes : [])
    permissionStatus.value = 'loaded'
  } catch {
    // 请求被取消（页面卸载/被新请求替代）不算加载失败；其余按「权限状态未知」处理。
    if (disposed || requestSeq !== permissionRequestSeq || tokenAtStart !== session.accessToken) return
    if (permissionAbortController?.signal.aborted) return
    permissionStatus.value = 'unavailable'
  }
}

// 权限加载失败后重试：成功后立即按本次结果刷新两个列表（评审 P2）。
async function retryPermissions() {
  await loadPermissions()
  if (permissionsKnown.value) {
    fetchRoles()
    fetchPermissions()
  }
}

// 评审 P2：所有写操作（角色/权限项增删改、启停、分配权限）成功后统一走这里——
// 先重新加载当前账号有效权限，再按最新权限刷新两个列表。操作者撤销自身唯一角色
// 的 permission:list / role:list 等权限、或角色/权限启停改变有效授权后，页面立即
// 反映：会话权限快照更新，且 fetchRoles/fetchPermissions 的既有三态门禁会自动
// 清空已失去访问权的列表，而不是等下一次查询才收到 403。
async function afterWrite() {
  await loadPermissions()
  if (disposed) return
  fetchRoles()
  fetchPermissions()
}

// ── 请求守卫：快速翻页/快速打开弹窗时，只允许最新一次请求写回页面状态 ──
function createRequestGuard() {
  let seq = 0
  return { next: () => ++seq, isLatest: (token) => token === seq }
}
const roleListGuard = createRequestGuard()
const permListGuard = createRequestGuard()
const assignGuard = createRequestGuard()

// 评审 P2 会话竞态：弹窗会话序号。每次打开（openAssignDialog）或关闭
// （onAssignDialogVisibleChange）分配弹窗都递增；在途保存请求捕获打开时的
// 序号，恢复后用 isAssignSessionCurrent 校验——会话已变（关闭/重开/换角色/
// re-open 改写基线）即丢弃旧请求，绝不把旧勾选与新基线混用提交。
let assignSessionSeq = 0

const activeTab = ref('roles')

// ── 角色列表 ──
const roleFilters = reactive({ keyword: '', status: '' })
const roleRows = ref([])
const roleTotal = ref(0)
const roleCurrent = ref(1)
const roleSize = ref(10)
const roleLoading = ref(false)
const roleError = ref('')
const roleLoadedAt = ref('')

async function fetchRoles() {
  const token = roleListGuard.next()
  // 评审 P2：权限未加载完成（idle/loading）或加载失败（unavailable）时不发
  // 列表请求——避免用旧权限跳过查询、或发出会被拒绝的请求。
  if (permissionStatus.value === 'idle' || permissionStatus.value === 'loading') {
    roleRows.value = []
    roleTotal.value = 0
    roleError.value = ''
    roleLoading.value = false
    return
  }
  if (!permissionsKnown.value) {
    roleRows.value = []
    roleTotal.value = 0
    roleError.value = '权限状态加载失败，无法确认查询权限；请点上方「重试加载权限」。'
    roleLoading.value = false
    return
  }
  if (!canListRoles.value) {
    roleRows.value = []
    roleTotal.value = 0
    roleError.value = '当前账号没有角色查询权限（role:list），请联系管理员。'
    roleLoading.value = false
    return
  }
  roleLoading.value = true
  roleError.value = ''
  try {
    const data = await permissionApi.listRoles({
      keyword: roleFilters.keyword,
      status: roleFilters.status,
      current: roleCurrent.value,
      size: roleSize.value,
    })
    if (!roleListGuard.isLatest(token)) return
    roleRows.value = Array.isArray(data?.records) ? data.records : []
    roleTotal.value = Number.isSafeInteger(data?.total) ? data.total : 0
    roleLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (!roleListGuard.isLatest(token)) return
    roleRows.value = []
    roleTotal.value = 0
    roleError.value = error?.message ? `角色列表加载失败：${error.message}` : '角色列表加载失败，请稍后重试。'
  } finally {
    if (roleListGuard.isLatest(token)) roleLoading.value = false
  }
}

function handleRoleSearch() {
  roleCurrent.value = 1
  fetchRoles()
}

function handleRoleReset() {
  roleFilters.keyword = ''
  roleFilters.status = ''
  roleCurrent.value = 1
  fetchRoles()
}

function handleRolePageChange(page) {
  roleCurrent.value = page
  fetchRoles()
}

function handleRoleSizeChange(newSize) {
  roleSize.value = newSize
  roleCurrent.value = 1
  fetchRoles()
}

// ── 角色新建 / 编辑 ──
const roleDialog = reactive({
  mode: 'create',
  visible: false,
  submitting: false,
  row: null,
  form: { roleCode: '', roleName: '' },
  errors: {},
})

function openRoleCreate() {
  if (!canCreateRole.value) {
    ElMessage.warning('当前账号没有创建角色权限（role:create）。')
    return
  }
  roleDialog.mode = 'create'
  roleDialog.row = null
  roleDialog.form = { roleCode: '', roleName: '' }
  roleDialog.errors = {}
  roleDialog.visible = true
}

function openRoleEdit(row) {
  if (!canUpdateRole.value) {
    ElMessage.warning('当前账号没有修改角色权限（role:update）。')
    return
  }
  roleDialog.mode = 'edit'
  roleDialog.row = row
  roleDialog.form = { roleCode: row.roleCode, roleName: row.roleName }
  roleDialog.errors = {}
  roleDialog.visible = true
}

async function submitRoleDialog() {
  if (roleDialog.submitting) return
  const isCreate = roleDialog.mode === 'create'
  // 纯函数先校验并构造 payload，非法输入以行内错误展示，不发请求。
  let payload
  try {
    payload = isCreate ? buildRoleCreatePayload(roleDialog.form) : buildRoleUpdatePayload(roleDialog.form)
  } catch {
    roleDialog.errors = validateRoleForm(roleDialog.form, { withCode: isCreate })
    return
  }
  roleDialog.errors = {}
  roleDialog.submitting = true
  try {
    if (isCreate) {
      await permissionApi.createRole(payload)
      ElMessage.success('角色已创建，默认启用且未分配权限')
    } else {
      await permissionApi.updateRole(roleDialog.row.id, payload)
      ElMessage.success('角色已更新')
    }
    roleDialog.visible = false
    await afterWrite()
  } catch {
    // api/index.js 的 onError 已统一提示（409 编码冲突等），这里只结束加载状态。
  } finally {
    roleDialog.submitting = false
  }
}

// ── 角色启停 ──
async function toggleRoleStatus(row) {
  if (!canSetRoleStatus.value) {
    ElMessage.warning('当前账号没有启停角色权限（role:status）。')
    return
  }
  const target = row.status === 1 ? 0 : 1
  const label = target === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定要${label}角色「${row.roleName}」吗？停用后该角色不再产生有效权限，已分配用户的该角色权限随之失效。`,
      `${label}角色`,
      { type: 'warning', confirmButtonText: `确定${label}`, cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await permissionApi.setRoleStatus(row.id, target)
    ElMessage.success(`角色已${label}`)
    await afterWrite()
  } catch {
    // 全局提示已弹出。
  }
}

// ── 分配权限 ──
// 评审 P2：已分配权限（assigned）与编辑目录（catalog）分离——只读账号（有
// role:read-permission、无 permission:list）也能独立查看已分配，目录 403 不
// 再连累它；目录只在具备所需权限时才加载，用于编辑勾选。
const assignDialog = reactive({
  visible: false,
  loading: false,
  saving: false,
  role: null,
  assigned: [],            // 已分配权限（独立加载、独立展示，只读账号也可见）
  assignedError: '',       // 已分配加载失败的独立提示
  catalog: [],             // 编辑用目录（仅可编辑账号加载）
  catalogLoaded: false,    // 目录是否成功加载完成（可保存的前提）
  loadError: '',           // 目录加载失败提示
  readOnly: false,         // 只读模式：只展示已分配，不请求/不勾选目录
  consistencyError: '',    // 评审 P1：状态不一致时阻止保存并提示重新加载
  baseline: '',            // 评审 P1 并发覆盖：打开弹窗时已分配集合的基线签名（本地快速比对）
  baselineIds: [],         // 评审 P1 并发覆盖：打开弹窗时的基线 ID 数组，提交给后端原子校验
  concurrencyError: '',    // 评审 P1 并发覆盖：基线不一致/保存前重读失败/后端 409 时阻止保存
})
const assignTableRef = ref(null)
const assignSelectedIds = ref([])
const assignRemovedDisabled = ref([])

// 评审 P2 会话竞态：弹窗从打开→关闭（含取消、X、保存成功）即递增会话序号，
// 作废该会话所有在途保存请求。用 watch 统一拦截所有关闭路径，避免遗漏某个
// 模板按钮；flush:'sync' 保证 visible 变 false 的同一时刻旧请求即失效。
watch(() => assignDialog.visible, (visible, wasVisible) => {
  if (wasVisible && !visible) assignSessionSeq += 1
}, { flush: 'sync' })

// 评审 P2：只读账号、或目录未就绪（加载失败/一致性冲突）时展示只读的
// 「已分配权限」列表；只有目录成功加载完成才显示可勾选的编辑目录。
const assignReadOnlyView = computed(() => assignDialog.readOnly || !assignDialog.catalogLoaded)

function typeTagOf(type) {
  return type === 'API' ? 'primary' : type === 'MENU' ? 'warning' : 'info'
}

async function openAssignDialog(row) {
  if (!canReadRolePermissions.value) {
    ElMessage.warning('当前账号没有角色已分配权限查询权限（role:read-permission）。')
    return
  }
  // 评审 P2 会话竞态：每次打开（含"重新加载"重开同一角色）递增会话序号，
  // 让上一会话仍在途的保存请求失效——即使它随后在 await 处恢复也不会提交。
  assignSessionSeq += 1
  // 新会话开始：清除旧会话可能残留的 saving 标志，避免程序化关闭/重开时
  // 旧在途请求的 finally 与新会话互相干扰导致保存按钮卡在 loading。
  assignDialog.saving = false
  assignDialog.role = row
  assignDialog.assigned = []
  assignDialog.assignedError = ''
  assignDialog.catalog = []
  assignDialog.catalogLoaded = false
  assignDialog.loadError = ''
  assignDialog.consistencyError = ''
  assignDialog.baseline = ''
  assignDialog.baselineIds = []
  assignDialog.concurrencyError = ''
  assignRemovedDisabled.value = []
  assignSelectedIds.value = []
  // 只读账号：有查看权、但缺 permission:list（目录接口会 403）或缺
  // role:assign-permission（不能保存）——只展示已分配，绝不请求编辑目录。
  // 评审 P2：把「查看已分配」与「加载编辑目录」解耦，目录 403 不再连累前者。
  const canEdit = canListPermissions.value && canAssignRolePermissions.value
  assignDialog.readOnly = !canEdit
  assignDialog.visible = true
  assignDialog.loading = true
  const token = assignGuard.next()

  // ① 已分配权限：独立加载、独立展示。只读账号也能拿到并看到它。
  let assigned = []
  try {
    const result = await permissionApi.getRolePermissions(row.id)
    if (!assignGuard.isLatest(token) || assignDialog.role?.id !== row.id) return
    assigned = Array.isArray(result) ? result : []
    assignDialog.assigned = assigned
    // 评审 P1 并发覆盖：记录打开弹窗时的已分配集合基线——签名用于本地快速比对，
    // ID 数组提交给后端在角色行锁内原子校验（前端重读无法消除竞争窗口）。
    assignDialog.baseline = permissionBaseline(assigned)
    assignDialog.baselineIds = permissionBaselineIds(assigned)
  } catch (error) {
    if (!assignGuard.isLatest(token) || assignDialog.role?.id !== row.id) return
    assignDialog.assignedError = error?.message
      ? `已分配权限加载失败：${error.message}`
      : '已分配权限加载失败，请稍后重试。'
    assignDialog.loading = false
    return
  }

  // 只读模式到此为止：已分配结果已独立展示，不发目录请求。
  if (!canEdit) {
    assignDialog.loading = false
    return
  }

  // ② 编辑目录：仅在具备 permission:list 且可保存时加载，用于勾选。
  let catalog = []
  try {
    const catalogResult = await permissionApi.listAllPermissions()
    if (!assignGuard.isLatest(token) || assignDialog.role?.id !== row.id) return
    catalog = catalogResult.records
  } catch (error) {
    if (!assignGuard.isLatest(token) || assignDialog.role?.id !== row.id) return
    assignDialog.loadError = error?.message
      ? `权限目录加载失败：${error.message}`
      : '权限目录加载失败，请稍后重试。'
    assignDialog.loading = false
    return
  }

  // ③ 评审 P1/P2 一致性校验：所有「已分配且启用」的权限都必须能在目录中预选
  // （findUnpreselectableAssigned），且两份读取中每个共有权限的 status 必须一致
  // （findStatusConflicts：避免按 assigned 状态提示「移除」、按 catalog 状态预选
  // 「保留」的自相矛盾 UI）。目录与已分配是两次独立请求，期间可能有其他管理员
  // 启停某项。任一校验不通过立即阻止保存并提示重新加载（不进入可勾选状态）。
  const unpreselectable = findUnpreselectableAssigned(assigned, catalog)
  const statusConflicts = findStatusConflicts(assigned, catalog)
  if (unpreselectable.length > 0 || statusConflicts.length > 0) {
    const reasons = []
    if (unpreselectable.length > 0) {
      reasons.push(`${unpreselectable.length} 个已分配且启用的权限在权限目录中无法预选`)
    }
    if (statusConflicts.length > 0) {
      reasons.push(`${statusConflicts.length} 个权限的状态在两次读取之间发生了变化`)
    }
    assignDialog.consistencyError =
      `检测到${reasons.join('、')}（可能有其他管理员刚变更了权限状态），已阻止保存；请重新加载后再分配。`
    assignDialog.loading = false
    return
  }

  // ④ 正常可编辑：装配目录 + 预选启用且已分配的项。
  assignDialog.catalog = catalog
  assignDialog.catalogLoaded = true
  const assignedIds = new Set(assigned.map((item) => String(item.id)))
  // 评审 P2：统一依据同一份（catalog）状态生成「停用·保存后移除」提示——不再
  // 混用 assigned 的 status 判提示、catalog 的 status 判预选，避免两份读取之间
  // 被启停时出现「一边提示移除、一边预选保留」的矛盾。契约：停用权限不能保留
  // 在替换请求中，保存后将从角色移除。
  assignRemovedDisabled.value = catalog
    .filter((item) => item.status !== 1 && assignedIds.has(String(item.id)))
  assignDialog.loading = false
  await nextTick()
  catalog
    .filter((item) => item.status === 1 && assignedIds.has(String(item.id)))
    .forEach((item) => assignTableRef.value?.toggleRowSelection(item, true))
}

function onAssignSelectionChange(selection) {
  assignSelectedIds.value = selection.map((item) => String(item.id))
}

async function submitAssignDialog() {
  if (assignDialog.saving) return
  const roleId = assignDialog.role?.id
  if (roleId == null) return
  // 评审 P1/P2 保存守卫：只读、目录未就绪、加载失败、检测到状态不一致或并发
  // 冲突时，绝不发全量替换（否则会误删角色授权或覆盖其他管理员的变更）。
  // 这些情况下保存按钮也已禁用。
  if (assignDialog.readOnly || !assignDialog.catalogLoaded
      || !!assignDialog.loadError || !!assignDialog.assignedError
      || !!assignDialog.consistencyError || !!assignDialog.concurrencyError
      || !canReadRolePermissions.value || !canListPermissions.value) {
    ElMessage.warning('当前不可保存：权限目录未就绪或状态/并发校验未通过，请重新加载后再分配。')
    return
  }
  let permissionIds
  try {
    // 勾选也固定为局部副本：不能让弹窗重开后 assignSelectedIds 变化影响本次提交。
    permissionIds = normalizePermissionIds(assignSelectedIds.value)
  } catch (error) {
    ElMessage.error(error?.message || '权限选择数据异常，请重新选择')
    return
  }
  // ── 评审 P2 会话竞态：固定本次保存的会话快照 ──
  // 关键：基线 ID 数组与签名、角色、会话序号全部复制为局部常量。旧实现提交时读
  // 共享的 assignDialog.baselineIds，若保存期间取消并重开同一角色，重开会把它
  // 改写为新基线（如 []），旧请求遂用「旧勾选 [X,Y] + 新基线 []」提交，后端校验
  // 通过并恢复其他管理员刚撤销的 X。固定局部副本后，无论弹窗被怎样重开，本次
  // 请求始终携带打开时那一份基线，后端能正确识别冲突并返回 409。
  const savedSession = {
    session: assignSessionSeq,
    roleId,
    baselineIds: [...assignDialog.baselineIds],
    baseline: assignDialog.baseline,
  }
  // 每个 await 之后都重新校验：会话已变（关闭 / 重开 / 换角色）就静默丢弃，不再写
  // 任何共享 UI 状态、也不发 PUT。pure 函数便于 Node 单测复现评审的交错顺序。
  const stillCurrent = () => isAssignSessionCurrent(savedSession, {
    session: assignSessionSeq,
    roleId: assignDialog.role?.id,
    baselineIds: assignDialog.baselineIds,
    visible: assignDialog.visible,
  })

  assignDialog.saving = true
  try {
    // 评审 P1 并发覆盖：保存前重读最新「已分配 + 目录」，与打开弹窗时的基线比对。
    // 全量替换的语义是「最终集合 = 本次提交集合」，后端行锁只能让并发写入依次
    // 执行、不能识别陈旧快照：若弹窗打开期间其他管理员增删过该角色权限（基线
    // 不同），陈旧快照的提交会覆盖对方变更。基线不一致即阻止保存并要求重新加载。
    let latestAssigned = []
    try {
      const result = await permissionApi.getRolePermissions(roleId)
      if (!stillCurrent()) return
      latestAssigned = Array.isArray(result) ? result : []
    } catch (error) {
      if (!stillCurrent()) return
      assignDialog.concurrencyError = error?.message
        ? `保存前重读已分配权限失败：${error.message}；已阻止保存，请重新加载后再分配。`
        : '保存前重读已分配权限失败；已阻止保存，请重新加载后再分配。'
      return
    }
    let latestCatalog = []
    try {
      latestCatalog = (await permissionApi.listAllPermissions()).records
      if (!stillCurrent()) return
    } catch (error) {
      if (!stillCurrent()) return
      assignDialog.concurrencyError = error?.message
        ? `保存前重读权限目录失败：${error.message}；已阻止保存，请重新加载后再分配。`
        : '保存前重读权限目录失败；已阻止保存，请重新加载后再分配。'
      return
    }
    if (!stillCurrent()) return
    // 本地快速比对用固定的基线签名（不是共享对象）。
    if (permissionBaseline(latestAssigned) !== savedSession.baseline) {
      assignDialog.concurrencyError =
        '该角色的权限在弹窗打开期间已被其他管理员变更，为避免覆盖对方修改，已阻止保存；请重新加载后再分配。'
      return
    }
    // 用最新读取的数据重算一致性（此前只校验了打开弹窗时的快照）。
    const lateConflict = findUnpreselectableAssigned(latestAssigned, latestCatalog)
    const lateStatusConflicts = findStatusConflicts(latestAssigned, latestCatalog)
    if (lateConflict.length > 0 || lateStatusConflicts.length > 0) {
      assignDialog.consistencyError =
        '检测到权限状态在弹窗打开期间被其他管理员变更，已阻止保存；请重新加载后再分配。'
      return
    }
    // 会话最后一次校验：PUT 携带固定的局部基线副本（不是共享对象）——这是并发
    // 安全的最终凭据；后端在角色行锁内与当前集合原子比对，冲突返回 409。
    if (!stillCurrent()) return
    await permissionApi.setRolePermissions(roleId, permissionIds, savedSession.baselineIds)
    // PUT 返回后若会话已变（保存期间被关闭/重开），不再改动新会话的 UI：写入已
    // 完成且后端已做原子校验，这里只结束在途请求，不误关新弹窗、不弹成功提示。
    if (!stillCurrent()) return
    ElMessage.success('角色权限已更新')
    assignDialog.visible = false
    // 评审 P2：分配会立即改变当前账号的有效权限（含撤销自身权限的可能），
    // 成功后重载会话权限并刷新列表。
    await afterWrite()
  } catch (error) {
    // 会话已变的在途请求：静默结束，不把错误写到新会话的 UI 上。
    if (!stillCurrent()) return
    // 409 有两种：请求中含停用权限（校验类）和并发基线冲突（写前校验）。两者都
    // 阻止保存；并发冲突特化提示要求重新加载，并在弹窗展示重新加载按钮。
    if (error?.status === 409 || error?.code === 409) {
      assignDialog.concurrencyError =
        '保存失败：该角色的权限已被其他管理员修改（并发冲突），已阻止覆盖；请重新加载后再分配。'
    }
    // 其余错误全局提示已弹出（404：权限/角色不存在；400：参数非法）。
  } finally {
    // 评审 P2（成功路径 saving 残留）：成功时 visible=false 会让 sync watch 递增
    // 会话序号，stillCurrent() 变 false，若据此不清 saving 就会把所有角色的
    // 「分配权限」入口永久禁用。改用 shouldReleaseAssignSaving 区分三种情形：
    // 会话未变 / 本次自己成功关闭（visible=false）→ 清；被新会话接管（会话变且
    // 弹窗仍可见）→ 不清（新会话 open 已自行重置 saving）。
    if (shouldReleaseAssignSaving(
      assignSessionSeq !== savedSession.session,
      assignDialog.visible,
    )) {
      assignDialog.saving = false
    }
  }
}

// ── 权限项列表 ──
const permFilters = reactive({ keyword: '', status: '', permissionType: '' })
const permRows = ref([])
const permTotal = ref(0)
const permCurrent = ref(1)
const permSize = ref(10)
const permLoading = ref(false)
const permError = ref('')
const permLoadedAt = ref('')

async function fetchPermissions() {
  const token = permListGuard.next()
  // 评审 P2：与角色列表一致，权限未加载完成或加载失败时不发列表请求。
  if (permissionStatus.value === 'idle' || permissionStatus.value === 'loading') {
    permRows.value = []
    permTotal.value = 0
    permError.value = ''
    permLoading.value = false
    return
  }
  if (!permissionsKnown.value) {
    permRows.value = []
    permTotal.value = 0
    permError.value = '权限状态加载失败，无法确认查询权限；请点上方「重试加载权限」。'
    permLoading.value = false
    return
  }
  if (!canListPermissions.value) {
    permRows.value = []
    permTotal.value = 0
    permError.value = '当前账号没有权限项查询权限（permission:list），请联系管理员。'
    permLoading.value = false
    return
  }
  permLoading.value = true
  permError.value = ''
  try {
    const data = await permissionApi.listPermissions({
      keyword: permFilters.keyword,
      status: permFilters.status,
      permissionType: permFilters.permissionType,
      current: permCurrent.value,
      size: permSize.value,
    })
    if (!permListGuard.isLatest(token)) return
    permRows.value = Array.isArray(data?.records) ? data.records : []
    permTotal.value = Number.isSafeInteger(data?.total) ? data.total : 0
    permLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (!permListGuard.isLatest(token)) return
    permRows.value = []
    permTotal.value = 0
    permError.value = error?.message ? `权限项列表加载失败：${error.message}` : '权限项列表加载失败，请稍后重试。'
  } finally {
    if (permListGuard.isLatest(token)) permLoading.value = false
  }
}

function handlePermSearch() {
  permCurrent.value = 1
  fetchPermissions()
}

function handlePermReset() {
  permFilters.keyword = ''
  permFilters.status = ''
  permFilters.permissionType = ''
  permCurrent.value = 1
  fetchPermissions()
}

function handlePermPageChange(page) {
  permCurrent.value = page
  fetchPermissions()
}

function handlePermSizeChange(newSize) {
  permSize.value = newSize
  permCurrent.value = 1
  fetchPermissions()
}

// ── 权限项新建 / 编辑 ──
const permDialog = reactive({
  mode: 'create',
  visible: false,
  submitting: false,
  row: null,
  form: { permissionCode: '', permissionName: '', permissionType: 'API', path: '', method: '' },
  errors: {},
})

function isApiType(type) {
  return type === 'API'
}

function openPermCreate() {
  if (!canCreatePermission.value) {
    ElMessage.warning('当前账号没有创建权限项权限（permission:create）。')
    return
  }
  permDialog.mode = 'create'
  permDialog.row = null
  permDialog.form = { permissionCode: '', permissionName: '', permissionType: 'API', path: '', method: '' }
  permDialog.errors = {}
  permDialog.visible = true
}

function openPermEdit(row) {
  if (!canUpdatePermission.value) {
    ElMessage.warning('当前账号没有修改权限项权限（permission:update）。')
    return
  }
  permDialog.mode = 'edit'
  permDialog.row = row
  // 编码与类型不可改，回显只读。评审 P2：MENU/BUTTON 的 path 是「菜单路由」，
  // payload 以数据库已有值 row.path 为准【原样提交】（不 trim、不按 JS length
  // 复检长度，见 buildPermissionUpdatePayload）；form.path 只是编辑表单的回显
  // 载体（这些类型不显示路径输入框，仅 row 缺 path 字段时才作回退源）。
  permDialog.form = {
    permissionCode: row.permissionCode,
    permissionName: row.permissionName,
    permissionType: row.permissionType,
    path: row.path ?? '',
    method: row.method ?? '',
  }
  permDialog.errors = {}
  permDialog.visible = true
}

function onPermTypeChange() {
  // MENU/BUTTON 的 path 是菜单路由：编辑时类型选择框禁用，本函数只在创建时触发；
  // 创建不采集菜单路由（提交 null 是合法终态），清空只是防 API 旧值串到 MENU/BUTTON。
  // 编辑场景的 payload 以 row.path 为准原样提交（见 buildPermissionUpdatePayload），
  // 不受 form.path 被清空影响。
  if (!isApiType(permDialog.form.permissionType)) {
    permDialog.form.path = ''
    permDialog.form.method = ''
  }
  permDialog.errors = {}
}

async function submitPermDialog() {
  if (permDialog.submitting) return
  const isCreate = permDialog.mode === 'create'
  let payload
  try {
    payload = isCreate
      ? buildPermissionCreatePayload(permDialog.form)
      : buildPermissionUpdatePayload(permDialog.row, permDialog.form)
  } catch (error) {
    // create/edit 共用同一校验器（编辑表单回显了编码/类型，字段齐全可直接复用）。
    permDialog.errors = validatePermissionCreateForm(permDialog.form)
    // 评审 P2 兜底：纯函数抛错但校验器没有对应字段可提示时，静默 return 会让
    // 页面看起来「点了保存没反应」——此时给全局提示。
    if (Object.keys(permDialog.errors).length === 0) {
      ElMessage.error(error?.message || '保存失败，请检查输入或稍后重试')
    }
    return
  }
  permDialog.errors = {}
  permDialog.submitting = true
  try {
    if (isCreate) {
      await permissionApi.createPermission(payload)
      ElMessage.success('权限项已创建，默认启用')
    } else {
      await permissionApi.updatePermission(permDialog.row.id, payload)
      ElMessage.success('权限项已更新')
    }
    permDialog.visible = false
    await afterWrite()
  } catch {
    // 全局提示已弹出（409：编码/名称冲突；400：格式错误）。
  } finally {
    permDialog.submitting = false
  }
}

// ── 权限项启停 ──
async function togglePermStatus(row) {
  if (!canSetPermissionStatus.value) {
    ElMessage.warning('当前账号没有启停权限项权限（permission:status）。')
    return
  }
  const target = row.status === 1 ? 0 : 1
  const label = target === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定要${label}权限项「${row.permissionName}」吗？停用后该权限不再计入任何角色的有效权限。`,
      `${label}权限项`,
      { type: 'warning', confirmButtonText: `确定${label}`, cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await permissionApi.setPermissionStatus(row.id, target)
    ElMessage.success(`权限项已${label}`)
    await afterWrite()
  } catch {
    // 全局提示已弹出。
  }
}

// 评审 P2：先完成本次权限加载，再按本次结果发起首屏查询——不用会话里的旧
// 权限跳过查询（刚获授权的用户无需手动刷新），空权限也不会发出被拒绝的请求。
onMounted(async () => {
  await loadPermissions()
  if (disposed) return
  fetchRoles()
  fetchPermissions()
})

onUnmounted(() => {
  disposed = true
  permissionAbortController?.abort()
})
</script>

<template>
  <section class="module-page role-permission-page">
    <div class="page-heading">
      <p class="eyebrow">ACCESS CONTROL</p>
      <h1>角色与权限</h1>
      <p>维护角色与权限项，为角色分配接口权限；变更提交后立即影响相关账号的有效权限。</p>
    </div>

    <ElAlert
      v-if="permissionStatus === 'loading' || permissionStatus === 'idle'"
      type="info"
      :closable="false"
      show-icon
      title="正在加载当前账号权限…"
      description="权限加载完成后将按本次结果发起列表查询；加载期间不显示操作、不发出请求。"
    />
    <ElAlert
      v-else-if="permissionStatus === 'unavailable'"
      type="warning"
      :closable="false"
      show-icon
      title="权限列表加载失败，操作入口已隐藏；请重试加载权限。"
    >
      <template #default>
        <ElButton size="small" @click="retryPermissions">重试加载权限</ElButton>
      </template>
    </ElAlert>

    <ElTabs v-model="activeTab" class="role-perm-tabs">
      <ElTabPane label="角色管理" name="roles">
        <section class="filter-card" aria-label="角色筛选">
          <div class="filter-grid">
            <label class="filter-field">
              <span>关键词</span>
              <ElInput v-model="roleFilters.keyword" placeholder="编码或名称，字面包含" clearable maxlength="100" @keyup.enter="handleRoleSearch" />
            </label>
            <label class="filter-field">
              <span>状态</span>
              <ElSelect v-model="roleFilters.status" placeholder="全部" clearable>
                <ElOption label="启用" :value="1" />
                <ElOption label="停用" :value="0" />
              </ElSelect>
            </label>
          </div>
          <div class="filter-actions">
            <ElButton type="primary" :disabled="!canListRoles" :loading="roleLoading" @click="handleRoleSearch">查询</ElButton>
            <ElButton :disabled="!canListRoles || roleLoading" @click="handleRoleReset">重置</ElButton>
            <ElButton v-if="canCreateRole" type="primary" plain @click="openRoleCreate">新建角色</ElButton>
          </div>
        </section>

        <ElAlert v-if="roleError" type="error" :closable="false" show-icon :title="roleError">
          <template #default>
            <ElButton link type="primary" :disabled="roleLoading || !canListRoles" @click="fetchRoles">重新加载</ElButton>
          </template>
        </ElAlert>

        <section class="table-card" aria-label="角色列表">
          <div class="table-meta">
            <span>共 {{ roleTotal }} 个角色</span>
            <span v-if="roleLoadedAt">最近查询：{{ roleLoadedAt }}</span>
          </div>
          <div v-loading="roleLoading" class="table-wrap">
            <ElTable :data="roleRows" row-key="id" border stripe>
              <ElTableColumn label="角色编码" min-width="150">
                <template #default="{ row }"><span class="mono">{{ row.roleCode }}</span></template>
              </ElTableColumn>
              <ElTableColumn prop="roleName" label="角色名称" min-width="160" />
              <ElTableColumn label="状态" width="92">
                <template #default="{ row }">
                  <ElTag :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="创建时间" min-width="168">
                <template #default="{ row }"><span class="mono">{{ formatDateTime(row.createdAt) }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="更新时间" min-width="168">
                <template #default="{ row }"><span class="mono">{{ formatDateTime(row.updatedAt) }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="200" fixed="right">
                <template #default="{ row }">
                  <ElButton v-if="canUpdateRole" link type="primary" @click="openRoleEdit(row)">编辑</ElButton>
                  <ElButton v-if="canSetRoleStatus" link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleRoleStatus(row)">
                    {{ row.status === 1 ? '停用' : '启用' }}
                  </ElButton>
                  <ElButton v-if="canReadRolePermissions || canAssignRolePermissions" link type="primary" :disabled="assignDialog.saving" @click="openAssignDialog(row)">分配权限</ElButton>
                  <span
                    v-if="!canUpdateRole && !canSetRoleStatus && !canReadRolePermissions && !canAssignRolePermissions"
                    class="muted-text"
                  >无权限</span>
                </template>
              </ElTableColumn>
              <template #empty><ElEmpty description="暂无角色数据" /></template>
            </ElTable>
          </div>
          <div class="pagination-wrap">
            <ElPagination
              background
              layout="total, sizes, prev, pager, next, jumper"
              :total="roleTotal"
              :current-page="roleCurrent"
              :page-size="roleSize"
              :page-sizes="[10, 20, 50, 100]"
              @current-change="handleRolePageChange"
              @size-change="handleRoleSizeChange"
            />
          </div>
        </section>
      </ElTabPane>

      <ElTabPane label="权限项" name="permissions">
        <section class="filter-card" aria-label="权限项筛选">
          <div class="filter-grid">
            <label class="filter-field">
              <span>关键词</span>
              <ElInput v-model="permFilters.keyword" placeholder="编码或名称，字面包含" clearable maxlength="100" @keyup.enter="handlePermSearch" />
            </label>
            <label class="filter-field">
              <span>状态</span>
              <ElSelect v-model="permFilters.status" placeholder="全部" clearable>
                <ElOption label="启用" :value="1" />
                <ElOption label="停用" :value="0" />
              </ElSelect>
            </label>
            <label class="filter-field">
              <span>类型</span>
              <ElSelect v-model="permFilters.permissionType" placeholder="全部" clearable>
                <ElOption v-for="type in PERMISSION_TYPES" :key="type" :label="type" :value="type" />
              </ElSelect>
            </label>
          </div>
          <div class="filter-actions">
            <ElButton type="primary" :disabled="!canListPermissions" :loading="permLoading" @click="handlePermSearch">查询</ElButton>
            <ElButton :disabled="!canListPermissions || permLoading" @click="handlePermReset">重置</ElButton>
            <ElButton v-if="canCreatePermission" type="primary" plain @click="openPermCreate">新建权限项</ElButton>
          </div>
        </section>

        <ElAlert v-if="permError" type="error" :closable="false" show-icon :title="permError">
          <template #default>
            <ElButton link type="primary" :disabled="permLoading || !canListPermissions" @click="fetchPermissions">重新加载</ElButton>
          </template>
        </ElAlert>

        <section class="table-card" aria-label="权限项列表">
          <div class="table-meta">
            <span>共 {{ permTotal }} 个权限项</span>
            <span v-if="permLoadedAt">最近查询：{{ permLoadedAt }}</span>
          </div>
          <div v-loading="permLoading" class="table-wrap">
            <ElTable :data="permRows" row-key="id" border stripe>
              <ElTableColumn label="权限编码" min-width="180">
                <template #default="{ row }"><span class="mono">{{ row.permissionCode }}</span></template>
              </ElTableColumn>
              <ElTableColumn prop="permissionName" label="权限名称" min-width="150" />
              <ElTableColumn label="类型" width="96">
                <template #default="{ row }">
                  <ElTag :type="typeTagOf(row.permissionType)" effect="plain">{{ row.permissionType }}</ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="路径" min-width="220" show-overflow-tooltip>
                <template #default="{ row }"><span class="mono">{{ row.path || '—' }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="方法" width="90">
                <template #default="{ row }"><span class="mono">{{ row.method || '—' }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="状态" width="92">
                <template #default="{ row }">
                  <ElTag :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="更新时间" min-width="168">
                <template #default="{ row }"><span class="mono">{{ formatDateTime(row.updatedAt) }}</span></template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <ElButton v-if="canUpdatePermission" link type="primary" @click="openPermEdit(row)">编辑</ElButton>
                  <ElButton v-if="canSetPermissionStatus" link :type="row.status === 1 ? 'danger' : 'success'" @click="togglePermStatus(row)">
                    {{ row.status === 1 ? '停用' : '启用' }}
                  </ElButton>
                  <span v-if="!canUpdatePermission && !canSetPermissionStatus" class="muted-text">无权限</span>
                </template>
              </ElTableColumn>
              <template #empty><ElEmpty description="暂无权限项数据" /></template>
            </ElTable>
          </div>
          <div class="pagination-wrap">
            <ElPagination
              background
              layout="total, sizes, prev, pager, next, jumper"
              :total="permTotal"
              :current-page="permCurrent"
              :page-size="permSize"
              :page-sizes="[10, 20, 50, 100]"
              @current-change="handlePermPageChange"
              @size-change="handlePermSizeChange"
            />
          </div>
        </section>
      </ElTabPane>
    </ElTabs>

    <ElDialog
      v-model="roleDialog.visible"
      :title="roleDialog.mode === 'create' ? '新建角色' : '编辑角色'"
      width="480px"
      :close-on-click-modal="false"
    >
      <ElForm label-position="top" @submit.prevent="submitRoleDialog">
        <ElFormItem label="角色编码" :error="roleDialog.errors.roleCode">
          <ElInput
            v-model="roleDialog.form.roleCode"
            :disabled="roleDialog.mode === 'edit'"
            maxlength="64"
            placeholder="创建后不可修改，例如 content-editor"
            @keyup.enter="submitRoleDialog"
          />
        </ElFormItem>
        <ElFormItem label="角色名称" :error="roleDialog.errors.roleName">
          <ElInput v-model="roleDialog.form.roleName" maxlength="100" placeholder="例如 内容编辑" @keyup.enter="submitRoleDialog" />
        </ElFormItem>
        <p v-if="roleDialog.mode === 'create'" class="dialog-note">新角色默认启用且未分配任何权限。</p>
      </ElForm>
      <template #footer>
        <ElButton @click="roleDialog.visible = false">取消</ElButton>
        <ElButton type="primary" :loading="roleDialog.submitting" @click="submitRoleDialog">
          {{ roleDialog.mode === 'create' ? '创建' : '保存' }}
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="assignDialog.visible"
      :title="`分配权限 · ${assignDialog.role?.roleName ?? ''}`"
      width="680px"
      :close-on-click-modal="false"
      :show-close="!assignDialog.saving"
      :close-on-press-escape="!assignDialog.saving"
    >
      <ElAlert
        v-if="assignRemovedDisabled.length > 0"
        type="warning"
        :closable="false"
        show-icon
        class="assign-alert"
        :title="`该角色当前已分配 ${assignRemovedDisabled.length} 个停用权限项`"
        description="契约要求停用权限不能保留在替换请求中：它们不可勾选，保存后将从角色中移除。"
      />
      <ElAlert
        v-if="assignDialog.consistencyError"
        type="error"
        :closable="false"
        show-icon
        class="assign-alert"
        :title="assignDialog.consistencyError"
      >
        <template #default>
          <ElButton link type="primary" :disabled="assignDialog.loading || assignDialog.saving" @click="openAssignDialog(assignDialog.role)">重新加载</ElButton>
        </template>
      </ElAlert>
      <!-- 评审 P1 并发覆盖：保存前基线比对不通过/重读失败时阻止保存并提示重新加载。 -->
      <ElAlert
        v-if="assignDialog.concurrencyError"
        type="error"
        :closable="false"
        show-icon
        class="assign-alert"
        :title="assignDialog.concurrencyError"
      >
        <template #default>
          <ElButton link type="primary" :disabled="assignDialog.loading || assignDialog.saving" @click="openAssignDialog(assignDialog.role)">重新加载</ElButton>
        </template>
      </ElAlert>
      <ElAlert
        v-if="assignDialog.loadError || assignDialog.assignedError"
        type="error"
        :closable="false"
        show-icon
        class="assign-alert"
        :title="assignDialog.assignedError || assignDialog.loadError"
      >
        <template #default>
          <ElButton link type="primary" :disabled="assignDialog.loading || assignDialog.saving" @click="openAssignDialog(assignDialog.role)">重新加载</ElButton>
        </template>
      </ElAlert>
      <ElAlert
        v-if="assignDialog.readOnly && !assignDialog.assignedError && !assignDialog.consistencyError && !assignDialog.concurrencyError"
        type="info"
        :closable="false"
        show-icon
        class="assign-alert"
        title="只读查看"
        description="当前账号缺少权限项目录（permission:list）或角色分配（role:assign-permission）权限，仅展示该角色已分配的权限，不加载编辑目录、不可保存。"
      />
      <div v-loading="assignDialog.loading" class="assign-wrap">
        <!-- 只读视图：独立展示已分配权限（评审 P2），不依赖目录请求结果。 -->
        <ElTable
          v-if="assignReadOnlyView"
          :data="assignDialog.assigned"
          row-key="id"
          max-height="360"
          border
        >
          <ElTableColumn label="权限编码" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.permissionCode }}</span></template>
          </ElTableColumn>
          <ElTableColumn prop="permissionName" label="权限名称" min-width="140" />
          <ElTableColumn label="类型" width="90">
            <template #default="{ row }">
              <ElTag :type="typeTagOf(row.permissionType)" effect="plain">{{ row.permissionType }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="92">
            <template #default="{ row }">
              <ElTag :type="row.status === 1 ? 'success' : 'danger'" effect="plain">
                {{ row.status === 1 ? '启用' : '停用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <template #empty><ElEmpty description="该角色暂未分配任何权限" /></template>
        </ElTable>
        <!-- 编辑视图：可勾选目录（仅在目录加载完成后出现）。 -->
        <ElTable
          v-else
          ref="assignTableRef"
          :data="assignDialog.catalog"
          row-key="id"
          max-height="360"
          border
          @selection-change="onAssignSelectionChange"
        >
          <ElTableColumn type="selection" width="46" :selectable="(row) => row.status === 1" />
          <ElTableColumn label="权限编码" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.permissionCode }}</span></template>
          </ElTableColumn>
          <ElTableColumn prop="permissionName" label="权限名称" min-width="140" />
          <ElTableColumn label="类型" width="90">
            <template #default="{ row }">
              <ElTag :type="typeTagOf(row.permissionType)" effect="plain">{{ row.permissionType }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="120">
            <template #default="{ row }">
              <ElTag v-if="row.status === 1" type="success" effect="plain">启用</ElTag>
              <ElTag v-else-if="assignRemovedDisabled.some((item) => String(item.id) === String(row.id))" type="danger" effect="plain">
                停用·保存后移除
              </ElTag>
              <ElTag v-else type="danger" effect="plain">停用</ElTag>
            </template>
          </ElTableColumn>
          <template #empty><ElEmpty description="暂无权限项目录" /></template>
        </ElTable>
      </div>
      <p v-if="assignReadOnlyView" class="dialog-note">只读视图：以上为该角色当前已分配的权限，不加载编辑目录、不可修改。</p>
      <p v-else class="dialog-note">已选 {{ assignSelectedIds.length }} 项；全量替换以本次勾选为准，空选表示清空该角色全部权限。</p>
      <template #footer>
        <!-- 评审 P2 会话竞态：保存期间禁用取消，避免关闭/重开打断在途请求 -->
        <ElButton :disabled="assignDialog.saving" @click="assignDialog.visible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="assignDialog.saving"
          :disabled="assignDialog.loading || assignDialog.saving || assignDialog.readOnly || !assignDialog.catalogLoaded
            || !!assignDialog.loadError || !!assignDialog.assignedError || !!assignDialog.consistencyError
            || !!assignDialog.concurrencyError || !canAssignRolePermissions"
          @click="submitAssignDialog"
        >保存分配</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="permDialog.visible"
      :title="permDialog.mode === 'create' ? '新建权限项' : '编辑权限项'"
      width="520px"
      :close-on-click-modal="false"
    >
      <ElForm label-position="top" @submit.prevent="submitPermDialog">
        <ElFormItem label="权限编码" :error="permDialog.errors.permissionCode">
          <ElInput
            v-model="permDialog.form.permissionCode"
            :disabled="permDialog.mode === 'edit'"
            maxlength="100"
            placeholder="创建后不可修改，例如 article:publish"
            @keyup.enter="submitPermDialog"
          />
        </ElFormItem>
        <ElFormItem label="权限名称" :error="permDialog.errors.permissionName">
          <ElInput v-model="permDialog.form.permissionName" maxlength="100" placeholder="例如 发布文章" @keyup.enter="submitPermDialog" />
        </ElFormItem>
        <ElFormItem label="权限类型" :error="permDialog.errors.permissionType">
          <ElSelect
            :model-value="permDialog.form.permissionType"
            :disabled="permDialog.mode === 'edit'"
            @update:model-value="(value) => { permDialog.form.permissionType = value; onPermTypeChange() }"
          >
            <ElOption v-for="type in PERMISSION_TYPES" :key="type" :label="type" :value="type" />
          </ElSelect>
        </ElFormItem>
        <template v-if="isApiType(permDialog.form.permissionType)">
          <ElFormItem label="API 路径" :error="permDialog.errors.path">
            <ElInput v-model="permDialog.form.path" maxlength="255" placeholder="/api/admin/xxx，可含 {id}" @keyup.enter="submitPermDialog" />
          </ElFormItem>
          <ElFormItem label="HTTP 方法" :error="permDialog.errors.method">
            <ElSelect v-model="permDialog.form.method" placeholder="选择方法" clearable>
              <ElOption v-for="method in HTTP_METHODS" :key="method" :label="method" :value="method" />
            </ElSelect>
          </ElFormItem>
        </template>
        <p v-else class="dialog-note">
          {{ permDialog.mode === 'edit' ? 'MENU / BUTTON 的 path 为菜单路由，保存时按已有值原样保留，不会被清空。' : 'MENU / BUTTON 类型不采集菜单路由与接口方法（创建时提交 null）。' }}
        </p>
        <p v-if="permDialog.mode === 'create'" class="dialog-note">新权限项默认启用；创建后编码与类型不可修改。</p>
      </ElForm>
      <template #footer>
        <ElButton @click="permDialog.visible = false">取消</ElButton>
        <ElButton type="primary" :loading="permDialog.submitting" @click="submitPermDialog">
          {{ permDialog.mode === 'create' ? '创建' : '保存' }}
        </ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.page-heading {
  margin-bottom: 18px;
}

.eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  letter-spacing: 0.14em;
  color: var(--muted, #6b7280);
}

.page-heading h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.page-heading > p:last-child {
  margin: 0;
  font-size: 13px;
  color: var(--muted, #6b7280);
}

.role-perm-tabs {
  margin-top: 6px;
}

.filter-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 14px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.filter-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--muted, #6b7280);
}

.filter-field > span {
  font-weight: 600;
  letter-spacing: 0.02em;
}

.filter-actions {
  display: flex;
  gap: 10px;
}

.table-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
}

.table-meta {
  display: flex;
  justify-content: space-between;
  padding: 0 2px 10px;
  font-size: 13px;
  color: var(--muted, #6b7280);
}

.table-wrap {
  min-height: 180px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.assign-alert {
  margin-bottom: 12px;
}

.assign-wrap {
  min-height: 160px;
}

.dialog-note {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--muted, #9ca3af);
}

.muted-text {
  color: var(--muted, #9ca3af);
  font-size: 12.5px;
}

@media (max-width: 640px) {
  .filter-card {
    padding: 14px;
  }

  .pagination-wrap {
    justify-content: center;
  }
}
</style>
