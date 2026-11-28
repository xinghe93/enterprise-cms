import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router/index.js'
import { pinia } from './stores/index.js'
// 统一在应用入口先加载 Element Plus 完整样式（包含 Dialog/Overlay 等全部组件样式），
// 确保 main.css 中的森林绿主题变量与组件覆写稳定生效；业务页面不再按需重复引入
// theme-chalk/*.css，避免懒加载块晚于 main.css 注入并覆盖主题。
import 'element-plus/dist/index.css'
import './styles/main.css'
import './styles/organization.css'

createApp(App).use(pinia).use(router).mount('#app')
