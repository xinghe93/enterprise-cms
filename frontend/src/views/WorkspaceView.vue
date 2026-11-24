<script setup>
import { ElTag } from 'element-plus'
import AppIcon from '../components/AppIcon.vue'

const areas = [
  { number: '01', title: '知识内容', text: '沉淀经验，让团队的每一份知识都有迹可循。', links: [{ label: '知识门户', path: '/portal' }, { label: '文章管理', path: '/admin/articles' }, { label: '分类与标签', path: '/admin/categories' }], icon: 'book' },
  { number: '02', title: '审核发布', text: '从草稿到发布，让内容流转清晰、有序。', links: [{ label: '审核与发布', path: '/admin/reviews' }, { label: '操作日志', path: '/admin/logs' }], icon: 'check' },
  { number: '03', title: '组织与权限', text: '连接员工与组织，为协作划定清晰的边界。', links: [{ label: '员工管理', path: '/admin/users' }, { label: '部门管理', path: '/admin/departments' }, { label: '角色与权限', path: '/admin/roles' }], icon: 'users' },
]
</script>

<template>
  <section class="workspace-hero">
    <div class="hero-copy"><p class="eyebrow">KNOWLEDGE WORKSPACE</p><h1>让知识，<br />有序流动。</h1><p class="hero-description">从一份经验，到团队的共同积累。<br />在这里连接内容、组织与协作。</p><RouterLink to="/portal" class="hero-link">前往知识门户 <AppIcon name="arrow" :size="19" /></RouterLink></div>
    <div class="knowledge-art" aria-hidden="true"><span class="art-orbit" /><div class="art-sheet art-sheet-back" /><div class="art-sheet art-sheet-front"><span class="sheet-symbol">E /</span><span class="sheet-line line-wide" /><span class="sheet-line" /><span class="sheet-line line-short" /><div class="sheet-grid"><span /><span /><span /></div><span class="sheet-foot">KNOWLEDGE, CONNECTED.</span></div><span class="art-caption">CREATE. CONNECT. SHARE.</span></div>
  </section>

  <section aria-labelledby="workspace-entry-title" class="workspace-modules">
    <div class="section-heading"><div><p class="eyebrow">EXPLORE YOUR WORKSPACE</p><h2 id="workspace-entry-title">从这里开始</h2></div><ElTag effect="plain" round>功能逐步接入中</ElTag></div>
    <div class="area-grid">
      <article v-for="area in areas" :key="area.number" class="area-card">
        <div class="area-top"><span class="area-icon"><AppIcon :name="area.icon" :size="24" /></span><span class="area-number">{{ area.number }}</span></div>
        <h3>{{ area.title }}</h3><p>{{ area.text }}</p>
        <div class="area-links"><RouterLink v-for="link in area.links" :key="link.path" :to="link.path">{{ link.label }}<AppIcon name="arrow" :size="16" /></RouterLink></div>
      </article>
    </div>
  </section>
  <div class="workspace-note"><AppIcon name="layers" :size="20" /><p>一个工作空间，贯穿知识创建、审核、发布与阅读。</p><span>共同积累 · 持续生长</span></div>
</template>
