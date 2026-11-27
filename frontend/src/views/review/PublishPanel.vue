<script setup>
import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElInput, ElLoading, ElMessage, ElMessageBox, ElOption,
  ElPagination, ElSelect, ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import {
  batchOfflineArticles, batchPublishArticles, buildPublishQuery, canChangePublication,
  formatLocalDateTime, listPublishableArticles, offlineArticle, publishArticle,
} from '../../api/review.js'
import { useSessionStore } from '../../stores/session.js'
import { articleStatuses } from '../content/contentModel.js'
import { useContentQuery } from '../content/useContentQuery.js'

const props = defineProps({ active: Boolean })
const session = useSessionStore()
const canList = computed(() => session.hasPermission('article:list'))
const statusOptions = ['APPROVED', 'PUBLISHED', 'OFFLINE']
const defaultStatus = () =>
  [false, true].some((batch) => canChangePublication('publish', 'APPROVED', batch, session.hasPermission))
    ? 'APPROVED'
    : 'PUBLISHED'
const status = ref(defaultStatus())
const appliedStatus = ref(status.value)
const keyword = ref('')
const appliedKeyword = ref('')
const current = ref(1)
const size = ref(10)
const table = ref(null)
const selection = ref([])
const busy = ref(false)
const actionError = ref('')
const vLoading = ElLoading.directive
let disposed = false

const { data, error, loading, load, clear } = useContentQuery((config) =>
  listPublishableArticles(buildPublishQuery({
    keyword: appliedKeyword.value, status: appliedStatus.value, current: current.value, size: size.value,
  }), config))
const rows = computed(() => data.value?.records ?? [])
const total = computed(() => data.value?.total ?? 0)
const action = computed(() => ({ APPROVED: 'publish', PUBLISHED: 'offline' })[appliedStatus.value])
const actionLabel = computed(() => action.value === 'publish' ? '发布' : '下架')
const canBatch = computed(() =>
  canList.value && canChangePublication(action.value, appliedStatus.value, true, session.hasPermission))
const blocked = computed(() => busy.value || loading.value)

function clearSelection() {
  selection.value = []
  table.value?.clearSelection()
}

async function reload() {
  clearSelection()
  if (!props.active || !canList.value) return
  await load()
  if (!props.active || !canList.value || loading.value || error.value || !data.value) return
  const lastPage = Math.max(1, Math.ceil(total.value / size.value))
  if (current.value > lastPage) {
    current.value = lastPage
    await load()
  }
}

watch([() => props.active, () => session.accessToken, canList], ([active, token, allowed], previous) => {
  clear()
  clearSelection()
  actionError.value = ''
  if (token !== previous[1]) {
    status.value = defaultStatus()
    appliedStatus.value = status.value
    keyword.value = ''
    appliedKeyword.value = ''
    current.value = 1
  }
  if (active && token && allowed && !busy.value) void reload()
}, { immediate: true })
onScopeDispose(() => { disposed = true })

function search() {
  if (blocked.value || !canList.value) return
  if ([...keyword.value].length > 255) {
    status.value = appliedStatus.value
    actionError.value = '文章关键词不能超过 255 字'
    return
  }
  actionError.value = ''
  appliedKeyword.value = keyword.value
  appliedStatus.value = status.value
  current.value = 1
  void reload()
}

function reset() {
  if (blocked.value) return
  keyword.value = ''
  status.value = defaultStatus()
  search()
}

function changePage(page) {
  if (blocked.value || page === current.value) return
  current.value = page
  actionError.value = ''
  void reload()
}

function changeSize(nextSize) {
  if (blocked.value || nextSize === size.value) return
  size.value = nextSize
  current.value = 1
  actionError.value = ''
  void reload()
}

function canActOn(row, batch = false) {
  return canChangePublication(action.value, row.status, batch, session.hasPermission)
}

function isCurrentRow(row) {
  return rows.value.some(({ id, status, version }) =>
    id === row.id && status === row.status && version === row.version)
}

function selectable(row) {
  return !blocked.value && canBatch.value && canActOn(row, true)
}

async function changePublication(records, batch = false) {
  if (blocked.value || !canList.value || !records.length) return
  if (records.some((row) => !isCurrentRow(row) || !canActOn(row, batch))) return
  const operation = action.value
  const label = actionLabel.value
  const token = session.accessToken
  const items = records.map(({ id, version }) => ({ id, version }))
  const inSession = () => !disposed && session.accessToken === token
  const message = batch ? `确定${label}选中的 ${items.length} 篇文章？` : `确定${label}《${records[0].title}》？`

  busy.value = true
  actionError.value = ''
  try {
    await ElMessageBox.confirm(message, batch ? `批量${label}` : label, {
      type: operation === 'offline' ? 'warning' : 'info',
      confirmButtonText: label, cancelButtonText: '取消',
    })
    if (!inSession() || !props.active || !canList.value
      || operation !== action.value || records.some((row) => !isCurrentRow(row) || !canActOn(row, batch))) return

    const config = { silent: true }
    if (batch) {
      const request = operation === 'publish' ? batchPublishArticles : batchOfflineArticles
      await request(items, config)
    } else {
      const request = operation === 'publish' ? publishArticle : offlineArticle
      await request(items[0].id, { version: items[0].version }, config)
    }
    if (!inSession()) return
    ElMessage.success(batch ? `已${label} ${items.length} 篇文章` : `文章已${label}`)
    await reload()
  } catch (failure) {
    if (!inSession() || failure === 'cancel' || failure === 'close') return
    if (failure.code === 409 || failure.code === 404) {
      actionError.value = `${batch ? '本次批量操作未生效。' : ''}文章状态已变化或文章已不存在，请核对最新列表后再操作。`
      await reload()
    } else {
      actionError.value = failure.message || `${label}失败，请稍后重试。`
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="cms-page">
    <ElAlert v-if="!canList" type="warning" :closable="false" show-icon
      title="当前账号没有查看后台文章列表的权限。" />
    <template v-else>
      <form class="cms-filter-bar" aria-label="发布与下架筛选" @submit.prevent="search">
        <div class="cms-filter-fields">
          <label class="cms-filter-field">
            <span>文章关键词</span>
            <ElInput v-model="keyword" clearable :disabled="blocked" placeholder="输入标题或摘要关键词" />
          </label>
          <label class="cms-filter-field">
            <span>文章状态</span>
            <ElSelect v-model="status" :disabled="blocked" @change="search">
              <ElOption v-for="option in statusOptions" :key="option"
                :label="articleStatuses[option].label" :value="option" />
            </ElSelect>
          </label>
        </div>
        <div class="cms-filter-actions">
          <ElButton type="primary" native-type="submit" :loading="loading" :disabled="busy">查询</ElButton>
          <ElButton :disabled="blocked" @click="reset">重置</ElButton>
        </div>
      </form>

      <ElAlert v-if="actionError" :title="actionError" type="warning" :closable="false" show-icon />
      <ElAlert v-if="error" :title="error.message" type="error" :closable="false" show-icon>
        <ElButton size="small" :disabled="busy" @click="reload">重新加载</ElButton>
      </ElAlert>
      <section v-else v-loading="loading" class="cms-surface-card" aria-label="待发布与已发布文章">
        <div class="cms-card-meta">
          <span>共 {{ total }} 篇文章</span>
          <div v-if="canBatch" class="cms-row-actions">
            <span v-if="selection.length">已选 {{ selection.length }} 篇</span>
            <ElButton size="small" :type="action === 'publish' ? 'primary' : 'danger'"
              :disabled="blocked || !selection.length" @click="changePublication(selection, true)">
              批量{{ actionLabel }}
            </ElButton>
          </div>
        </div>
        <ElTable ref="table" :data="rows" row-key="id" stripe empty-text="没有符合条件的文章"
          @selection-change="selection = $event">
          <ElTableColumn v-if="canBatch" :key="appliedStatus" type="selection" :selectable="selectable" width="48" />
          <ElTableColumn label="标题" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <RouterLink v-if="session.hasPermission('article:read')"
                :to="{ name: 'article-detail', params: { id: row.id } }" class="cms-inline-link">{{ row.title }}</RouterLink>
              <span v-else>{{ row.title }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="categoryName" label="分类" min-width="125" show-overflow-tooltip />
          <ElTableColumn prop="authorName" label="作者" min-width="100" show-overflow-tooltip />
          <ElTableColumn label="状态" width="110">
            <template #default="{ row }">
              <ElTag :type="articleStatuses[row.status]?.type" effect="light" round>
                {{ articleStatuses[row.status]?.label ?? row.status }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="最近发布时间" width="175">
            <template #default="{ row }">{{ formatLocalDateTime(row.publishedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn v-if="canChangePublication(action, appliedStatus, false, session.hasPermission)"
            label="操作" width="95" fixed="right">
            <template #default="{ row }">
              <ElButton v-if="canActOn(row)" :disabled="blocked" link
                :type="action === 'publish' ? 'primary' : 'danger'" @click="changePublication([row])">
                {{ actionLabel }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="cms-pagination">
          <ElPagination background :current-page="current" :page-size="size" :page-sizes="[10, 20, 50]"
            :total="total" :disabled="blocked" layout="total, sizes, prev, pager, next"
            @size-change="changeSize" @current-change="changePage" />
        </div>
      </section>
    </template>
  </div>
</template>
