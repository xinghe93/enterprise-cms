<script setup>
/**
 * 部门管理（功能② 部门与角色分配 · 部门树部分）。
 *
 * 契约要点：《接口与协作约定 V1.0》5 / 7 节
 *  - 树按 status 筛选时由后端保留必要祖先的真实状态，前端不把停用祖先伪装成启用；
 *  - 更新是完整 PUT，parentId / deptName / sortOrder 均必填，deptCode 不可改；
 *  - 禁止把节点移到自身或后代下；切换父节点时目标与全部祖先必须启用；
 *  - 存在子部门或任何员工引用（含停用员工）时删除返回 409，启停只改当前节点、不级联；
 *  - 前端按会话有效权限控制请求与按钮显隐（后端仍独立强制 AOP 鉴权）。
 */
import { computed, ref, watch } from 'vue'
import { ElButton, ElDialog, ElTag } from 'element-plus'
import { api } from '../../api/index.js'
import { createUserApi } from '../../api/user.js'
import { useSessionStore } from '../../stores/session.js'
import { resolvePagePermissionState } from '../../utils/menuPermission.js'
import { findDepartment, flattenDepartments, isValidId, parentOptions, sortDepartmentTree } from '../../utils/organization.js'
import { countCodePoints, syncClampedInput, trimOnBlur } from '../../utils/text.js'

const session = useSessionStore()
const userApi = createUserApi(api)

const hasPermissionSnapshot = ref(session.permissionStatus === 'loaded')
watch([() => session.accessToken, () => session.permissionStatus], ([token, status], previous) => {
  if (!token || (previous && token !== previous[0])) {
    hasPermissionSnapshot.value = status === 'loaded'
  } else if (status === 'loaded') {
    hasPermissionSnapshot.value = true
  } else if (status === 'unavailable') {
    hasPermissionSnapshot.value = false
  }
})
const pagePermissionState = computed(() => resolvePagePermissionState({
  permissionStatus: session.permissionStatus,
  hasSnapshot: hasPermissionSnapshot.value,
}))
const permissionsReady = computed(() => pagePermissionState.value === 'ready')
const permissionsLoading = computed(() => pagePermissionState.value === 'loading')
const permissionsUnavailable = computed(() => pagePermissionState.value === 'unavailable')
const can = (code) => permissionsReady.value && session.hasPermission(code)
const canListDepartments = computed(() => can('department:tree'))
const canCreateDepartment = computed(() => can('department:create'))
const canUpdateDepartment = computed(() => can('department:update'))
const canSetDepartmentStatus = computed(() => can('department:status'))
const canDeleteDepartment = computed(() => can('department:delete'))
const tree = ref([])
// 上级候选必须基于完整树：表格被 statusFilter 筛选后，启用分支可能被隐藏，
// 导致编辑停用部门时无法移动到合法启用分支。两棵树职责分离。
const fullTree = ref([])
const loading = ref(false)
const loadFailed = ref(false)
const statusFilter = ref('')
const submitting = ref(false)
const editor = ref(null)
const pendingDelete = ref(null)
const formError = ref('')

const rows = computed(() => flattenDepartments(sortDepartmentTree(tree.value)))
/**
 * 是否存在子部门必须基于完整树判断。表格树受 statusFilter 筛选，
 * 筛选后子部门可能被隐藏，若用 row.node.children 判断会误放行「有子部门却可点删除」，
 * 用户在确认后才拿到后端 409。
 */
function hasChildren(node) {
  const full = findDepartment(fullTree.value, node.id)
  return Boolean(full?.children?.length)
}
const candidates = computed(() => parentOptions(fullTree.value, {
  editingId: editor.value?.mode === 'edit' ? editor.value.node.id : null,
  keepParentId: editor.value?.mode === 'edit' ? editor.value.node.parentId ?? null : null,
}))

let treeRequestId = 0

async function retryPermissions() {
  await session.refreshPermissions()
}

async function loadTree() {
  const requestId = ++treeRequestId
  if (!canListDepartments.value) {
    tree.value = []
    fullTree.value = []
    loadFailed.value = false
    loading.value = false
    return
  }
  loading.value = true
  loadFailed.value = false
  try {
    if (statusFilter.value === '') {
      // 默认「全部状态」下筛选树与完整树完全相同，只发一次请求即可复用。
      try {
        const full = await userApi.getDepartmentTree()
        if (requestId !== treeRequestId) return
        tree.value = full ?? []
        fullTree.value = full ?? []
      } catch {
        if (requestId !== treeRequestId) return
        tree.value = []
        fullTree.value = []
        loadFailed.value = true
      }
      return
    }
    // 筛选后的树用于表格，完整树用于上级候选。两个请求相互独立：
    // 用 allSettled 分开兜底，一侧失败不清空另一侧已成功的数据（DeepSeek 审查 #8）。
    const [filteredResult, fullResult] = await Promise.allSettled([
      userApi.getDepartmentTree({ status: Number(statusFilter.value) }),
      userApi.getDepartmentTree(),
    ])
    if (requestId !== treeRequestId) return
    const filtered = filteredResult.status === 'fulfilled' ? filteredResult.value : null
    const full = fullResult.status === 'fulfilled' ? fullResult.value : null
    tree.value = filtered ?? []
    fullTree.value = full ?? []
    if (filtered === null || full === null) {
      // api/client.js 已统一弹出后端错误；内联只给出可重试的中性状态，不重复弹窗。
      loadFailed.value = true
    }
  } finally {
    // 无论成功、失败还是响应过期，只有最新一次请求负责复位 loading，
    // 避免并发切换筛选时旧请求提前 return 导致状态残留（DeepSeek 审查 #6）。
    if (requestId === treeRequestId) loading.value = false
  }
}

function openCreate(parentId = '') {
  if (!canCreateDepartment.value || submitting.value) return
  formError.value = ''
  editor.value = {
    mode: 'create',
    form: { deptCode: '', deptName: '', parentId, sortOrder: 0 },
  }
}

function openEdit(node) {
  if (!canUpdateDepartment.value || submitting.value) return
  formError.value = ''
  editor.value = {
    mode: 'edit',
    node,
    form: {
      deptCode: node.deptCode,
      deptName: node.deptName,
      parentId: node.parentId ?? '',
      sortOrder: node.sortOrder ?? 0,
    },
  }
}

function closeEditor() {
  if (submitting.value) return
  editor.value = null
  formError.value = ''
}

function validateForm() {
  const { form, mode, node } = editor.value
  // 长度按 Unicode 代码点计数，与 DepartmentJson.text() 同口径（老师复审 P2）：
  // 100 个「𠮷」名称的 String.length()=200，按 UTF-16 判定会被误拒。
  if (mode === 'create' && (!form.deptCode || countCodePoints(form.deptCode) > 64)) return '部门编码必填且不超过 64 字'
  // 名称先做与后端一致的纯空白拒绝（trim 含 U+3000 等全角空白），再按代码点查长度。
  if (!form.deptName.trim() || countCodePoints(form.deptName) > 100) return '部门名称必填且不超过 100 字'
  const order = Number(form.sortOrder)
  if (!Number.isInteger(order) || order < 0 || order > 2147483647) return '排序值需为 0～2147483647 的整数'
  // parentId 统一按字符串校验：select 候选与服务端返回均为字符串，防数字类型绕判。
  const parentId = form.parentId ? String(form.parentId) : ''
  if (parentId && !isValidId(parentId)) return '上级部门 ID 非法'
  // 完整 PUT 切换父节点时目标与全部祖先必须启用；原父不变可保留停用引用（keepParentId）。
  // 候选来自完整树，不受表格 statusFilter 影响；ID 统一按字符串比较，避免类型不一致误拒。
  // 保持原父引用不变时跳过候选校验：完整树偶发加载失败不应拦下合法的
  // 「只改名称/排序」编辑（DeepSeek 审查 #7）；只有切换父节点才必须校验候选。
  const keepParentId = mode === 'edit' ? String(node?.parentId ?? '') : ''
  if (parentId && parentId !== keepParentId && !candidates.value.some((option) => String(option.id) === parentId)) {
    return '上级部门不可为自身、后代或停用分支'
  }
  return ''
}

async function save() {
  // 首尾空白留到提交时统一处理（老师复审 P2：@input 逐键 trim 会吃掉名称中的空格）。
  // 先归一化再校验，长度按 trim 后的真实提交值计算。
  if (editor.value?.form) {
    editor.value.form.deptCode = String(editor.value.form.deptCode ?? '').trim()
    editor.value.form.deptName = String(editor.value.form.deptName ?? '').trim()
  }
  const message = validateForm()
  if (message) {
    formError.value = message
    return
  }
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  const { form, mode, node } = editor.value
  const parentId = form.parentId || null
  try {
    if (mode === 'create') {
      await userApi.createDepartment({
        parentId,
        deptCode: form.deptCode,
        deptName: form.deptName,
        sortOrder: Number(form.sortOrder),
      }, { silent: true })
    } else {
      // 完整 PUT：即使未改动，三个字段也必须全部提交，不能靠省略保留旧值。
      await userApi.updateDepartment(node.id, {
        parentId,
        deptName: form.deptName,
        sortOrder: Number(form.sortOrder),
      }, { silent: true })
    }
    editor.value = null
    await loadTree()
  } catch (error) {
    formError.value = error?.message || '保存失败，请按提示检查后重试'
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(node) {
  if (!canSetDepartmentStatus.value || submitting.value) return
  // 列表行可能来自筛选前的快照；提交前按 ID 从最新完整树回读真实状态，
  // 避免基于过期 status 反向切换（DeepSeek 审查 #7）。
  const current = findDepartment(fullTree.value, node.id) ?? findDepartment(tree.value, node.id) ?? node
  // 服务端 status 恒为数字 0/1；防御性归一化，字符串类型不静默失效。
  const status = Number(current.status)
  if (status !== 0 && status !== 1) return
  submitting.value = true
  try {
    await userApi.setDepartmentStatus(current.id, status === 1 ? 0 : 1)
    await loadTree()
  } catch {
    // 公共层已提示 404 / 409 / 500，此处不吞异常也不重复弹窗。
  } finally {
    submitting.value = false
  }
}

function askDelete(node) {
  if (!canDeleteDepartment.value || submitting.value || pendingDelete.value) return
  pendingDelete.value = node
}

async function confirmDelete() {
  if (submitting.value || !pendingDelete.value) return
  submitting.value = true
  try {
    await userApi.deleteDepartment(pendingDelete.value.id)
    pendingDelete.value = null
    await loadTree()
  } catch {
    // 后端对子部门 / 员工引用（含停用员工）返回 409，由公共层展示具体原因。
  } finally {
    submitting.value = false
  }
}

watch(canListDepartments, () => {
  void loadTree()
}, { immediate: true })
</script>

<template>
  <section class="cms-page org-page">
    <header class="cms-page-header">
      <div class="page-heading">
        <p class="eyebrow">ORGANIZATION · DEPARTMENTS</p>
        <h1>部门管理</h1>
        <p>维护部门层级、编码与排序；启停只影响当前节点，删除空部门需无子部门与员工引用。</p>
      </div>
      <div class="cms-page-header-actions">
        <ElButton :disabled="loading || !canListDepartments" @click="loadTree">刷新</ElButton>
        <ElButton v-if="canCreateDepartment" type="primary" :disabled="loading" @click="openCreate()">新建根部门</ElButton>
      </div>
    </header>

    <p v-if="permissionsLoading" class="org-alert" role="status">正在加载当前账号权限…</p>
    <p v-else-if="permissionsUnavailable" class="org-alert" role="alert">
      权限列表加载失败，无法确认部门管理权限；
      <ElButton link type="primary" @click="retryPermissions">点击重试</ElButton>
    </p>
    <p v-else-if="!canListDepartments" class="org-alert" role="alert">当前账号缺少 department:tree 权限，无法查看部门树。</p>

    <div v-if="canListDepartments" class="org-toolbar">
      <label class="org-inline-field">
        <span>状态</span>
        <select v-model="statusFilter" @change="loadTree">
          <option value="">全部状态</option>
          <option value="1">仅启用</option>
          <option value="0">仅停用</option>
        </select>
      </label>
      <span class="org-toolbar-hint">树各层按排序值、ID 升序；筛选保留必要祖先的真实状态</span>
    </div>

    <p v-if="loadFailed" class="org-alert" role="alert">部门树未加载完成，请确认服务与登录态后重试。</p>

    <div v-if="canListDepartments" class="org-card">
      <div class="cms-card-meta">
        <span>共 {{ rows.length }} 个部门节点</span>
        <span>层级按排序值与 ID 升序排列</span>
      </div>
      <div class="org-table-scroll">
        <table class="org-table">
        <thead>
          <tr><th>部门</th><th>编码</th><th>排序</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.node.id">
            <td>
              <span class="org-tree-label" :style="{ paddingLeft: `${row.depth * 18}px` }">
                <span v-if="row.depth" class="org-tree-branch" aria-hidden="true">└</span>
                {{ row.node.deptName }}
              </span>
            </td>
            <td class="org-mono">{{ row.node.deptCode }}</td>
            <td class="org-mono">{{ row.node.sortOrder }}</td>
            <td>
              <ElTag :type="row.node.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                {{ row.node.status === 1 ? '启用' : '停用' }}
              </ElTag>
            </td>
            <td>
              <div class="org-actions">
                <button v-if="canCreateDepartment" type="button" :disabled="submitting" @click="openCreate(row.node.id)">新增子部门</button>
                <button v-if="canUpdateDepartment" type="button" :disabled="submitting" @click="openEdit(row.node)">编辑</button>
                <button v-if="canSetDepartmentStatus" type="button" :disabled="submitting" @click="toggleStatus(row.node)">
                  {{ row.node.status === 1 ? '停用' : '启用' }}
                </button>
                <button
                  v-if="canDeleteDepartment"
                  type="button"
                  class="is-danger"
                  :disabled="submitting || hasChildren(row.node)"
                  :title="hasChildren(row.node) ? '存在子部门，不能删除' : '删除空部门'"
                  @click="askDelete(row.node)"
                >删除</button>
                <span
                  v-if="!canCreateDepartment && !canUpdateDepartment && !canSetDepartmentStatus && !canDeleteDepartment"
                  class="org-muted"
                >无权限</span>
              </div>
            </td>
          </tr>
          <tr v-if="!rows.length && !loading">
            <td colspan="5" class="org-empty">暂无部门数据，点击「新建根部门」开始搭建组织结构。</td>
          </tr>
        </tbody>
      </table>
      </div>
      <p v-if="loading" class="org-loading">正在加载部门树…</p>
    </div>

    <ElDialog
      v-if="editor"
      :model-value="true"
      :title="editor.mode === 'create' ? '新建部门' : '编辑部门'"
      width="min(480px, calc(100vw - 32px))"
      :show-close="!submitting" :close-on-click-modal="false" :close-on-press-escape="!submitting"
      @update:model-value="(open) => { if (!open) closeEditor() }"
    >
      <label class="org-field">
        <span>部门编码 <em>*</em></span>
        <!-- syncClampedInput 按代码点截断并同步回 DOM：与 DepartmentJson.text()
             的字符语义一致，且显示值与提交值始终一致（老师复审 #1/#2）。 -->
        <input :value="editor.form.deptCode" @input="editor.form.deptCode = syncClampedInput($event, 64)" @blur="editor.form.deptCode = trimOnBlur($event)" :disabled="editor.mode === 'edit'" placeholder="如 DEPT_SALES" />
        <small v-if="editor.mode === 'edit'">编码创建后不可修改；更新请求不提交该字段。</small>
      </label>
      <label class="org-field">
        <span>部门名称 <em>*</em></span>
        <input :value="editor.form.deptName" @input="editor.form.deptName = syncClampedInput($event, 100)" @blur="editor.form.deptName = trimOnBlur($event)" placeholder="如 销售部" />
      </label>
      <label class="org-field">
        <span>上级部门</span>
        <select v-model="editor.form.parentId">
          <option value="">根部门（无上级）</option>
          <option v-for="option in candidates" :key="option.id" :value="option.id">{{ option.label }}</option>
        </select>
        <small>不可选择自身或后代；切换上级时目标及所有祖先必须启用。原上级不变时可保留停用引用。</small>
      </label>
      <label class="org-field">
        <span>排序值 <em>*</em></span>
        <input v-model="editor.form.sortOrder" type="number" min="0" max="2147483647" step="1" />
      </label>
      <p v-if="formError" class="org-form-error" role="alert">{{ formError }}</p>
      <template #footer>
        <ElButton :disabled="submitting" @click="closeEditor">取消</ElButton>
        <ElButton type="primary" :loading="submitting" :disabled="submitting" @click="save">{{ submitting ? '保存中…' : '保存' }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-if="pendingDelete"
      :model-value="true"
      title="删除部门" width="min(430px, calc(100vw - 32px))"
      :show-close="!submitting" :close-on-click-modal="false" :close-on-press-escape="!submitting"
      @update:model-value="(open) => { if (!open && !submitting) pendingDelete = null }"
    >
      <p>确认删除「{{ pendingDelete.deptName }}」（{{ pendingDelete.deptCode }}）？</p>
      <p class="org-note">该部门下若仍有子部门或任何员工引用（含停用员工），服务端会返回 409 并保留数据。</p>
      <template #footer>
        <ElButton :disabled="submitting" @click="pendingDelete = null">取消</ElButton>
        <ElButton type="primary" :loading="submitting" :disabled="submitting" @click="confirmDelete">确认删除</ElButton>
      </template>
    </ElDialog>
  </section>
</template>
