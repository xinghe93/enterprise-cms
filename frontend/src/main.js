import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router/index.js'
import { pinia } from './stores/index.js'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-tag.css'
import 'element-plus/theme-chalk/el-message.css'
// ElDialog 由组织管理页直接使用，按需引入时必须同时带上对话框与遮罩样式，
// 否则弹窗无定位/无遮罩、背景仍可点击（PR #41 审查 P2）。
import 'element-plus/theme-chalk/el-dialog.css'
import 'element-plus/theme-chalk/el-overlay.css'
import './styles/main.css'
import './styles/organization.css'

createApp(App).use(pinia).use(router).mount('#app')
