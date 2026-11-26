<script setup>
import { ElTag } from 'element-plus'
import AppIcon from '../components/AppIcon.vue'
import { useSessionStore } from '../stores/session.js'

const session = useSessionStore()

const areas = [
  {
    number: '01', title: '知识内容与门户',
    text: '沉淀团队经验与规章制度，统一维护分类树、标签与知识文档。',
    links: [
      { label: '知识门户', path: '/portal' },
      { label: '文章管理', path: '/admin/articles' },
      { label: '分类与标签', path: '/admin/categories' },
    ],
    icon: 'book',
  },
  {
    number: '02', title: '审核发布与审计',
    text: '规范草稿提交、审核流转、发布下架，完整追溯每一次关键操作。',
    links: [
      { label: '审核与发布', path: '/admin/reviews' },
      { label: '操作日志', path: '/admin/logs' },
    ],
    icon: 'check',
  },
  {
    number: '03', title: '组织与权限控制',
    text: '维护员工账号、部门层级与角色权限矩阵，确保协作边界清晰。',
    links: [
      { label: '员工管理', path: '/admin/users' },
      { label: '岗位分配', path: '/admin/assignments' },
      { label: '部门管理', path: '/admin/departments' },
      { label: '角色与权限', path: '/admin/roles' },
    ],
    icon: 'shield',
  },
]
</script>

<template>
  <section class="workspace-hero">
    <div class="hero-copy">
      <p class="eyebrow">KNOWLEDGE WORKSPACE</p>
      <h1>让知识，<br />有序流动。</h1>
      <p class="hero-description">
        从一份经验，到团队的共同积累。<br />
        在这里统一完成企业知识的创作、审核、发布与检索。
      </p>
      <div class="hero-actions">
        <RouterLink to="/portal" class="hero-link">前往知识门户 <AppIcon name="arrow" :size="17" /></RouterLink>
        <RouterLink v-if="!session.isAuthenticated" to="/login" class="hero-link-ghost">登录企业账号</RouterLink>
        <RouterLink v-else to="/admin/articles" class="hero-link-ghost">进入文章管理</RouterLink>
      </div>
    </div>
    <div class="knowledge-art" aria-hidden="true">
      <span class="art-orbit" />
      <div class="art-sheet art-sheet-back" />
      <div class="art-sheet art-sheet-front">
        <span class="sheet-symbol">E /</span>
        <span class="sheet-line line-wide" />
        <span class="sheet-line" />
        <span class="sheet-line line-short" />
        <div class="sheet-grid"><span /><span /><span /></div>
        <span class="sheet-foot">KNOWLEDGE, CONNECTED.</span>
      </div>
      <span class="art-caption">CREATE. CONNECT. SHARE.</span>
    </div>
  </section>

  <section aria-labelledby="workspace-entry-title" class="workspace-modules">
    <div class="section-heading">
      <div>
        <p class="eyebrow">EXPLORE YOUR WORKSPACE</p>
        <h2 id="workspace-entry-title">核心业务工作台</h2>
      </div>
      <ElTag type="success" effect="plain" round>全模块已就绪</ElTag>
    </div>
    <div class="area-grid">
      <article v-for="area in areas" :key="area.number" class="area-card">
        <div class="area-top">
          <span class="area-icon"><AppIcon :name="area.icon" :size="22" /></span>
          <span class="area-number">{{ area.number }}</span>
        </div>
        <h3>{{ area.title }}</h3>
        <p>{{ area.text }}</p>
        <div class="area-links">
          <RouterLink v-for="link in area.links" :key="link.path" :to="link.path">
            {{ link.label }}<AppIcon name="arrow" :size="15" />
          </RouterLink>
        </div>
      </article>
    </div>
  </section>
  <div class="workspace-note">
    <AppIcon name="layers" :size="18" />
    <p>一个统一工作空间，贯穿知识创建、审核发布、权限管控与操作审计。</p>
    <span>共同积累 · 持续生长</span>
  </div>
</template>
