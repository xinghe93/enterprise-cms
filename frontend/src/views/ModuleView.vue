<script setup>
import { ElButton, ElTag } from 'element-plus'
import AppIcon from '../components/AppIcon.vue'
import { useRouter } from 'vue-router'
const router = useRouter()
defineProps({ entry: { type: Object, required: true } })
</script>

<template>
  <section class="module-page">
    <div class="page-heading"><p class="eyebrow">ENTERPRISE KNOWLEDGE</p><h1>{{ entry.title }}</h1><p>{{ entry.description }}</p></div>
    <div class="empty-panel"><div class="empty-illustration"><AppIcon :name="entry.icon" :size="44" /></div><ElTag effect="plain" round>待接入</ElTag><h2>{{ entry.title }}即将开启</h2><p>当前已预留页面入口，功能完成后将在这里提供服务。</p><ElButton @click="router.push('/workspace')">返回工作空间</ElButton></div>
  </section>
</template>
