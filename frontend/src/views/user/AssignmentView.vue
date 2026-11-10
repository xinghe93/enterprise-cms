<script setup>
/**
 * 员工部门 / 角色分配（功能② 部门与角色分配 · 分配部分）。
 *
 * 契约要点：《接口与协作约定 V1.0》3.2 / 5 / 7 节
 *  - 员工分页 departmentId 含本部门及全部后代，status 省略为全部，keyword 字面包含；
 *  - 修改员工是完整 PUT，realName 与 departmentId 都必填，null 表示清除部门；
 *  - 角色分配是全量替换，最多 100 项、不重复、空列表清空；每个提交项都必须启用，
 *    已有的停用项应在下一次替换时移除，管理回显的 roles 含停用项但不能当作有效角色；
 *  - 新选部门时目标及祖先必须启用，原引用不变可保留停用引用。
 * 员工账号的新建 / 编辑 / 启停属于功能① 的账号管理页面，不在本页重复实现。
 * 权限由后端 AOP 校验，界面不做权限隐藏。
 */
import { computed, onMounted, ref } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { api } from '../../api/index.js'
import { createUserApi } from '../../api/user.js'
import { createRoleApi } from '../../api/role.js'
import { flattenDepartments, isValidId, parentOptions, sortDepartmentTree } from '../../utils/organization.js'

const userApi = createUserApi(api)
const roleApi = createRoleApi(api)
const departments = ref([])
const assignableRoles = ref([])
const rolesLoaded = ref(false)
const users = ref([])
const total = ref(0)
const filters = ref({ keyword: '', departmentId: '', status: '', current: 1, size: 10 })
const loading = ref(false)
const loadFailed = ref(false)
const submitting = ref(false)
const dialog = ref(null)
const selectedRoleIds = ref([])
const removedRoles = ref([])
const formError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / filters.value.size)))
const departmentFilterOptions = computed(() => flattenDepartments(sortDepartmentTree(departments.value)))
// parentOptions 复用「启用链 + 保留原停用引用」规则，正好对应分配部门时的可选范围。
const assignmentOptions = computed(() => parentOptions(departments.value, {
  keepParentId: dialog.value?.user?.departmentId ?? null,
}))

async function loadDepartments() {
  try {
    departments.value = await userApi.getDepartmentTree()
  } catch {
    departments.value = []
  }
}

async function ensureRoles() {
  if (rolesLoaded.value) return
  try {
    const page = await roleApi.listAssignableRoles()
    assignableRoles.value = page?.records ?? []
    rolesLoaded.value = true
  } catch {
    assignableRoles.value = []
  }
}

async function loadUsers() {
  if (loading.value) return
  loading.value = true
  loadFailed.value = false
  try {
    const page = await userApi.listUsers({
      keyword: filters.value.keyword,
      departmentId: filters.value.departmentId,
      status: filters.value.status === '' ? '' : Number(filters.value.status),
      current: filters.value.current,
      size: filters.value.size,
    })
    users.value = page?.records ?? []
    total.value = Number(page?.total ?? 0)
    filters.value.current = Number(page?.current ?? filters.value.current)
  } catch {
    loadFailed.value = true
    users.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  filters.value.current = 1
  void loadUsers()
}

function changePage(delta) {
  const next = filters.value.current + delta
  if (next < 1 || next > totalPages.value) return
  filters.value.current = next
  void loadUsers()
}

function changeSize() {
  filters.value.current = 1
  void loadUsers()
}

function closeDialog() {
  if (submitting.value) return
  dialog.value = null
  formError.value = ''
}

function openDepartmentDialog(user) {
  formError.value = ''
  dialog.value = { type: 'department', user, form: { departmentId: user.departmentId ?? '' } }
}

async function openRoleDialog(user) {
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    const detail = await userApi.getUser(user.id)
    await ensureRoles()
    const available = new Set(assignableRoles.value.map((role) => role.id))
    const assigned = detail?.roles ?? []
    // 只把「启用且在候选中」的角色作为可保留项；停用项在下一次全量替换时被移除。
    selectedRoleIds.value = assigned.filter((role) => available.has(role.id)).map((role) => role.id)
    removedRoles.value = assigned.filter((role) => !available.has(role.id))
    dialog.value = { type: 'role', user }
  } catch {
    // 公共层已提示 404 / 500，保持弹窗关闭状态。
  } finally {
    submitting.value = false
  }
}

function toggleRole(id) {
  const index = selectedRoleIds.value.indexOf(id)
  if (index >= 0) selectedRoleIds.value.splice(index, 1)
  else selectedRoleIds.value.push(id)
}

async function saveDepartment() {
  const { user, form } = dialog.value
  const departmentId = form.departmentId
  if (departmentId && !isValidId(departmentId)) {
    formError.value = '部门 ID 非法，请重新选择'
    return
  }
  if (departmentId && !assignmentOptions.value.some((option) => option.id === departmentId)) {
    formError.value = '新选部门及其所有祖先必须启用；原部门不变时可保留'
    return
  }
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    // 完整 PUT：realName 必须一起提交，不能省略或用空值覆盖。
    await userApi.updateUser(user.id, { realName: user.realName, departmentId: departmentId || null })
    dialog.value = null
    await loadUsers()
  } catch (error) {
    formError.value = error?.message || '保存失败，请按提示检查后重试'
  } finally {
    submitting.value = false
  }
}

async function saveRoles() {
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    // 全量替换：空数组即清空，api 层负责去重与 100 项上限校验。
    await userApi.setUserRoles(dialog.value.user.id, selectedRoleIds.value)
    dialog.value = null
    await loadUsers()
  } catch (error) {
    formError.value = error?.message || '保存失败，请按提示检查后重试'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadDepartments()
  void loadUsers()
})
</script>

<template>
  <section class="org-page">
    <div class="page-heading">
      <p class="eyebrow">ORGANIZATION / A</p>
      <h1>部门与角色分配</h1>
      <p>按部门（含全部后代）筛选员工，调整所属部门或全量替换已分配角色；停用角色在保存后移除。</p>
    </div>

    <div class="org-toolbar org-toolbar-wrap">
      <label class="org-inline-field">
        <span>关键字</span>
        <input v-model.trim="filters.keyword" maxlength="100" placeholder="账号或姓名" @keyup.enter="search" />
      </label>
      <label class="org-inline-field">
        <span>部门（含后代）</span>
        <select v-model="filters.departmentId" @change="search">
          <option value="">全部部门</option>
          <option v-for="row in departmentFilterOptions" :key="row.node.id" :value="row.node.id">
            {{ '　'.repeat(row.depth) }}{{ row.node.deptName }}{{ row.node.status === 0 ? '（停用）' : '' }}
          </option>
        </select>
      </label>
      <label class="org-inline-field">
        <span>状态</span>
        <select v-model="filters.status" @change="search">
          <option value="">全部状态</option>
          <option value="1">仅启用</option>
          <option value="0">仅停用</option>
        </select>
      </label>
      <ElButton type="primary" :disabled="loading" @click="search">查询</ElButton>
      <ElButton :disabled="loading" @click="loadUsers">刷新</ElButton>
    </div>

    <p v-if="loadFailed" class="org-alert" role="alert">员工列表未加载，请确认服务与登录态后重试。</p>

    <div class="org-card">
      <table class="org-table">
        <thead>
          <tr><th>账号</th><th>姓名</th><th>部门</th><th>状态</th><th>已分配角色（含停用）</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td class="org-mono">{{ user.username }}</td>
            <td>{{ user.realName }}</td>
            <td>{{ user.departmentName || '—' }}</td>
            <td>
              <ElTag :type="user.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                {{ user.status === 1 ? '启用' : '停用' }}
              </ElTag>
            </td>
            <td>
              <div class="org-tags">
                <ElTag v-for="role in user.roles" :key="role.id" :type="role.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ role.roleName }}{{ role.status === 0 ? '（停用）' : '' }}
                </ElTag>
                <span v-if="!user.roles || !user.roles.length" class="org-muted">未分配</span>
              </div>
            </td>
            <td>
              <div class="org-actions">
                <button type="button" :disabled="submitting" @click="openDepartmentDialog(user)">分配部门</button>
                <button type="button" :disabled="submitting" @click="openRoleDialog(user)">分配角色</button>
              </div>
            </td>
          </tr>
          <tr v-if="!users.length && !loading">
            <td colspan="6" class="org-empty">没有符合条件的员工，请调整筛选条件。</td>
          </tr>
        </tbody>
      </table>
      <p v-if="loading" class="org-loading">正在加载员工列表…</p>
      <div class="org-pager">
        <span>共 {{ total }} 条 · 第 {{ filters.current }} / {{ totalPages }} 页</span>
        <label class="org-inline-field">
          <span>每页</span>
          <select v-model.number="filters.size" @change="changeSize">
            <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option>
          </select>
        </label>
        <button type="button" :disabled="loading || filters.current <= 1" @click="changePage(-1)">上一页</button>
        <button type="button" :disabled="loading || filters.current >= totalPages" @click="changePage(1)">下一页</button>
      </div>
    </div>

    <div v-if="dialog" class="org-overlay" @click.self="closeDialog">
      <section class="org-dialog" role="dialog" aria-modal="true" :aria-labelledby="`assign-title-${dialog.type}`">
        <header class="org-dialog-head">
          <h2 :id="`assign-title-${dialog.type}`">
            {{ dialog.type === 'department' ? '分配部门' : '分配角色' }} · {{ dialog.user.realName }}（{{ dialog.user.username }}）
          </h2>
          <button type="button" class="org-icon-btn" aria-label="关闭弹窗" @click="closeDialog">×</button>
        </header>

        <div v-if="dialog.type === 'department'" class="org-dialog-body">
          <label class="org-field">
            <span>所属部门</span>
            <select v-model="dialog.form.departmentId">
              <option value="">无部门（清除原部门）</option>
              <option v-for="option in assignmentOptions" :key="option.id" :value="option.id">{{ option.label }}</option>
            </select>
            <small>新选部门及所有祖先必须启用；保持原部门不变时可保留停用引用。</small>
          </label>
          <p class="org-note">修改员工是完整更新，提交时会同时带上当前姓名「{{ dialog.user.realName }}」。</p>
          <p v-if="formError" class="org-form-error" role="alert">{{ formError }}</p>
        </div>

        <div v-else class="org-dialog-body">
          <p class="org-note">角色为全量替换：取消勾选即移除，最多 100 项；保存后重新加载列表以刷新回显。</p>
          <div v-if="removedRoles.length" class="org-alert" role="status">
            以下已分配角色当前不可分配（停用或不存在），保存后将被移除：
            {{ removedRoles.map((role) => role.roleName).join('、') }}
          </div>
          <div v-if="!assignableRoles.length" class="org-empty">暂无可分配的启用角色。</div>
          <div v-else class="org-check-list">
            <label v-for="role in assignableRoles" :key="role.id" class="org-check-item">
              <input type="checkbox" :checked="selectedRoleIds.includes(role.id)" @change="toggleRole(role.id)" />
              <span>{{ role.roleName }}</span><span class="org-mono">{{ role.roleCode }}</span>
            </label>
          </div>
          <p v-if="formError" class="org-form-error" role="alert">{{ formError }}</p>
        </div>

        <footer class="org-dialog-foot">
          <ElButton :disabled="submitting" @click="closeDialog">取消</ElButton>
          <ElButton type="primary" :disabled="submitting" @click="dialog.type === 'department' ? saveDepartment() : saveRoles()">
            {{ submitting ? '保存中…' : '保存' }}
          </ElButton>
        </footer>
      </section>
    </div>
  </section>
</template>
