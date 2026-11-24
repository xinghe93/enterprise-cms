<script setup>
import { ElButton } from 'element-plus'
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <section class="empty-panel standalone-panel"><p class="error-number">404</p><h1>这页知识还没有归档</h1><p>页面不存在，请检查地址或返回工作空间。</p><ElButton type="primary" @click="router.push('/workspace')">返回工作空间</ElButton></section>
</template>
