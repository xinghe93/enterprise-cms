<script setup>
// 「角色与权限」管理页（B 模块前端，OpenAPI /api/admin/roles、/api/admin/permissions）。
//
// 权限：进入页面先按 OpenAPI GET /api/auth/permissions（x-owner B）加载当前账号
// 有效权限写入会话，再按权限编码门控各操作（沿用 D 的 ReviewListView 先例：
// 每次进入页面都重新拉取，不信任旧快照）；权限状态未知（接口不可用）时按
// 「未下发即放行」的渐进策略保证可联调，与 LogListView 一致。
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
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
  formatDateTime,
  normalizePermissionIds,
  validatePermissionCreateForm,
  validateRoleForm,
} from '../../utils/rolePermission.js'

const vLoading = ElLoading.directive

const session = useSessionStore()
const permissionApi = createPermissionApi(api)

// ── 权限编码：每次进入页面重新拉取，管理员增删权限后旧快照不能作为「已加载」依据 ──
const permissionStatus = ref('idle')
let permissionRequestSeq = 0
let permissionAbortController = null
let disposed = false

const permissionsKnown = computed(() => Array.isArray(session.permissionCodes) && session.permissionCodes.length > 0)
const can = (code) => (!permissionsKnown.value || session.hasPermission(code))

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

// ── 请求守卫：快速翻页/快速打开弹窗时，只允许最新一次请求写回页面状态 ──
function createRequestGuard() {
  let seq = 0
  return { next: () => ++seq, isLatest: (token) => token === seq }
}
const roleListGuard = createRequestGuard()
const permListGuard = createRequestGuard()
const assignGuard = createRequestGuard()

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
    fetchRoles()
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
    fetchRoles()
  } catch {
    // 全局提示已弹出。
  }
}

// ── 分配权限 ──
const assignDialog = reactive({
  visible: false,
  loading: false,
  loadError: '',
  saving: false,
  role: null,
  catalog: [],
})
const assignTableRef = ref(null)
const assignSelectedIds = ref([])
const assignRemovedDisabled = ref([])

function typeTagOf(type) {
  return type === 'API' ? 'primary' : type === 'MENU' ? 'warning' : 'info'
}

async function openAssignDialog(row) {
  if (!canReadRolePermissions.value) {
    ElMessage.warning('当前账号没有角色已分配权限查询权限（role:read-permission）。')
    return
  }
  assignDialog.role = row
  assignDialog.catalog = []
  assignDialog.loadError = ''
  assignDialog.visible = true
  assignDialog.loading = true
  const token = assignGuard.next()
  try {
    // 目录必须与已分配列表同时就绪：候选不完整会让全量替换误删权限。
    const [assigned, catalogResult] = await Promise.all([
      permissionApi.getRolePermissions(row.id),
      permissionApi.listAllPermissions(),
    ])
    if (!assignGuard.isLatest(token) || assignDialog.role?.id !== row.id) return
    assignDialog.catalog = catalogResult.records
    const assignedIds = new Set((Array.isArray(assigned) ? assigned : []).map((item) => String(item.id)))
    // 契约：停用权限不能保留在替换请求中——只回显启用项；已分配的停用项在列表中
    // 单独标记，保存后将从角色移除。
    const disabledAssigned = (Array.isArray(assigned) ? assigned : [])
      .filter((item) => item.status !== 1)
    assignRemovedDisabled.value = disabledAssigned
    assignDialog.loading = false
    await nextTick()
    assignDialog.catalog
      .filter((item) => item.status === 1 && assignedIds.has(String(item.id)))
      .forEach((item) => assignTableRef.value?.toggleRowSelection(item, true))
  } catch (error) {
    if (!assignGuard.isLatest(token) || assignDialog.role?.id !== row.id) return
    assignDialog.loadError = error?.message ? `权限目录加载失败：${error.message}` : '权限目录加载失败，请稍后重试。'
    assignDialog.loading = false
  }
}

function onAssignSelectionChange(selection) {
  assignSelectedIds.value = selection.map((item) => String(item.id))
}

async function submitAssignDialog() {
  if (assignDialog.saving) return
  let permissionIds
  try {
    permissionIds = normalizePermissionIds(assignSelectedIds.value)
  } catch (error) {
    ElMessage.error(error?.message || '权限选择数据异常，请重新选择')
    return
  }
  assignDialog.saving = true
  try {
    await permissionApi.setRolePermissions(assignDialog.role.id, permissionIds)
    ElMessage.success('角色权限已更新')
    assignDialog.visible = false
  } catch {
    // 全局提示已弹出（409：请求中含停用权限；404：权限不存在）。
  } finally {
    assignDialog.saving = false
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
  // 编码与类型不可改，回显只读；MENU/BUTTON 的 path/method 为 null，表单不提供输入。
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
  // MENU/BUTTON 没有 path/method（仅 API 元数据），切换类型时清空避免旧值误提交。
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
  } catch {
    // create/edit 共用同一校验器（编辑表单回显了编码/类型，字段齐全可直接复用）。
    permDialog.errors = validatePermissionCreateForm(permDialog.form)
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
    fetchPermissions()
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
    fetchPermissions()
  } catch {
    // 全局提示已弹出。
  }
}

onMounted(() => {
  loadPermissions()
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
      v-if="permissionStatus === 'unavailable'"
      type="warning"
      :closable="false"
      show-icon
      title="权限状态未知"
      description="暂时无法获取当前账号的有效权限编码（GET /api/auth/permissions 失败），页面按未下发处理放行显示；实际操作以后端鉴权结果为准。"
    />

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
                  <ElButton v-if="canReadRolePermissions || canAssignRolePermissions" link type="primary" @click="openAssignDialog(row)">分配权限</ElButton>
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
        v-if="assignDialog.loadError"
        type="error"
        :closable="false"
        show-icon
        class="assign-alert"
        :title="assignDialog.loadError"
      >
        <template #default>
          <ElButton link type="primary" :disabled="assignDialog.loading" @click="openAssignDialog(assignDialog.role)">重新加载</ElButton>
        </template>
      </ElAlert>
      <div v-loading="assignDialog.loading" class="assign-wrap">
        <ElTable
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
      <p class="dialog-note">已选 {{ assignSelectedIds.length }} 项；全量替换以本次勾选为准，空选表示清空该角色全部权限。</p>
      <template #footer>
        <ElButton @click="assignDialog.visible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="assignDialog.saving"
          :disabled="assignDialog.loading || !!assignDialog.loadError || !canAssignRolePermissions"
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
        <p v-else class="dialog-note">MENU / BUTTON 类型没有 API 路径与方法（仅 API 类型是接口元数据）。</p>
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
