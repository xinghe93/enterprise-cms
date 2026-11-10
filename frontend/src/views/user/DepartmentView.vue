<script setup>
/**
 * 部门管理（功能② 部门与角色分配 · 部门树部分）。
 *
 * 契约要点：《接口与协作约定 V1.0》5 / 7 节
 *  - 树按 status 筛选时由后端保留必要祖先的真实状态，前端不把停用祖先伪装成启用；
 *  - 更新是完整 PUT，parentId / deptName / sortOrder 均必填，deptCode 不可改；
 *  - 禁止把节点移到自身或后代下；切换父节点时目标与全部祖先必须启用；
 *  - 存在子部门或任何员工引用（含停用员工）时删除返回 409，启停只改当前节点、不级联。
 * 权限由后端 AOP 校验，这里不做前端隐藏，避免用界面过滤替代鉴权。
 */
import { computed, onMounted, ref } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { api } from '../../api/index.js'
import { createUserApi } from '../../api/user.js'
import { flattenDepartments, isValidId, parentOptions, sortDepartmentTree } from '../../utils/organization.js'

const userApi = createUserApi(api)
const tree = ref([])
const loading = ref(false)
const loadFailed = ref(false)
const statusFilter = ref('')
const submitting = ref(false)
const editor = ref(null)
const pendingDelete = ref(null)
const formError = ref('')

const rows = computed(() => flattenDepartments(sortDepartmentTree(tree.value)))
const candidates = computed(() => parentOptions(tree.value, {
  editingId: editor.value?.mode === 'edit' ? editor.value.node.id : null,
  keepParentId: editor.value?.mode === 'edit' ? editor.value.node.parentId ?? null : null,
}))

async function loadTree() {
  if (loading.value) return
  loading.value = true
  loadFailed.value = false
  try {
    tree.value = await userApi.getDepartmentTree({
      status: statusFilter.value === '' ? '' : Number(statusFilter.value),
    })
  } catch {
    // api/client.js 已统一弹出后端错误；内联只给出可重试的中性状态，不重复弹窗。
    loadFailed.value = true
    tree.value = []
  } finally {
    loading.value = false
  }
}

function openCreate(parentId = '') {
  formError.value = ''
  editor.value = {
    mode: 'create',
    form: { deptCode: '', deptName: '', parentId, sortOrder: 0 },
  }
}

function openEdit(node) {
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
  const { form, mode } = editor.value
  if (mode === 'create' && (!form.deptCode || form.deptCode.length > 64)) return '部门编码必填且不超过 64 字'
  if (!form.deptName || form.deptName.length > 100) return '部门名称必填且不超过 100 字'
  const order = Number(form.sortOrder)
  if (!Number.isInteger(order) || order < 0 || order > 2147483647) return '排序值需为 0～2147483647 的整数'
  if (form.parentId && !isValidId(form.parentId)) return '上级部门 ID 非法'
  if (form.parentId && !candidates.value.some((option) => option.id === form.parentId)) {
    return '上级部门不可为自身、后代或停用分支'
  }
  return ''
}

async function save() {
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
      })
    } else {
      // 完整 PUT：即使未改动，三个字段也必须全部提交，不能靠省略保留旧值。
      await userApi.updateDepartment(node.id, {
        parentId,
        deptName: form.deptName,
        sortOrder: Number(form.sortOrder),
      })
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
  if (submitting.value) return
  submitting.value = true
  try {
    await userApi.setDepartmentStatus(node.id, node.status === 1 ? 0 : 1)
    await loadTree()
  } catch {
    // 公共层已提示 404 / 409 / 500，此处不吞异常也不重复弹窗。
  } finally {
    submitting.value = false
  }
}

function askDelete(node) {
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

onMounted(loadTree)
</script>

<template>
  <section class="org-page">
    <div class="page-heading">
      <p class="eyebrow">ORGANIZATION / A</p>
      <h1>部门管理</h1>
      <p>维护部门层级、编码与排序；启停只影响当前节点，删除空部门需无子部门与员工引用。</p>
    </div>

    <div class="org-toolbar">
      <label class="org-inline-field">
        <span>状态</span>
        <select v-model="statusFilter" @change="loadTree">
          <option value="">全部状态</option>
          <option value="1">仅启用</option>
          <option value="0">仅停用</option>
        </select>
      </label>
      <span class="org-toolbar-hint">树各层按排序值、ID 升序；筛选保留必要祖先的真实状态</span>
      <ElButton type="primary" :disabled="loading" @click="openCreate()">新建根部门</ElButton>
      <ElButton :disabled="loading" @click="loadTree">刷新</ElButton>
    </div>

    <p v-if="loadFailed" class="org-alert" role="alert">部门树未加载，请确认服务与登录态后重试。</p>

    <div class="org-card">
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
                <button type="button" :disabled="submitting" @click="openCreate(row.node.id)">新增子部门</button>
                <button type="button" :disabled="submitting" @click="openEdit(row.node)">编辑</button>
                <button type="button" :disabled="submitting" @click="toggleStatus(row.node)">
                  {{ row.node.status === 1 ? '停用' : '启用' }}
                </button>
                <button
                  type="button"
                  class="is-danger"
                  :disabled="submitting || (row.node.children && row.node.children.length > 0)"
                  :title="row.node.children && row.node.children.length > 0 ? '存在子部门，不能删除' : '删除空部门'"
                  @click="askDelete(row.node)"
                >删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!rows.length && !loading">
            <td colspan="5" class="org-empty">暂无部门数据，点击「新建根部门」开始搭建组织结构。</td>
          </tr>
        </tbody>
      </table>
      <p v-if="loading" class="org-loading">正在加载部门树…</p>
    </div>

    <div v-if="editor" class="org-overlay" @click.self="closeEditor">
      <section class="org-dialog" role="dialog" aria-modal="true" aria-labelledby="dept-dialog-title">
        <header class="org-dialog-head">
          <h2 id="dept-dialog-title">{{ editor.mode === 'create' ? '新建部门' : '编辑部门' }}</h2>
          <button type="button" class="org-icon-btn" aria-label="关闭弹窗" @click="closeEditor">×</button>
        </header>
        <div class="org-dialog-body">
          <label class="org-field">
            <span>部门编码 <em>*</em></span>
            <input v-model.trim="editor.form.deptCode" maxlength="64" :disabled="editor.mode === 'edit'" placeholder="如 DEPT_SALES" />
            <small v-if="editor.mode === 'edit'">编码创建后不可修改；更新请求不提交该字段。</small>
          </label>
          <label class="org-field">
            <span>部门名称 <em>*</em></span>
            <input v-model.trim="editor.form.deptName" maxlength="100" placeholder="如 销售部" />
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
        </div>
        <footer class="org-dialog-foot">
          <ElButton :disabled="submitting" @click="closeEditor">取消</ElButton>
          <ElButton type="primary" :disabled="submitting" @click="save">{{ submitting ? '保存中…' : '保存' }}</ElButton>
        </footer>
      </section>
    </div>

    <div v-if="pendingDelete" class="org-overlay" @click.self="pendingDelete = null">
      <section class="org-dialog org-dialog-sm" role="dialog" aria-modal="true" aria-labelledby="dept-delete-title">
        <header class="org-dialog-head">
          <h2 id="dept-delete-title">删除部门</h2>
          <button type="button" class="org-icon-btn" aria-label="关闭弹窗" @click="pendingDelete = null">×</button>
        </header>
        <div class="org-dialog-body">
          <p>确认删除「{{ pendingDelete.deptName }}」（{{ pendingDelete.deptCode }}）？</p>
          <p class="org-note">该部门下若仍有子部门或任何员工引用（含停用员工），服务端会返回 409 并保留数据。</p>
        </div>
        <footer class="org-dialog-foot">
          <ElButton :disabled="submitting" @click="pendingDelete = null">取消</ElButton>
          <ElButton type="primary" :disabled="submitting" @click="confirmDelete">确认删除</ElButton>
        </footer>
      </section>
    </div>
  </section>
</template>
