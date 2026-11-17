# @RequirePermission 使用规范（B 模块）

> 适用范围：全体后端成员（A/C/D）。权限码目录以 OpenAPI `x-permission-catalog` 为唯一权威，禁止自造权限码。

## 1. 基本用法

在 Controller 方法上标注，切面会在方法执行前完成鉴权：

```java
@GetMapping("/{id}")
@RequirePermission("role:read")
public ApiResponse<RoleView> detail(@PathVariable String id) { ... }
```

## 2. 校验流程与响应码

| 请求状态 | 响应 | 来源 |
| --- | --- | --- |
| 无 Token / Token 失效 / 撤销 / 用户停用 | **401** | A 的 `CurrentUserProvider` |
| 已登录但所属角色无该权限码（或权限已停用） | **403** | B 的授权快照比对 |
| 注解 `value` 留空（使用错误） | **500** | 失败关闭，防止漏填后接口裸奔 |

## 3. 规则

1. **权限码必须与 OpenAPI 该接口的 `x-permission` 完全一致**，一个方法只挂一个 `@RequirePermission`。
2. `value` 可填多个权限码，语义为**任一满足即放行（OR）**；需要"同时满足多个权限"的场景应拆分接口，不要在注解里叠要求。
3. `PUBLIC` 端点不挂注解；仅要求登录的 `AUTHENTICATED` 端点也不挂（认证过滤器已保证 401）。
4. 授权快照**每次请求实时计算、不缓存**——角色/权限变更对新请求立即生效，调用方无需做任何失效处理。
5. 本注解只能标注在 Spring MVC Controller 的方法上（经 AOP 代理拦截），Service 层内部调用不需要也不生效。

## 4. 排查清单（403 时）

1. 该用户在 `sys_user_role` 里是否关联了角色？
2. 关联的角色在 `sys_role` 里是否启用（status=1）？
3. 该权限码在 `sys_permission` 里是否启用，且在 `sys_role_permission` 中与角色关联？
4. 注解里的码是否和 OpenAPI 一致（大小写、拼写）？

以上 1–3 可直接查库核对；种子基线见 `database/002_seed_permission_baseline.sql`。

## 5. 联调自测

未登录访问：`curl -i http://localhost:8080/api/admin/roles` → 401
带员工 Token 访问管理端：`curl -i -H "Authorization: Bearer <token>" ...` → 403
带 ADMIN Token：→ 200
