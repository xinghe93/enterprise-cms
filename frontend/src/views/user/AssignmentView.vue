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
import { useRoute } from 'vue-router'
import { ElButton, ElDialog, ElTag } from 'element-plus'
// 弹窗样式不能依赖其他页面的懒加载组件顺带加载（老师审查 #4）。
import 'element-plus/es/components/dialog/style/css.mjs'
import { api } from '../../api/index.js'
import { createUserApi } from '../../api/user.js'
import { createRoleApi } from '../../api/role.js'
import { flattenDepartments, isValidId, normalizeRoleIds, parentOptions, partitionAssignedRoles, sortDepartmentTree } from '../../utils/organization.js'

const route = useRoute()

const userApi = createUserApi(api)
const roleApi = createRoleApi(api)
const departments = ref([])
const assignableRoles = ref([])
const rolesLoadFailed = ref(false)
const users = ref([])
const total = ref(0)
const filters = ref({ keyword: '', departmentId: '', status: '', current: 1, size: 10 })
const loading = ref(false)
const loadFailed = ref(false)
const submitting = ref(false)
const dialog = ref(null)
const selectedRoleIds = ref([])
const removedRoles = ref([])
// 只有「员工详情 + 启用角色目录」两项请求都成功、勾选状态有效时才允许保存；
// 否则全量替换会以空勾选提交 roleIds: []，静默清空员工已有角色（老师审查 #2）。
const rolesReady = ref(false)
// 目录与详情分别记录失败：任一失败都要保留弹窗内的重试入口（老师审查 #2）。
// 只看 rolesLoadFailed 时，目录重试成功但详情失败会同时丢掉重试按钮，只能关弹窗重开。
const roleDetailFailed = ref(false)
const formError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / filters.value.size)))
const departmentFilterOptions = computed(() => flattenDepartments(sortDepartmentTree(departments.value)))
// parentOptions 复用「启用链 + 保留原停用引用」规则，正好对应分配部门时的可选范围。
const assignmentOptions = computed(() => parentOptions(departments.value, {
  keepParentId: dialog.value?.user?.departmentId ?? null,
}))

let usersRequestId = 0

async function loadDepartments() {
  try {
    departments.value = await userApi.getDepartmentTree()
  } catch {
    departments.value = []
  }
}

/**
 * 每次打开角色弹窗都重新拉取完整启用角色目录：
 *  - 候选成功加载一次后一直复用，会漏掉其他管理员新增的角色，保存时把详情里的新关联误删；
 *  - 加载失败必须显式标记，由调用方禁用保存并允许重试，不能用空候选继续执行全量替换。
 */
async function ensureRoles() {
  rolesLoadFailed.value = false
  try {
    const page = await roleApi.listAssignableRoles()
    assignableRoles.value = page?.records ?? []
    return true
  } catch {
    assignableRoles.value = []
    rolesLoadFailed.value = true
    return false
  }
}

async function loadUsers() {
  const requestId = ++usersRequestId
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
    // 只接收与当前筛选条件对应的最新响应，防止旧请求后到覆盖新条件的结果。
    if (requestId !== usersRequestId) return
    users.value = page?.records ?? []
    total.value = Number(page?.total ?? 0)
    filters.value.current = Number(page?.current ?? filters.value.current)
  } catch {
    if (requestId !== usersRequestId) return
    loadFailed.value = true
    users.value = []
    total.value = 0
  } finally {
    if (requestId === usersRequestId) loading.value = false
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
  rolesReady.value = false
  roleDetailFailed.value = false
}

async function openDepartmentDialog(user) {
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    // 列表可能是他人修改姓名之前加载的快照；完整 PUT 会回写 realName，
    // 因此打开弹窗时重新读取详情，避免把他人已提交的姓名覆盖成旧值。
    const detail = await userApi.getUser(user.id)
    // 详情是权威来源：departmentId 为 null 表示该员工当前已无部门，
    // 不能回退到列表旧值，否则保存会把已清除的部门重新挂回（老师审查 #3）。
    const detailDepartmentId = detail?.departmentId ?? null
    dialog.value = {
      type: 'department',
      user: {
        ...user,
        id: detail?.id ?? user.id,
        realName: detail?.realName ?? user.realName,
        departmentId: detailDepartmentId,
      },
      // select 以空串表示「无部门」，保存时统一转 null 提交。
      form: { departmentId: detailDepartmentId ?? '' },
    }
  } catch {
    // 公共层已提示 404 / 500，保持弹窗关闭。
  } finally {
    submitting.value = false
  }
}

async function openRoleDialog(user) {
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  roleDetailFailed.value = false
  try {
    const detail = await userApi.getUser(user.id)
    const ok = await ensureRoles()
    dialog.value = { type: 'role', user }
    if (!ok) {
      // 目录失败时勾选无效：清空待移除提示（目录没拿到，无法判断哪些会被移除），
      // 保持禁止保存并引导重试，而不是带着空勾选放行保存。
      selectedRoleIds.value = []
      removedRoles.value = []
      rolesReady.value = false
      formError.value = '启用角色目录加载失败，为避免误删现有角色已禁止保存，请重试'
      return
    }
    applyRoleSelection(detail)
  } catch {
    // 公共层已提示 404 / 500，保持弹窗关闭状态。
  } finally {
    submitting.value = false
  }
}

/** 两项请求都成功后按完整目录重建勾选；只有走到这里才解锁保存。 */
function applyRoleSelection(detail) {
  const { selectedRoleIds: selected, removedRoles: removed } = partitionAssignedRoles(detail?.roles, assignableRoles.value)
  selectedRoleIds.value = selected
  removedRoles.value = removed
  rolesReady.value = true
  roleDetailFailed.value = false
}

/** 候选目录加载失败后的重试：目录与详情都重新成功才解锁保存并重建勾选。 */
async function retryRoles() {
  if (submitting.value || !dialog.value || dialog.value.type !== 'role') return
  submitting.value = true
  formError.value = ''
  try {
    const ok = await ensureRoles()
    if (!ok) {
      rolesReady.value = false
      formError.value = '启用角色目录仍加载失败，请稍后重试'
      return
    }
    // 详情失败时不能解锁保存：rolesReady 保持 false，勾选维持上一轮（无效）状态。
    const detail = await userApi.getUser(dialog.value.user.id)
    applyRoleSelection(detail)
  } catch {
    rolesReady.value = false
    // 详情失败也要留下重试入口：此时 ensureRoles 已把 rolesLoadFailed 清空，
    // 若不再单独标记，保存禁用且重试按钮消失，只能关闭弹窗重开（老师审查 #2）。
    roleDetailFailed.value = true
    formError.value = '重试失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}

function toggleRole(id) {
  if (!rolesReady.value) return
  const key = String(id)
  const index = selectedRoleIds.value.indexOf(key)
  if (index >= 0) selectedRoleIds.value.splice(index, 1)
  else selectedRoleIds.value.push(key)
}

async function saveDepartment() {
  // 保存按钮与弹窗由 v-if 控制，但 closeDialog 可在异步回调里先执行；防御性判空避免解构崩溃。
  if (!dialog.value || dialog.value.type !== 'department') return
  const { user, form } = dialog.value
  const departmentId = form.departmentId
  if (departmentId && !isValidId(departmentId)) {
    formError.value = '部门 ID 非法，请重新选择'
    return
  }
  if (departmentId && !assignmentOptions.value.some((option) => String(option.id) === String(departmentId))) {
    formError.value = '新选部门及其所有祖先必须启用；原部门不变时可保留'
    return
  }
  if (submitting.value) return
  submitting.value = true
  formError.value = ''
  try {
    // 完整 PUT：realName 用刚读取的最新值；departmentId 契约要求十进制字符串。
    await userApi.updateUser(user.id, {
      realName: user.realName,
      departmentId: departmentId ? String(departmentId) : null,
    })
    dialog.value = null
    await loadUsers()
  } catch (error) {
    formError.value = error?.message || '保存失败，请按提示检查后重试'
  } finally {
    submitting.value = false
  }
}

async function saveRoles() {
  if (submitting.value || !rolesReady.value || !dialog.value || dialog.value.type !== 'role') return
  submitting.value = true
  formError.value = ''
  try {
    // 全量替换：空数组即清空；先在本地归一化，超限/非法 ID 不发请求。
    const roleIds = normalizeRoleIds(selectedRoleIds.value)
    await userApi.setUserRoles(dialog.value.user.id, roleIds)
    dialog.value = null
    await loadUsers()
  } catch (error) {
    formError.value = error?.message || '保存失败，请按提示检查后重试'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 员工页跳转带 ?keyword=<username>：首次请求前读取，避免目标员工不在第一页时
  // 管理员还要手动重新搜索（老师审查 #5）；长度与契约 keyword 上限一致。
  const keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  if (keyword) filters.value.keyword = keyword.slice(0, 100)
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

    <ElDialog
      v-if="dialog"
      :model-value="true"
      :title="`${dialog.type === 'department' ? '分配部门' : '分配角色'} · ${dialog.user.realName}（${dialog.user.username}）`"
      width="min(520px, calc(100vw - 32px))"
      :show-close="!submitting" :close-on-click-modal="false" :close-on-press-escape="!submitting"
      @update:model-value="(open) => { if (!open) closeDialog() }"
    >
      <div v-if="dialog?.type === 'department'">
        <label class="org-field">
          <span>所属部门</span>
          <select v-model="dialog.form.departmentId">
            <option value="">无部门（清除原部门）</option>
            <option v-for="option in assignmentOptions" :key="option.id" :value="option.id">{{ option.label }}</option>
          </select>
          <small>新选部门及所有祖先必须启用；保持原部门不变时可保留停用引用。</small>
        </label>
        <p class="org-note">修改员工是完整更新，提交时会同时带上刚读取的最新姓名「{{ dialog.user.realName }}」。</p>
        <p v-if="formError" class="org-form-error" role="alert">{{ formError }}</p>
      </div>

      <div v-else>
        <p class="org-note">角色为全量替换：取消勾选即移除，最多 100 项；保存后重新加载列表以刷新回显。</p>
        <div v-if="removedRoles.length" class="org-alert" role="status">
          以下已分配角色当前不可分配（停用或不存在），保存后将被移除：
          {{ removedRoles.map((role) => role.roleName).join('、') }}
        </div>
        <ElButton v-if="rolesLoadFailed || roleDetailFailed" link type="danger" :disabled="submitting" @click="retryRoles">角色信息加载失败，点击重试</ElButton>
        <div v-else-if="!assignableRoles.length" class="org-empty">暂无可分配的启用角色。</div>
        <div v-else class="org-check-list">
          <label v-for="role in assignableRoles" :key="role.id" class="org-check-item">
            <input
              type="checkbox" :disabled="!rolesReady"
              :checked="selectedRoleIds.includes(String(role.id))" @change="toggleRole(role.id)"
            />
            <span>{{ role.roleName }}</span><span class="org-mono">{{ role.roleCode }}</span>
          </label>
        </div>
        <p v-if="formError" class="org-form-error" role="alert">{{ formError }}</p>
      </div>

      <template #footer>
        <ElButton :disabled="submitting" @click="closeDialog">取消</ElButton>
        <ElButton
          type="primary" :loading="submitting"
          :disabled="submitting || (dialog?.type === 'role' && !rolesReady)"
          @click="dialog?.type === 'department' ? saveDepartment() : saveRoles()"
        >{{ submitting ? '保存中…' : '保存' }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>
