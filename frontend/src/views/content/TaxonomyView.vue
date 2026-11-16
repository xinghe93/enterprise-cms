<script setup>
import { computed, ref, watch } from 'vue'
import { ElTabPane, ElTabs } from 'element-plus'
import { useSessionStore } from '../../stores/session.js'
import ContentState from './ContentState.vue'
import TaxonomyPanel from './TaxonomyPanel.vue'
import 'element-plus/es/components/tabs/style/css.mjs'
import './content.css'

const session = useSessionStore()
const canSeeCategories = computed(() => session.isAuthenticated && session.hasPermission('category:tree'))
const canSeeTags = computed(() => session.isAuthenticated && session.hasPermission('tag:list'))
const tab = ref('category')

watch([canSeeCategories, canSeeTags], ([categories, tags]) => {
  if (tab.value === 'category' && !categories && tags) tab.value = 'tag'
  if (tab.value === 'tag' && !tags && categories) tab.value = 'category'
}, { immediate: true })
</script>

<template>
  <section class="content-page">
    <header class="content-heading">
      <div class="page-heading">
        <h1>分类与标签</h1>
      </div>
    </header>

    <ContentState :allowed="canSeeCategories || canSeeTags">
      <ElTabs v-model="tab">
        <ElTabPane v-if="canSeeCategories" name="category" label="分类树">
          <TaxonomyPanel v-if="tab === 'category'" kind="category" />
        </ElTabPane>
        <ElTabPane v-if="canSeeTags" name="tag" label="标签目录">
          <TaxonomyPanel v-if="tab === 'tag'" kind="tag" />
        </ElTabPane>
      </ElTabs>
    </ContentState>
  </section>
</template>
