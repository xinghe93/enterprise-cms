<script setup>
/**
 * 员工账号管理（功能① 登录与账号管理 · 账号管理页面）。
 *
 * 本页补齐 AssignmentView 未覆盖的账号生命周期：新建、编辑（完整 PUT）、启停。
 * 部门与角色的分配仍在 AssignmentView，本页「分配角色/部门」按钮跳转过去。
 *
 * 契约要点：《接口与协作约定 V1.0》3.2 / 7 节
 *  - 创建字段 username(1-64) / password(8-128) / realName(1-100) / departmentId(可空 ID)；
 *  - 修改是完整 PUT，realName 与 departmentId 都必填，null 表示清除部门；打开编辑弹窗
 *    必须重新 getUser 取最新 realName / departmentId，避免用列表旧快照覆盖他人修改；
 *  - 修改员工不提交 username / password / status，也不改角色；
 *  - 启停只改当前用户状态（服务端提交后撤销其会话），status 显式为 0 或 1；
 *  - 部门候选只取启用链；编辑时保留原部门引用（可能已停用），符合「原引用不变可保留」。
 * 权限由后端 AOP 校验，界面不做权限隐藏。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElDialog, ElTag } from 'element-plus'
// 弹窗样式不能依赖其他页面的懒加载组件顺带加载（老师审查 #4）。
import 'element-plus/es/components/dialog/style/css.mjs'
import { api } from '../../api/index.js'
import { createUserApi } from '../../api/user.js'
import { flattenDepartments, isValidId, parentOptions, sortDepartmentTree, treeFailureFilterPatch, validateAccountForm } from '../../utils/organization.js'
import { syncClampedInput, trimOnBlur } from '../../utils/text.js'

const router = useRouter()
const userApi = createUserApi(api)

const users = ref([])
const total = ref(0)
const filters = ref({ keyword: '', departmentId: '', status: '', current: 1, size: 10 })
const loading = ref(false)
const loadFailed = ref(false)
const submitting = ref(false)
// 编辑详情加载与保存提交分开用锁：详情加载未完成时不是「保存中」，
// 复用 submitting 会让状态语义混淆（DeepSeek 审查）。
const loadingDetail = ref(false)
const dialog = ref(null)       // { mode:'create'|'edit', user?, form }
const pendingStatus = ref(null) // { user, next }
const formError = ref('')
const fullTree = ref([])
const treeLoaded = ref(false)
const treeFailed = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / filters.value.size)))
const departmentFilterOptions = computed(() => flattenDepartments(sortDepartmentTree(fullTree.value)))
// 可选部门：启用部门 + 编辑时保留原部门引用；与后端「新引用须启用、原引用可保留」一致。
const departmentOptions = computed(() => parentOptions(fullTree.value, {
  // 原部门以详情里的权威值为准，显式存到 dialog.retainedDepartmentId；
  // 不再从合并后的 user 快照推断，避免列表旧值与详情 null 混用（DeepSeek 审查 #1）。
  keepParentId: dialog.value?.mode === 'edit' ? (dialog.value.retainedDepartmentId ?? null) : null,
}))
const allowedDepartmentIds = computed(() => new Set(departmentOptions.value.map((option) => String(option.id))))
// 原部门不在候选中（树失败、停用或已不存在）时必须显式渲染「保留原部门」选项，
// 不能只在 treeFailed 时显示，否则下拉空白却仍提交该 ID（DeepSeek 审查 #4）。
const retainedDepartmentMissing = computed(() => {
  const id = dialog.value?.mode === 'edit' ? dialog.value.retainedDepartmentId : null
  return id !== null && id !== undefined && !allowedDepartmentIds.value.has(String(id))
})

let usersRequestId = 0

async function loadTree() {
  try {
    fullTree.value = await userApi.getDepartmentTree()
    treeFailed.value = false
  } catch {
    fullTree.value = []
    treeFailed.value = true
    // 老师复审 P2：树失败后部门下拉被禁用，若仍保留旧部门 ID，查询会一直带着
    // 已不可见的过滤条件且用户无法切回「全部部门」。目录被清空时重置筛选并
    // 重新查询（本来就是全部部门则不重复请求）。requestId 保证并发中以后到的
    // 重置查询为准，刷新触发的旧查询结果会被丢弃。
    const patch = treeFailureFilterPatch(filters.value)
    if (patch) {
      Object.assign(filters.value, patch)
      void loadUsers()
    }
  } finally {
    treeLoaded.value = true
  }
}

/** 刷新同时重试部门树与员工列表：树加载失败后必须给用户就地恢复的入口（老师审查 #3）。 */
function refresh() {
  void loadTree()
  void loadUsers()
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
  // 首尾空白只在提交查询时处理（老师复审 P2：@input 逐键 trim 会吃掉姓名中的空格）。
  filters.value.keyword = filters.value.keyword.trim()
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

function openCreate() {
  if (submitting.value || loadingDetail.value || pendingStatus.value) return
  formError.value = ''
  dialog.value = { mode: 'create', form: { username: '', password: '', realName: '', departmentId: '' } }
}

async function openEdit(user) {
  if (submitting.value || loadingDetail.value || pendingStatus.value) return
  loadingDetail.value = true
  formError.value = ''
  try {
    // 列表是旧快照；完整 PUT 会回写 realName / departmentId，先取最新详情避免覆盖。
    const detail = await userApi.getUser(user.id)
    const retainedDepartmentId = detail?.departmentId ?? null
    dialog.value = {
      mode: 'edit',
      user: { ...user, ...detail },
      // 详情是原部门的唯一权威来源：候选、下拉选项与校验都引用同一字段。
      retainedDepartmentId,
      form: {
        realName: detail?.realName ?? user.realName ?? '',
        departmentId: retainedDepartmentId ?? '',
      },
    }
  } catch {
    // 公共层已提示错误，保持弹窗关闭。
  } finally {
    loadingDetail.value = false
  }
}

function closeDialog() {
  if (submitting.value) return
  dialog.value = null
  formError.value = ''
}

async function save() {
  if (submitting.value || !dialog.value) return
  const { mode, form, user, retainedDepartmentId } = dialog.value
  // 编辑时详情里的原部门是权威值：树加载失败也要允许「只改姓名、保留原部门」保存。
  const message = validateAccountForm(mode, form, allowedDepartmentIds.value,
    mode === 'edit' ? (retainedDepartmentId ?? null) : null)
  if (message) {
    formError.value = message
    return
  }
  submitting.value = true
  formError.value = ''
  const departmentId = form.departmentId === '' || form.departmentId === null || form.departmentId === undefined
    ? null : String(form.departmentId)
  try {
    if (mode === 'create') {
      await userApi.createUser({
        username: form.username.trim(),
        password: form.password,
        realName: form.realName.trim(),
        departmentId,
      })
    } else {
      // 完整 PUT：只提交 realName 与 departmentId，不改 username / password / status / 角色。
      await userApi.updateUser(user.id, {
        realName: form.realName.trim(),
        departmentId,
      })
    }
    dialog.value = null
    await loadUsers()
  } catch (error) {
    formError.value = error?.message || '保存失败，请按提示检查后重试'
  } finally {
    submitting.value = false
  }
}

function askStatus(user) {
  // 确认弹窗打开期间不允许被其他行的启停覆盖，否则确认对象悄悄换成另一名员工
  // （DeepSeek 审查：启停确认弹窗可被并发覆盖）。
  if (submitting.value || pendingStatus.value || loadingDetail.value) return
  if (user.status !== 0 && user.status !== 1) return
  pendingStatus.value = { user, next: user.status === 1 ? 0 : 1 }
}

async function confirmStatus() {
  if (submitting.value || !pendingStatus.value) return
  submitting.value = true
  try {
    await userApi.setUserStatus(pendingStatus.value.user.id, pendingStatus.value.next)
    pendingStatus.value = null
    await loadUsers()
  } catch {
    // 公共层已提示 404 / 409 / 500，保持对话框允许重试。
  } finally {
    submitting.value = false
  }
}

function goAssign(user) {
  // 分配页通过查询参数带 username，便于定位具体员工；分配页负责读取与回显。
  void router.push({ name: 'assignments', query: user?.username ? { keyword: user.username } : {} })
}

onMounted(() => {
  void loadTree()
  void loadUsers()
})
</script>

<template>
  <section class="org-page">
    <div class="page-heading">
      <p class="eyebrow">ORGANIZATION / A</p>
      <h1>员工管理</h1>
      <p>维护员工账号的新建、编辑与启停；部门与角色分配请到「岗位分配」。</p>
    </div>

    <div class="org-toolbar">
      <label class="org-inline-field">
        <span>关键字</span>
        <!-- 原生 input 的 maxlength 按 UTF-16 代码单元截断，与后端代码点口径不一致；
             syncClampedInput 逐代码点截断并把结果同步回 DOM，显示值与提交值始终一致。 -->
        <input :value="filters.keyword" @input="filters.keyword = syncClampedInput($event, 100)" @blur="filters.keyword = trimOnBlur($event)" placeholder="登录账号 / 姓名" @keyup.enter="search" />
      </label>
      <label class="org-inline-field">
        <span>部门</span>
        <select v-model="filters.departmentId" :disabled="treeFailed" @change="search">
          <option value="">{{ treeFailed ? '部门树未加载' : '全部部门' }}</option>
          <option v-for="option in departmentFilterOptions" :key="option.node.id" :value="option.node.id">
            {{ '　'.repeat(option.depth) }}{{ option.node.deptName }}{{ option.node.status === 0 ? '（停用）' : '' }}
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
      <ElButton @click="search">查询</ElButton>
      <span class="org-toolbar-hint">部门筛选含本部门及全部后代</span>
      <ElButton type="primary" :disabled="loading || !treeLoaded || treeFailed" @click="openCreate">新建员工</ElButton>
      <ElButton :disabled="loading" @click="refresh">刷新</ElButton>
    </div>

    <p v-if="loadFailed" class="org-alert" role="alert">员工列表未加载，请确认服务与登录态后重试。</p>
    <p v-if="treeFailed" class="org-alert" role="alert">部门树未加载，已清除部门筛选并查询全部员工；点击「刷新」可重试。编辑时仍可保留员工详情返回的部门并只改姓名。</p>

    <div class="org-card">
      <table class="org-table">
        <thead>
          <tr><th>登录账号</th><th>姓名</th><th>所属部门</th><th>角色</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td class="org-mono">{{ user.username }}</td>
            <td>{{ user.realName }}</td>
            <td>{{ user.departmentName || '—' }}</td>
            <td>
              <span v-if="!user.roles || !user.roles.length" class="org-muted">无角色</span>
              <span v-else class="org-tags">
                <ElTag v-for="role in user.roles" :key="role.id" :type="role.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ role.roleName }}{{ role.status === 0 ? '（停用）' : '' }}
                </ElTag>
              </span>
            </td>
            <td>
              <ElTag :type="user.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                {{ user.status === 1 ? '启用' : '停用' }}
              </ElTag>
            </td>
            <td>
              <div class="org-actions">
                <button type="button" :disabled="submitting || loadingDetail" @click="openEdit(user)">编辑</button>
                <button type="button" :disabled="submitting || loadingDetail" @click="goAssign(user)">分配角色/部门</button>
                <button type="button" :disabled="submitting || loadingDetail || Boolean(pendingStatus)" @click="askStatus(user)">
                  {{ user.status === 1 ? '停用' : '启用' }}
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!users.length && !loading">
            <td colspan="6" class="org-empty">暂无员工数据，点击「新建员工」开始添加。</td>
          </tr>
        </tbody>
      </table>
      <p v-if="loading" class="org-loading">正在加载员工列表…</p>
      <div class="org-pager">
        <span>共 {{ total }} 条 · 第 {{ filters.current }} / {{ totalPages }} 页</span>
        <label class="org-inline-field">
          <span>每页</span>
          <select v-model.number="filters.size" @change="changeSize">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
          </select>
        </label>
        <button type="button" :disabled="loading || filters.current <= 1" @click="changePage(-1)">上一页</button>
        <button type="button" :disabled="loading || filters.current >= totalPages" @click="changePage(1)">下一页</button>
      </div>
    </div>

    <ElDialog
      v-if="dialog"
      :model-value="true"
      :title="dialog.mode === 'create' ? '新建员工' : `编辑员工 · ${dialog.user?.realName ?? ''}`"
      width="min(480px, calc(100vw - 32px))"
      :show-close="!submitting" :close-on-click-modal="false" :close-on-press-escape="!submitting"
      @update:model-value="(open) => { if (!open) closeDialog() }"
    >
      <label v-if="dialog.mode === 'create'" class="org-field">
        <span>登录账号 <em>*</em></span>
        <input :value="dialog.form.username" @input="dialog.form.username = syncClampedInput($event, 64)" @blur="dialog.form.username = trimOnBlur($event)" placeholder="如 zhangsan" />
        <small>1～64 字，登录唯一标识。</small>
      </label>
      <label v-if="dialog.mode === 'create'" class="org-field">
        <span>初始密码 <em>*</em></span>
        <input :value="dialog.form.password" type="password" @input="dialog.form.password = syncClampedInput($event, 128)" placeholder="至少 8 位" />
        <small>8～128 字。</small>
      </label>
      <label class="org-field">
        <span>姓名 <em>*</em></span>
        <input :value="dialog.form.realName" @input="dialog.form.realName = syncClampedInput($event, 100)" @blur="dialog.form.realName = trimOnBlur($event)" placeholder="如 张三" />
      </label>
      <label class="org-field">
        <span>所属部门</span>
        <select v-model="dialog.form.departmentId">
          <option value="">无部门（清除原部门）</option>
          <!-- 原部门不在候选中（树失败 / 停用 / 已不存在）时显式显示「保留原部门」，
               避免下拉框空白却仍提交该 ID，让用户知道保存会原样回写。 -->
          <option v-if="retainedDepartmentMissing"
            :value="dialog.retainedDepartmentId">保留原部门（ID: {{ dialog.retainedDepartmentId }}）</option>
          <option v-for="option in departmentOptions" :key="option.id" :value="option.id">{{ option.label }}</option>
        </select>
        <small>仅可选择启用部门；原部门停用时可保留不变。</small>
      </label>
      <p v-if="dialog.mode === 'edit'" class="org-note">修改只更新姓名与所属部门，不影响账号、密码、状态与角色。</p>
      <p v-if="formError" class="org-form-error" role="alert">{{ formError }}</p>
      <template #footer>
        <ElButton :disabled="submitting" @click="closeDialog">取消</ElButton>
        <ElButton type="primary" :loading="submitting" :disabled="submitting" @click="save">{{ submitting ? '保存中…' : '保存' }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-if="pendingStatus"
      :model-value="true"
      :title="pendingStatus.next === 1 ? '启用员工' : '停用员工'"
      width="min(430px, calc(100vw - 32px))"
      :show-close="!submitting" :close-on-click-modal="false" :close-on-press-escape="!submitting"
      @update:model-value="(open) => { if (!open) pendingStatus = null }"
    >
      <p>确认将「{{ pendingStatus.user.realName }}」（{{ pendingStatus.user.username }}）设为{{ pendingStatus.next === 1 ? '启用' : '停用' }}？</p>
      <p class="org-note">启停只作用于该员工；停用后其全部登录会话会被服务端撤销。</p>
      <template #footer>
        <ElButton :disabled="submitting" @click="pendingStatus = null">取消</ElButton>
        <ElButton type="primary" :loading="submitting" :disabled="submitting" @click="confirmStatus">确认</ElButton>
      </template>
    </ElDialog>
  </section>
</template>
