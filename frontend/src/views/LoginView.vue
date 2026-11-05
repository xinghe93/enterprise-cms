<script setup>
import { ElButton, ElTag } from 'element-plus'
import AppIcon from '../components/AppIcon.vue'
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <section class="empty-panel standalone-panel"><div class="empty-illustration"><AppIcon name="shield" :size="44" /></div><ElTag effect="plain" round>待接入</ElTag><h1>账号登录</h1><p>登录功能接入后，可使用企业账号访问知识与协作服务。</p><ElButton @click="router.push('/workspace')">返回工作空间</ElButton></section>
</template>
