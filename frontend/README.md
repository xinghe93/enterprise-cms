# EnterpriseCMS 前端

Vue 3 + Vite + Element Plus + Vue Router + Pinia + Axios。知识门户与管理后台共用一个工程，目前提供公共骨架，业务页面由各模块负责人继续实现。

## 启动

建议使用 Node.js 24 LTS 和 npm，仓库包含 `.nvmrc`。也支持 Node.js 22.12 及以上的 22.x。

```bash
cd frontend
nvm use # 使用 nvm 时执行；其他方式安装 Node 可跳过
npm ci
cp .env.example .env.local
npm run dev
```

打开终端显示的 `http://127.0.0.1:5173`。骨架启动无需数据库、Redis 或后端；占位页面不会自动请求业务接口。后端启动步骤与测试数据属于后续开发环境准备工作。

```bash
npm test        # Node 原生测试：响应、错误、令牌及会话状态
npm run build  # 生产产物输出到 dist/
npm run preview # 仅本地预览构建结果，默认 4173
```

## 环境与代理

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | 浏览器 API 前缀；业务模块路径不要重复添加 `/api` |
| `API_PROXY_TARGET` | `http://127.0.0.1:8080` | 仅开发服务器使用的后端地址，完整保留 `/api` 路径 |

本地修改 `.env.local` 后重启 Vite。例如浏览器请求 `/api/admin/users`，开发服务器转发到 `http://127.0.0.1:8080/api/admin/users`。`VITE_` 变量进入浏览器构建产物，不可存放密码或 Token。

生产部署：以 `dist/` 作为静态站点目录，为浏览器路由配置 history 回退（不存在的页面路径回退到 `index.html`）；`/api/` 优先反向代理到后端，不能回退到首页。Vite 开发代理不进入生产构建。默认部署在域名根路径，子路径部署需同步设置 Vite `base`、静态资源路径和服务器回退规则。

## 公共基础与分工

| 位置 | 职责 |
| --- | --- |
| `src/layouts/AppLayout.vue` | 公共侧栏、顶栏、移动端导航和内容区 |
| `src/router/index.js` | 路由与懒加载页面 |
| `src/router/navigation.js` | 模块入口和成员归属 |
| `src/views/WorkspaceView.vue` | 工作空间首页 |
| `src/views/ModuleView.vue` | 共用业务占位页，开发时替换对应路由组件 |
| `src/api/client.js` | Axios 工厂、统一响应和错误归一化，可独立测试 |
| `src/api/index.js` | 供业务模块导入的实例、Element Plus 提示及 401 处理 |
| `src/stores/session.js` | 内存会话与后端有效权限编码 |
| `src/styles/main.css` | 公共主题、布局及响应式样式 |

A：登录、员工、部门；B：角色与权限；C：门户、文章、分类和标签；D：审核发布和日志。后续分别增加 `views/user`、`views/permission`、`views/content`、`views/review` 等目录，新增业务 API 文件，避免集中修改公共布局。分类与标签、角色与权限目前是组合入口，各负责人可在内部增加子页面。

## 接口调用

以 `docs/api/enterprise-cms.openapi.json` 及接口约定为准。模块 API 示例（尚无对应业务页面）：

```js
import { api } from '../api/index.js'

// 已解包为 { records, total, current, size }，无需再次取 response.data。
export const getUsers = (params) => api.get('/admin/users', { params })
```

仅在组件挂载或交互时发起请求，并捕获拒绝的 Promise 以结束加载状态。错误默认由公共层提示，组件 catch 中不要重复 ElMessage；需要自行提示的表单使用 `{ silent: true }`，错误依然会抛出。组件卸载时可以用 `AbortController` 取消请求，取消不会弹出错误。

公共层按 `{ code, message, data, timestamp }` 解包，支持成功 `data: null`，保留字符串 ID，不能使用 `Number(id)`。业务时间维持契约规定的本地日期时间字符串，外层 UTC timestamp 不与其混用。

## 登录与权限的接入边界

当前 `/login` 只有入口说明，没有模拟账号、登录提交或登录成功状态；所有模块页面都是占位。业务开发前由 A/B 完成真实认证与权限接入：

1. A 在登录成功后将 `accessToken` 写入 `session.setToken()`；使用 `/auth/me` 数据调用 `setUser()`。
2. B 使用 `/auth/permissions` 返回的编码数组调用 `setPermissions()`，接入路由守卫、菜单与按钮可见性。不能从角色名称推断有效权限。
3. 请求自动携带 `Authorization: Bearer ...`；401 清空用户、令牌及权限并跳转 `/login`；403 只提示错误。退出登录请求成功后调用 `session.clear()` 并跳转登录页。

令牌当前只存内存，刷新页面后需重新登录，没有本地持久化、自动续期或刷新令牌。公共层不自动重试写操作。当前不提供业务路由鉴权守卫，菜单入口也未按权限过滤；这些必须在真实业务接入时完成，后端仍须独立强制鉴权。

## 验收

启动后验证首页、侧栏切换、直接访问并刷新 `/admin/articles`、未知地址显示 404，手机宽度下可展开/关闭导航。后端业务完成后，再用 Apifox 核对成功/400/401/403/409 等响应，再联调对应页面。
