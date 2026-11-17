<script setup>
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElTag } from 'element-plus'
import { getArticle, getPortalArticle } from '../../api/content.js'
import AppIcon from '../../components/AppIcon.vue'
import { useSessionStore } from '../../stores/session.js'
import ContentState from './ContentState.vue'
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
const back = computed(() => ({ name: props.portal ? 'portal' : 'articles', query: route.query }))
const { data: article, loading, error, load, clear } = useContentQuery((config) =>
  (props.portal ? getPortalArticle : getArticle)(id.value, config))
const { busy, changeArticle } = useArticleActions()
const editable = computed(() => !props.portal && isEditableArticle(article.value?.status))
const status = computed(() => articleStatuses[article.value?.status])

watch([allowed, id, () => props.portal], () => {
  clear()
  if (allowed.value && validId.value) void load()
}, { immediate: true })

async function mutate(action) {
  const originalRoute = route.fullPath
  if (!await changeArticle(article.value, action) || route.fullPath !== originalRoute) return
  if (action === 'delete') await router.replace(back.value)
  else await load()
}
</script>

<template>
  <section class="content-page">
    <RouterLink :to="back" class="content-back"><AppIcon name="arrow" :size="16" />{{ portal ? '返回知识门户' : '返回文章管理' }}</RouterLink>
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
        <footer v-if="editable && (can('article:update') || can('article:submit') || can('article:delete'))" class="paper-actions">
          <ElButton v-if="can('article:update') && can('article:read')" :disabled="busy" @click="router.push({ name: 'article-edit', params: { id }, query: route.query })">编辑</ElButton>
          <ElButton v-if="can('article:submit')" type="primary" :disabled="busy" @click="mutate('submit')">提交审核</ElButton>
          <ElButton v-if="can('article:delete')" type="danger" plain :disabled="busy" @click="mutate('delete')">删除</ElButton>
        </footer>
      </article>
    </ContentState>
  </section>
</template>

<style scoped>
.detail-paper { max-width: 920px; margin-inline: auto; overflow: hidden; }
.paper-header { background: #eef2e7; border-bottom: 1px solid var(--line); padding: clamp(20px, 5vw, 43px); }
.article-byline, .paper-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.paper-header h1 { margin: 12px 0 16px; font-family: "Songti SC", "Noto Serif CJK SC", "SimSun", serif; font-size: clamp(25px, 4vw, 42px); line-height: 1.4; overflow-wrap: anywhere; }
.article-byline { color: #526755; font-size: 12px; }
.article-byline span { display: inline-flex; align-items: center; gap: 6px; }
.paper-summary { margin-top: 19px; padding-inline-start: 12px; border-inline-start: 2px solid #98ae8d; color: #526755; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.paper-tags { margin-top: 18px; gap: 7px; }
.article-body { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; padding: clamp(20px, 5vw, 43px); font-size: 15px; min-height: 150px; }
.paper-actions { padding: 16px clamp(20px, 5vw, 43px); border-top: 1px solid var(--line); display: flex; gap: 8px; flex-wrap: wrap; }
.paper-actions .el-button { margin: 0; }
</style>
