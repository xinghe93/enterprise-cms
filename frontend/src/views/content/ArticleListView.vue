<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElEmpty, ElInput, ElOption, ElPagination, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus'
import AppIcon from '../../components/AppIcon.vue'
import { listArticles, listCategories, listPortalArticles, listPortalCategories, listPortalTags, listTags } from '../../api/content.js'
import { useSessionStore } from '../../stores/session.js'
import ContentState from './ContentState.vue'
import ContentTagSelect from './ContentTagSelect.vue'
import { articleStatuses, categoryOptions, displayTime, isContentId, isEditableArticle } from './contentModel.js'
import { useArticleActions } from './useArticleActions.js'
import { useContentQuery } from './useContentQuery.js'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/pagination/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import './content.css'

const props = defineProps({ portal: Boolean })
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const can = (code) => session.isAuthenticated && session.hasPermission(code)
const listName = computed(() => props.portal ? 'portal' : 'articles')
const detailName = computed(() => props.portal ? 'portal-article' : 'article-detail')
const allowed = computed(() => can(props.portal ? 'portal:article:list' : 'article:list'))
const canRead = computed(() => can(props.portal ? 'portal:article:read' : 'article:read'))
const canListCategories = computed(() => can(props.portal ? 'portal:category:tree' : 'category:tree'))
const canListTags = computed(() => can(props.portal ? 'portal:tag:list' : 'tag:list'))
const canEdit = computed(() => !props.portal && canRead.value && can('article:update'))
const filters = reactive({ keyword: '', categoryId: '', tagId: '', status: '', authorId: '' })
const authorError = ref('')
const pageSize = 10

const criteria = computed(() => {
  const { current, keyword, categoryId, tagId, authorId, status } = route.query
  const page = Number(current)
  return {
    current: Number.isSafeInteger(page) && page > 0 ? page : 1,
    size: pageSize,
    keyword: typeof keyword === 'string' ? Array.from(keyword).slice(0, 255).join('').trim() || undefined : undefined,
    categoryId: isContentId(categoryId) ? categoryId : undefined,
    tagId: isContentId(tagId) ? tagId : undefined,
    ...(!props.portal && {
      status: typeof status === 'string' && Object.hasOwn(articleStatuses, status) ? status : undefined,
      authorId: isContentId(authorId) ? authorId : undefined,
    }),
  }
})

const { data, loading, error, load, clear } = useContentQuery((config) =>
  (props.portal ? listPortalArticles : listArticles)(criteria.value, config))
const categoryQuery = useContentQuery((config) =>
  props.portal ? listPortalCategories(config) : listCategories(undefined, config))
const categories = computed(() => categoryOptions(categoryQuery.data.value ?? []))
const fetchTags = (params, config) => (props.portal ? listPortalTags : listTags)(params, config)
const { busy, changeArticle } = useArticleActions()

watch([allowed, () => route.fullPath, listName], () => {
  if (route.name !== listName.value) return
  Object.assign(filters, {
    keyword: criteria.value.keyword ?? '',
    categoryId: criteria.value.categoryId ?? '',
    tagId: criteria.value.tagId ?? '',
    status: criteria.value.status ?? '',
    authorId: criteria.value.authorId ?? '',
  })
  authorError.value = ''
  clear()
  if (allowed.value) void load()
}, { immediate: true })

watch([allowed, canListCategories, listName], () => {
  categoryQuery.clear()
  if (allowed.value && canListCategories.value) void categoryQuery.load()
}, { immediate: true })

watch(data, (result) => {
  if (!result || route.name !== listName.value) return
  const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
  if (criteria.value.current > lastPage) navigate(criteria.value, lastPage, true)
})

function navigate(query, current = 1, replace = false) {
  const { keyword, categoryId, tagId, status, authorId } = query
  const target = {
    name: listName.value,
    query: {
      keyword: keyword?.trim() || undefined, categoryId: categoryId || undefined, tagId: tagId || undefined,
      current: current === 1 ? undefined : String(current),
      ...(!props.portal && { status: status || undefined, authorId: authorId?.trim() || undefined }),
    },
  }
  if (router.resolve(target).fullPath === route.fullPath) void load()
  else if (replace) void router.replace(target)
  else void router.push(target)
}

function search() {
  if (!props.portal && filters.authorId.trim() && !isContentId(filters.authorId.trim())) {
    authorError.value = '请输入有效的作者 ID'
    return
  }
  authorError.value = ''
  navigate(filters)
}

function reset() {
  Object.assign(filters, { keyword: '', categoryId: '', tagId: '', status: '', authorId: '' })
  authorError.value = ''
  navigate(filters)
}

async function mutate(article, action) {
  const originalRoute = route.fullPath
  if (!await changeArticle(article, action) || route.fullPath !== originalRoute) return
  await load()
}
</script>

<template>
  <section class="content-page" :class="{ 'portal-page': portal }">
    <header class="content-heading" :class="{ 'portal-heading': portal }">
      <div class="page-heading">
        <h1>{{ portal ? '知识门户' : '文章管理' }}</h1>
      </div>
      <AppIcon v-if="portal" class="portal-stamp" name="book" :size="52" />
      <ElButton v-else-if="can('article:create')" type="primary" @click="router.push({ name: 'article-new', query: route.query })">新建草稿</ElButton>
    </header>

    <form v-if="allowed" class="content-filters" @submit.prevent="search">
      <label>
        标题或摘要
        <ElInput v-model="filters.keyword" aria-label="标题或摘要" maxlength="255" :count-graphemes="(value) => Array.from(value).length" clearable placeholder="输入检索关键词" />
      </label>
      <label v-if="canListCategories">
        分类
        <ElSelect
          v-model="filters.categoryId" aria-label="分类" filterable clearable placeholder="全部分类"
          :loading="categoryQuery.loading.value" :no-data-text="categoryQuery.error.value?.message || '暂无分类'"
          @visible-change="(open) => open && categoryQuery.error.value && categoryQuery.load()"
        >
          <ElOption v-for="option in categories" :key="option.id" :label="option.label" :value="option.id" />
        </ElSelect>
      </label>
      <label v-if="canListTags">
        标签
        <ContentTagSelect v-model="filters.tagId" :fetch-tags="fetchTags" />
      </label>
      <label v-if="!portal">
        状态
        <ElSelect v-model="filters.status" aria-label="文章状态" clearable placeholder="全部状态">
          <ElOption v-for="(status, key) in articleStatuses" :key="key" :label="status.label" :value="key" />
        </ElSelect>
      </label>
      <label v-if="!portal">
        作者 ID
        <ElInput v-model="filters.authorId" aria-label="作者 ID" maxlength="19" clearable placeholder="精确匹配作者" @input="authorError = ''" />
      </label>
      <div class="filter-actions">
        <ElButton type="primary" native-type="submit">{{ portal ? '检索' : '查询' }}</ElButton>
        <ElButton @click="reset">重置</ElButton>
      </div>
      <p v-if="authorError" class="filter-error" role="alert">{{ authorError }}</p>
    </form>

    <ContentState :allowed="allowed" :loading="loading" :error="error" @retry="load">
      <div v-if="data" class="content-panel table-wrap">
        <ElEmpty v-if="!data.records.length" :description="portal ? '未找到符合条件的已发布文章' : '未找到符合条件的文章'" />
        <template v-else-if="portal">
          <p class="result-count" role="status">找到 {{ data.total }} 篇文章</p>
          <article v-for="article in data.records" :key="article.id" class="library-item">
            <div class="library-meta"><span>{{ article.categoryName }}</span><span>{{ displayTime(article.publishedAt) }}</span></div>
            <h2>
              <RouterLink v-if="canRead" :to="{ name: detailName, params: { id: article.id }, query: route.query }">
                {{ article.title }}
              </RouterLink>
              <template v-else>{{ article.title }}</template>
            </h2>
            <p v-if="article.summary" class="library-summary">{{ article.summary }}</p>
            <div class="library-byline">
              <span>{{ article.authorName }}</span>
              <ElTag v-for="tag in article.tags" :key="tag.id" size="small" type="info" effect="plain">{{ tag.tagName }}</ElTag>
            </div>
          </article>
        </template>
        <ElTable v-else :data="data.records" row-key="id">
          <ElTableColumn label="文章" min-width="180">
            <template #default="{ row }">
              <RouterLink v-if="canRead" class="text-link" :to="{ name: detailName, params: { id: row.id }, query: route.query }">{{ row.title }}</RouterLink>
              <span v-else>{{ row.title }}</span>
              <p v-if="row.summary" class="row-summary">{{ row.summary }}</p>
            </template>
          </ElTableColumn>
          <ElTableColumn label="分类 / 标签" min-width="135">
            <template #default="{ row }">
              {{ row.categoryName }}
              <p v-if="row.tags.length" class="row-summary">{{ row.tags.map((tag) => tag.tagName).join(' · ') }}</p>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="authorName" label="作者" min-width="95" />
          <ElTableColumn label="状态" width="95">
            <template #default="{ row }">
              <ElTag size="small" :type="articleStatuses[row.status]?.type" effect="plain">{{ articleStatuses[row.status]?.label || row.status }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="更新时间" width="145">
            <template #default="{ row }">{{ displayTime(row.updatedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn v-if="canEdit || can('article:submit') || can('article:delete')" label="操作" width="175">
            <template #default="{ row }">
              <div v-if="isEditableArticle(row.status)" class="row-actions">
                <RouterLink v-if="canEdit" class="text-link" :to="{ name: 'article-edit', params: { id: row.id }, query: route.query }">编辑</RouterLink>
                <ElButton v-if="can('article:submit')" link type="primary" :disabled="busy" @click="mutate(row, 'submit')">提交审核</ElButton>
                <ElButton v-if="can('article:delete')" link type="danger" :disabled="busy" @click="mutate(row, 'delete')">删除</ElButton>
              </div>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElPagination
          v-if="data.total > pageSize" class="content-pagination" small layout="total, prev, pager, next"
          :total="data.total" :current-page="criteria.current" :page-size="pageSize" :pager-count="5"
          @update:current-page="navigate(criteria, $event)"
        />
      </div>
    </ContentState>
  </section>
</template>

<style scoped>
.portal-heading {
  position: relative;
  overflow: hidden;
  align-items: center;
  background: #edf1e5;
  border: 1px solid #e1e7d9;
  border-radius: 10px;
  padding: clamp(20px, 4vw, 39px);
}
.portal-heading h1 { font-family: "Songti SC", "Noto Serif CJK SC", "SimSun", serif; font-size: clamp(31px, 4vw, 46px); }
.portal-stamp { color: #789379; flex: 0 0 auto; margin-inline-end: 12px; }
.result-count { padding: 15px 24px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 12px; }
.library-item { padding: 20px 24px; border-bottom: 1px solid var(--line); }
.library-item:last-child { border-bottom: 0; }
.library-item h2 { font-family: "Songti SC", "Noto Serif CJK SC", "SimSun", serif; font-size: 23px; margin: 7px 0; overflow-wrap: anywhere; }
.library-item h2 a:hover { color: var(--el-color-primary); text-decoration: underline; text-underline-offset: 5px; }
.library-meta, .library-byline { display: flex; align-items: center; gap: 10px 16px; flex-wrap: wrap; font-size: 12px; color: #56705c; }
.library-meta span:first-child { font-weight: 600; }
.library-byline { margin-top: 11px; }
.library-summary { max-width: 78ch; color: var(--muted); line-height: 1.7; overflow-wrap: anywhere; font-size: 14px; }
.row-summary { font-size: 12px; color: var(--muted); white-space: nowrap; text-overflow: ellipsis; overflow: hidden; }
.filter-error { flex-basis: 100%; font-size: 12px; color: var(--el-color-danger); }
@media (max-width: 420px) {
  .portal-stamp { display: none; }
  .library-item, .result-count { padding-inline: 16px; }
  .row-actions { gap: 6px; }
}
</style>
