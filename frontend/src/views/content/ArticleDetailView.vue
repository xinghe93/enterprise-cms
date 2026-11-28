<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElDialog, ElMessage, ElTag } from 'element-plus'
import { getArticle, getPortalArticle, listTags } from '../../api/content.js'
import { replaceArticleTags } from '../../api/review.js'
import AppIcon from '../../components/AppIcon.vue'
import { useSessionStore } from '../../stores/session.js'
import ContentState from './ContentState.vue'
import ContentTagSelect from './ContentTagSelect.vue'
import { articleStatuses, displayTime, isContentId, isEditableArticle } from './contentModel.js'
import { useArticleActions } from './useArticleActions.js'
import { useContentQuery } from './useContentQuery.js'
import './content.css'

const props = defineProps({ portal: Boolean })
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const can = (code) => session.isAuthenticated && session.hasPermission(code)
const allowed = computed(() => can(props.portal ? 'portal:article:read' : 'article:read'))
const id = computed(() => route.params.id)
const validId = computed(() => isContentId(id.value))
const fromReviews = computed(() => !props.portal && route.query.from === 'reviews')
const back = computed(() => {
  if (props.portal) return { name: 'portal', query: route.query }
  if (fromReviews.value) return { name: 'reviews' }
  return { name: 'articles', query: route.query }
})
const backLabel = computed(() => (props.portal ? '返回知识门户' : fromReviews.value ? '返回审核与发布' : '返回文章管理'))
const { data: article, loading, error, load, clear } = useContentQuery((config) =>
  (props.portal ? getPortalArticle : getArticle)(id.value, config))
const { busy, changeArticle } = useArticleActions()
const editable = computed(() => !props.portal && isEditableArticle(article.value?.status))
const canBindTags = computed(() => editable.value && can('article:bind-tag') && can('tag:list'))
const status = computed(() => articleStatuses[article.value?.status])

const tagDialogVisible = ref(false)
const tagIds = ref([])
const savingTags = ref(false)
const fetchActiveTags = (params, config) => listTags({ ...params, status: 1 }, config)

watch([allowed, id, () => props.portal], () => {
  tagDialogVisible.value = false
  clear()
  if (allowed.value && validId.value) void load()
}, { immediate: true })

function openTagDialog() {
  if (!article.value || !canBindTags.value || savingTags.value) return
  tagIds.value = article.value.tags.map((tag) => tag.id)
  tagDialogVisible.value = true
}

async function saveTags() {
  if (!article.value || savingTags.value) return
  const originalRoute = route.fullPath
  savingTags.value = true
  try {
    await replaceArticleTags(article.value.id, {
      version: article.value.version,
      tagIds: tagIds.value,
    })
    ElMessage.success('文章标签已更新')
    tagDialogVisible.value = false
    if (route.fullPath === originalRoute) await load()
  } catch (failure) {
    if ((failure?.code === 409 || failure?.status === 409) && route.fullPath === originalRoute) {
      tagDialogVisible.value = false
      await load()
    }
  } finally {
    savingTags.value = false
  }
}

async function mutate(action) {
  const originalRoute = route.fullPath
  if (!await changeArticle(article.value, action) || route.fullPath !== originalRoute) return
  if (action === 'delete') await router.replace(back.value)
  else await load()
}
</script>

<template>
  <section class="content-page">
    <RouterLink :to="back" class="content-back"><AppIcon name="arrow" :size="16" />{{ backLabel }}</RouterLink>
    <ContentState :allowed="allowed" :loading="loading" :error="validId ? error : { message: '文章 ID 无效' }" :retry="validId" @retry="load">
      <article v-if="article" class="content-panel detail-paper">
        <header class="paper-header">
          <ElTag v-if="!portal" :type="status?.type" effect="plain">{{ status?.label || article.status }}</ElTag>
          <h1>{{ article.title }}</h1>
          <div class="article-byline">
            <span><AppIcon name="layers" :size="15" />{{ article.categoryName }}</span>
            <span><AppIcon name="users" :size="15" />{{ article.authorName }}</span>
            <span><AppIcon name="clock" :size="15" />{{ portal ? '发布于' : '更新于' }} {{ displayTime(portal ? article.publishedAt : article.updatedAt) }}</span>
            <span v-if="!portal">版本 {{ article.version }}</span>
          </div>
          <p v-if="article.summary" class="paper-summary">{{ article.summary }}</p>
          <div v-if="article.tags.length" class="paper-tags">
            <ElTag v-for="tag in article.tags" :key="tag.id" type="info" effect="plain">
              {{ tag.tagName }}{{ !portal && tag.status === 0 ? '（已停用）' : '' }}
            </ElTag>
          </div>
        </header>
        <div class="article-body">{{ article.content }}</div>
        <footer v-if="editable && (can('article:update') || canBindTags || can('article:submit') || can('article:delete'))" class="paper-actions">
          <ElButton v-if="can('article:update') && can('article:read')" :disabled="busy || savingTags" @click="router.push({ name: 'article-edit', params: { id }, query: route.query })">编辑</ElButton>
          <ElButton v-if="canBindTags" :disabled="busy || savingTags" @click="openTagDialog">调整标签</ElButton>
          <ElButton v-if="can('article:submit')" type="primary" :disabled="busy || savingTags" @click="mutate('submit')">提交审核</ElButton>
          <ElButton v-if="can('article:delete')" type="danger" plain :disabled="busy || savingTags" @click="mutate('delete')">删除</ElButton>
        </footer>
      </article>
    </ContentState>

    <ElDialog
      v-model="tagDialogVisible"
      title="调整文章标签"
      width="min(480px, calc(100vw - 32px))"
      :show-close="!savingTags"
      :close-on-click-modal="false"
      :close-on-press-escape="!savingTags"
      destroy-on-close
    >
      <p class="field-help tag-dialog-tip">
        全量替换当前文章标签（最多 100 项，清空表示移除全部标签）；已下架文章调整标签后将回到草稿状态。
      </p>
      <ContentTagSelect
        v-model="tagIds"
        class="tag-dialog-select"
        :fetch-tags="fetchActiveTags"
        :initial-tags="article?.tags ?? []"
        :disabled="savingTags"
        multiple
      />
      <template #footer>
        <ElButton :disabled="savingTags" @click="tagDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="savingTags" @click="saveTags">保存标签</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.detail-paper { max-width: 920px; width: 100%; margin-inline: auto; overflow: hidden; box-shadow: var(--shadow-sm); }
.paper-header {
  background: linear-gradient(135deg, #eef3e7 0%, #f7f9f2 100%);
  border-bottom: 1px solid var(--line); padding: clamp(22px, 4vw, 38px);
}
.article-byline, .paper-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.paper-header h1 {
  margin: 12px 0 14px; font-family: "Songti SC", "Noto Serif CJK SC", "SimSun", serif;
  font-size: clamp(24px, 3.4vw, 36px); line-height: 1.4; color: var(--green); overflow-wrap: anywhere;
}
.article-byline { color: var(--ink-secondary); font-size: 12.5px; }
.article-byline span { display: inline-flex; align-items: center; gap: 6px; }
.paper-summary {
  margin-top: 18px; padding: 10px 14px; border-inline-start: 3px solid var(--brand);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0; background: rgba(255, 255, 255, 0.6);
  color: var(--ink-secondary); line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13.5px;
}
.paper-tags { margin-top: 16px; gap: 7px; }
.article-body {
  white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9;
  padding: clamp(22px, 4vw, 38px); font-size: 15px; color: var(--ink); min-height: 180px;
}
.paper-actions {
  padding: 16px clamp(22px, 4vw, 38px); border-top: 1px solid #ebf0e6;
  background: var(--surface-subtle); display: flex; gap: 10px; flex-wrap: wrap;
}
.paper-actions .el-button { margin: 0; }
.tag-dialog-tip { margin-bottom: 12px; }
.tag-dialog-select { width: 100%; }
</style>
