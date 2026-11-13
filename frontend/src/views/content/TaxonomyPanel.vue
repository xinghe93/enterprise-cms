<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage, ElMessageBox,
  ElOption, ElPagination, ElSelect, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import {
  createCategory, createTag, deleteCategory, deleteTag, listCategories, listTags,
  setCategoryStatus, setTagStatus, updateCategory, updateTag,
} from '../../api/content.js'
import { useSessionStore } from '../../stores/session.js'
import ContentState from './ContentState.vue'
import { categoryOptions, displayTime } from './contentModel.js'
import { useContentQuery } from './useContentQuery.js'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/form-item/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import 'element-plus/es/components/message-box/style/css.mjs'
import 'element-plus/es/components/pagination/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'

const props = defineProps({ kind: { type: String, required: true } })
const isCategory = props.kind === 'category'
const label = isCategory ? '分类' : '标签'
const nameKey = isCategory ? 'categoryName' : 'tagName'
const nameLimit = isCategory ? 100 : 64
const pageSize = 10
const session = useSessionStore()
const can = (action) => session.isAuthenticated && session.hasPermission(`${props.kind}:${action}`)
const status = ref(null)
const keyword = ref('')
const filters = reactive({ current: 1, size: pageSize, status: undefined, keyword: undefined })
const { data, loading, error, load } = useContentQuery((config) => isCategory
  ? listCategories({ status: filters.status }, config)
  : listTags({ ...filters }, config))
const rows = computed(() => isCategory ? data.value ?? [] : data.value?.records ?? [])
const allCategories = useContentQuery((config) => listCategories(undefined, config))
const parentOptions = computed(() => categoryOptions(
  filters.status === undefined ? rows.value : allCategories.data.value ?? [],
  { activeOnly: true, currentId: originalParent.value, excludeId: editingId.value },
))

const dialog = ref(false)
const editingId = ref(null)
const originalParent = ref(null)
const fields = reactive({ name: '', parentId: '', sortOrder: 0 })
const formError = ref('')
const busy = ref(false)

watch(data, (result) => {
  if (isCategory || !result) return
  const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
  if (filters.current > lastPage) paginate(lastPage)
})

void load()

function search() {
  Object.assign(filters, { current: 1, status: status.value ?? undefined, keyword: keyword.value.trim() || undefined })
  void load()
}

function reset() {
  status.value = null
  keyword.value = ''
  search()
}

function paginate(page) {
  filters.current = page
  void load()
}

function openEditor(row) {
  editingId.value = row?.id ?? null
  originalParent.value = row?.parentId ?? null
  Object.assign(fields, { name: row?.[nameKey] ?? '', parentId: row?.parentId ?? '', sortOrder: row?.sortOrder ?? 0 })
  formError.value = ''
  dialog.value = true
  allCategories.clear()
  if (isCategory && filters.status !== undefined) void allCategories.load()
}

async function save() {
  if (busy.value) return
  const name = fields.name.trim()
  if (!name || fields.name.length > nameLimit) {
    formError.value = `请输入 1 至 ${nameLimit} 个字符的${label}名称`
    return
  }
  if (isCategory && (!Number.isInteger(fields.sortOrder) || fields.sortOrder < 0 || fields.sortOrder > 2147483647)) {
    formError.value = '排序值须为 0 至 2147483647 的整数'
    return
  }
  const data = isCategory
    ? { categoryName: name, parentId: fields.parentId || null, sortOrder: fields.sortOrder }
    : { tagName: name }

  busy.value = true
  formError.value = ''
  try {
    if (editingId.value) await (isCategory ? updateCategory : updateTag)(editingId.value, data)
    else await (isCategory ? createCategory : createTag)(data)
    ElMessage.success(`${label}已保存`)
    dialog.value = false
    await load()
  } catch {
    // 失败提示由公共请求客户端处理，保留表单便于修改。
  } finally {
    busy.value = false
  }
}

async function mutate(row, deleting) {
  if (busy.value) return
  const nextStatus = row.status === 1 ? 0 : 1
  const action = deleting ? '删除' : nextStatus === 1 ? '启用' : '停用'
  busy.value = true
  try {
    await ElMessageBox.confirm(`确定${action}${label}「${row[nameKey]}」？`, `${action}${label}`, {
      type: 'warning', confirmButtonText: action, cancelButtonText: '取消',
    })
    if (deleting) await (isCategory ? deleteCategory : deleteTag)(row.id)
    else await (isCategory ? setCategoryStatus : setTagStatus)(row.id, nextStatus)
    ElMessage.success(`${label}已${action}`)
    await load()
  } catch {
    // 取消或写入失败时保留原数据。
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="content-filters" @submit.prevent="search">
    <label v-if="!isCategory">
      标签名称
      <ElInput v-model="keyword" aria-label="标签名称" maxlength="64" clearable placeholder="输入标签名称检索" />
    </label>
    <label class="status-filter">
      状态
      <ElSelect v-model="status" aria-label="目录状态" clearable :value-on-clear="null" placeholder="全部状态">
        <ElOption label="启用" :value="1" />
        <ElOption label="停用" :value="0" />
      </ElSelect>
    </label>
    <div class="filter-actions">
      <ElButton type="primary" native-type="submit">查询</ElButton>
      <ElButton @click="reset">重置</ElButton>
    </div>
    <ElButton v-if="can('create')" class="create-button" :disabled="busy" @click="openEditor()">新建{{ label }}</ElButton>
  </form>

  <ContentState :allowed="true" :loading="loading" :error="error" @retry="load">
    <div v-if="data" class="content-panel table-wrap">
      <ElEmpty v-if="!rows.length" :description="`没有符合条件的${label}`" />
      <ElTable v-else :data="rows" row-key="id">
        <ElTableColumn :prop="nameKey" :label="`${label}名称`" min-width="155" />
        <ElTableColumn v-if="isCategory" prop="sortOrder" label="排序" width="86" />
        <ElTableColumn label="状态" width="85">
          <template #default="{ row }">
            <ElTag size="small" :type="row.status === 1 ? 'success' : 'info'" effect="plain">{{ row.status === 1 ? '启用' : '停用' }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="145">
          <template #default="{ row }">{{ displayTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn v-if="can('update') || can('status') || can('delete')" label="操作" width="152">
          <template #default="{ row }">
            <div class="row-actions">
              <ElButton v-if="can('update')" link type="primary" :disabled="busy" @click="openEditor(row)">编辑</ElButton>
              <ElButton v-if="can('status')" link type="primary" :disabled="busy" @click="mutate(row, false)">{{ row.status === 1 ? '停用' : '启用' }}</ElButton>
              <ElButton v-if="can('delete')" link type="danger" :disabled="busy" @click="mutate(row, true)">删除</ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        v-if="!isCategory && data.total > pageSize" class="content-pagination" small layout="total, prev, pager, next"
        :total="data.total" :current-page="filters.current" :page-size="pageSize" :pager-count="5" @update:current-page="paginate"
      />
    </div>
  </ContentState>

  <ElDialog
    v-model="dialog" :title="`${editingId ? '编辑' : '新建'}${label}`" width="min(480px, calc(100vw - 32px))"
    :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy" destroy-on-close
  >
    <ElForm label-position="top" @submit.prevent="save">
      <ElFormItem :label="`${label}名称`" required>
        <ElInput v-model="fields.name" :aria-label="`${label}名称`" :maxlength="nameLimit" show-word-limit />
      </ElFormItem>
      <ElFormItem v-if="isCategory" label="上级分类">
        <ElSelect
          v-model="fields.parentId" aria-label="上级分类" filterable :loading="allCategories.loading.value"
          :disabled="allCategories.loading.value || Boolean(allCategories.error.value)"
        >
          <ElOption label="根分类（无上级）" value="" />
          <ElOption v-for="option in parentOptions" :key="option.id" :value="option.id" :label="option.label" />
        </ElSelect>
        <ElButton v-if="allCategories.error.value" link type="danger" @click="allCategories.load()">加载上级分类失败，点击重试</ElButton>
        <p class="field-help">新选上级及其祖先须启用。保留原上级时可继续使用已停用的层级。</p>
      </ElFormItem>
      <ElFormItem v-if="isCategory" label="排序" required>
        <ElInputNumber v-model="fields.sortOrder" aria-label="排序" :min="0" :max="2147483647" :step="1" step-strictly />
      </ElFormItem>
      <ElAlert v-if="formError" :title="formError" :closable="false" type="error" show-icon />
      <div class="dialog-actions">
        <ElButton :disabled="busy" @click="dialog = false">取消</ElButton>
        <ElButton type="primary" native-type="submit" :loading="busy">保存</ElButton>
      </div>
    </ElForm>
  </ElDialog>
</template>

<style scoped>
.content-filters .status-filter { flex: 0 1 155px; }
.create-button { margin-inline-start: auto; }
.dialog-actions { display: flex; justify-content: flex-end; margin-top: 20px; }
.el-form-item .field-help { width: 100%; margin-top: 5px; }
@media (max-width: 480px) {
  .create-button { margin-inline-start: 0; }
}
</style>
