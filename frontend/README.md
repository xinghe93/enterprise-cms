# EnterpriseCMS 前端

Vue 3 + Vite + Element Plus + Vue Router + Pinia + Axios。知识门户与管理后台共用一个工程，A/B/C/D 四个业务模块页面均已接入完成。

## 启动

建议使用 Node.js 24 LTS 和 npm，仓库包含 `.nvmrc`。也支持 Node.js 22.12 及以上的 22.x。

```bash
cd frontend
nvm use # 使用 nvm 时执行；其他方式安装 Node 可跳过
npm ci
cp .env.example .env.local
npm run dev
```

打开终端显示的 `http://127.0.0.1:5173`。联调完整业务需启动后端服务、MySQL 与 Redis（详见仓库根目录文档与 `docs/四人协作开发指南 V1.0.md`）。

```bash
npm test        # Node 原生测试：响应、错误、权限、表单校验及业务工具
npm run build   # 生产产物输出到 dist/
npm run preview # 仅本地预览构建结果，默认 4173
```

## 环境与代理

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | 浏览器 API 前缀；业务模块路径不要重复添加 `/api` |
| `API_PROXY_TARGET` | `http://127.0.0.1:8080` | 仅开发服务器使用的后端地址，完整保留 `/api` 路径 |

本地修改 `.env.local` 后重启 Vite。例如浏览器请求 `/api/admin/users`，开发服务器转发到 `http://127.0.0.1:8080/api/admin/users`。`VITE_` 变量进入浏览器构建产物，不可存放密码或 Token。

生产部署：以 `dist/` 作为静态站点目录，为浏览器路由配置 history 回退（不存在的页面路径回退到 `index.html`）；`/api/` 优先反向代理到后端，不能回退到首页。Vite 开发代理不进入生产构建。默认部署在域名根路径，子路径部署需同步设置 Vite `base`、静态资源路径和服务器回退规则。

## 目录与模块分工

| 位置 | 职责 |
| --- | --- |
| `src/layouts/AppLayout.vue` | 公共侧栏、顶栏、移动端导航、权限过滤菜单与内容区 |
| `src/router/index.js`、`navigation.js`、`viewComponents.js` | 路由守卫、模块导航权限声明与懒加载页面映射 |
| `src/views/WorkspaceView.vue`、`LoginView.vue` | 按有效权限展示的工作空间首页、登录退出与安全回跳 |
| `src/views/user/` | A 模块：员工管理（`AccountView`）、岗位分配（`AssignmentView`）、部门管理（`DepartmentView`） |
| `src/views/role/RolePermissionView.vue` | B 模块：角色与权限项管理、角色权限全量分配（含并发基线校验） |
| `src/views/content/` | C 模块：知识门户、文章管理/详情/编辑器、分类树与标签目录 |
| `src/views/ReviewListView.vue`、`review/PublishPanel.vue`、`LogListView.vue` | D 模块：待审核队列、通过/驳回、审核历史、批量发布/下架、操作日志 |
| `src/api/client.js`、`src/api/index.js` | Axios 工厂、统一响应解包、错误归一化、旧会话 401 隔离与自动跳转登录 |
| `src/stores/session.js` | `sessionStorage` 令牌持久化、刷新自动恢复用户信息与实时有效权限编码 |
| `src/styles/main.css`、`organization.css` | 统一森林绿主题与业务页面样式（Element Plus 完整样式统一在 `main.js` 引入） |

## 接口调用约定

以 `docs/api/enterprise-cms.openapi.json` 及《接口与协作约定 V1.0》为准：

```js
import { api } from '../api/index.js'

// 已解包为 { records, total, current, size }，无需再次取 response.data。
export const getUsers = (params) => api.get('/admin/users', { params })
```

仅在组件挂载或交互时发起请求，并捕获拒绝的 Promise 以结束加载状态。错误默认由公共层提示，组件 catch 中不要重复 ElMessage；需要自行在表单内提示错误的接口使用 `{ silent: true }`，错误依然会抛出。组件卸载时可用 `AbortController` 取消在途请求，取消不会弹出错误。

公共层按 `{ code, message, data, timestamp }` 解包，支持成功 `data: null`，业务 ID 统一保留十进制字符串（禁止 `Number(id)`）。业务时间维持契约规定的本地日期时间字符串，不与外层 UTC `timestamp` 混用。

## 认证与权限机制

1. 登录后将不透明 Bearer Token 存入当前标签页的 `sessionStorage`（刷新自动经 `/api/auth/me` 与 `/api/auth/permissions` 恢复会话，关闭标签页自动清理，多标签页互不串号；不写入 `localStorage`，无滑动续期与刷新令牌）。
2. 所有受保护路由与侧栏菜单均以 `/api/auth/permissions` 返回的有效权限编码为准；路由切换与同页点击菜单会自动刷新权限快照，角色/权限调整无需重新登录即可生效。
3. 请求自动携带 `Authorization: Bearer ...`；401 清空本地会话并携带安全站内 `redirect` 跳转 `/login`（并按请求发起时的令牌快照隔离切号前的迟到 401）；403 仅提示错误。后端始终独立执行 `@RequirePermission` 与数据范围鉴权。

## 验收

改动后在 `frontend` 目录执行 `npm test` 和 `npm run build`，并结合后端与不同角色账号在浏览器验证登录回跳、菜单/按钮权限、文章全生命周期流转、审核发布及操作日志检索。
