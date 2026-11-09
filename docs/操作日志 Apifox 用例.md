# 操作日志模块 Apifox 测试用例（D 模块）

**状态（2026-11-09 第二轮）：** 查询接口 18 条用例可在当前分支直接执行（L-01～L-16 为查询契约，L-17/L-18 为未知查询参数 400）；审计切面 6 条用例依赖真实写接口（审核/发布等）合入后执行，已标注。权限注解尚未合入（B 的权限数据层已随 PR #21 进入 dev），所有用例暂不需要 Token；B 交付注解后按第 0.4 节调整。

- 接口说明：见 [操作日志说明](操作日志说明.md)
- 契约依据：[接口与协作约定 V1.0](<接口与协作约定 V1.0.md>) 第 3.5、8 节；[OpenAPI](api/enterprise-cms.openapi.json)

## 0. 前置准备

### 0.1 启动后端

依赖：Docker 中的 `mysql8`（MySQL 8.4）容器已启动、`enterprise_cms` 库已导入 `database/001_init.sql`。容器未启动时先执行 `docker start mysql8`。

```powershell
cd "D:\大三第一学期\JAVA框架技术（一）\课程设计\enterprise-cms\backend"
$env:JAVA_HOME = "E:\Java\jdk-25.0.5.0.3"
$env:SPRING_DATASOURCE_PASSWORD = "<本机 MySQL root 密码>"
$env:TZ = "Asia/Shanghai"
mvn -B -DskipTests spring-boot:run
```

启动成功标志：日志出现 `Started ... in ... seconds`。默认端口 8080，若改为其他端口，同步修改 Apifox 的 `baseUrl`。

**密码纪律：** `SPRING_DATASOURCE_PASSWORD` 只用环境变量注入，真实密码不得写进本文档、用例脚本、提交记录或截图；需要共享的示例一律写占位符。

### 0.2 Apifox 环境变量

| 变量 | 值 | 说明 |
| --- | --- | --- |
| `baseUrl` | `http://127.0.0.1:8080` | 本地后端地址 |
| `token` | 占位，按实际登录结果填写 | B 合入权限后使用，`Authorization: Bearer {{token}}` |
| `testUserId` | `990101` | 种子数据中的演示用户 ID |

### 0.3 测试数据（可重复执行）

在 `enterprise_cms` 库执行。固定 ID 便于断言，重复执行不会叠加：

```sql
-- 1. 演示用户（固定 ID，FK sys_operation_log.user_id 需要）
INSERT INTO sys_user (id, username, password_hash, real_name, department_id, status)
VALUES (990101, 'log_demo', 'not-a-real-password-hash', '日志演示用户', NULL, 1)
ON DUPLICATE KEY UPDATE real_name = '日志演示用户', status = 1;

-- 2. 三条日志：SUCCESS + FAILURE + user_id 为 NULL，时间固定
DELETE FROM sys_operation_log WHERE id IN (990001, 990002, 990003);
INSERT INTO sys_operation_log (id, user_id, operation, request_uri, http_method, result, duration_ms, created_at) VALUES
(990001, 990101, 'article.approve', '/api/admin/articles/1/approve', 'POST', 'SUCCESS', 12, '2026-11-08 10:00:00.000'),
(990002, 990101, 'article.reject',  '/api/admin/articles/2/reject',  'POST', 'FAILURE',  8, '2026-11-08 10:05:00.000'),
(990003, NULL,   'article.publish', '/api/admin/articles/3/publish', 'POST', 'SUCCESS', 15, '2026-11-08 11:00:00.000');
```

清理（用例执行完后）：

```sql
DELETE FROM sys_operation_log WHERE id IN (990001, 990002, 990003);
DELETE FROM sys_user WHERE id = 990101;
```

**基线假设：** 测试库 `sys_operation_log` 原本为空，此时默认分页 `total=3`。若库中已有其他日志，保留种子数据不变，总数类断言改为"执行前后差值 +3"，或统一加时间条件 `createdFrom=2026-11-08T00:00:00&createdTo=2026-11-09T00:00:00` 后再核对 3 条。

### 0.4 当前鉴权状态

当前分支没有权限注解，`/api/admin/logs` 可直接访问，不带 `Authorization` 也返回 200。B 合入 `@RequirePermission` 后：

- 未带 Token 的请求预期 401（message：请先登录）；
- 带非审核/管理员 Token 的请求预期 403（message：无访问权限）；
- 正常用例统一带 `Authorization: Bearer {{token}}`。

## 1. 用例总览

| 编号 | 类型 | 接口 | 目的 |
| --- | --- | --- | --- |
| L-01 | 正常 | GET /api/admin/logs | 默认分页、排序、ID 字符串、时间格式 |
| L-02 | 正常 | GET /api/admin/logs | userId 精确过滤 |
| L-03 | 正常 | GET /api/admin/logs | result 精确过滤 |
| L-04 | 正常 | GET /api/admin/logs | 时间区间左闭右开 |
| L-05 | 正常 | GET /api/admin/logs | operation 去空白后精确匹配 |
| L-06 | 边界 | GET /api/admin/logs | size=101 拒绝 |
| L-07 | 错误参数 | GET /api/admin/logs | result 非法枚举 |
| L-08 | 错误参数 | GET /api/admin/logs | 时间起点等于终点 |
| L-09 | 错误参数 | GET /api/admin/logs | current=0 |
| L-10 | 错误参数 | GET /api/admin/logs | size=0 |
| L-11 | 正常 | GET /api/admin/logs/{id} | 详情含操作人显示名 |
| L-12 | 正常 | GET /api/admin/logs/{id} | user_id 为 NULL 的详情 |
| L-13 | 404 | GET /api/admin/logs/{id} | 记录不存在 |
| L-14 | 错误参数 | GET /api/admin/logs/{id} | id=0 |
| L-15 | 错误参数 | GET /api/admin/logs/{id} | 非数字 id |
| L-16 | 边界 | GET /api/admin/logs | 越界页 records=[] 且 total 真实 |
| L-17 | 错误参数 | GET /api/admin/logs | 未知查询参数（列表） |
| L-18 | 错误参数 | GET /api/admin/logs/{id} | 未知查询参数（详情） |
| A-01～A-06 | 审计 | 写接口 + 日志查询 | 见第 3 节，待业务接口合入 |

## 2. 查询接口用例

所有请求前缀 `{{baseUrl}}`，不带 `Authorization`（见 0.4）。成功响应必须同时满足：HTTP 200、`code=200`、`message="操作成功"`、`data` 非空、`timestamp` 以 `Z` 结尾。

| 编号 | 请求 | 预期状态与断言 |
| --- | --- | --- |
| L-01 | `GET /api/admin/logs` | 200；`data.total=3`；`data.current=1`；`data.size=10`；`records` 长度 3；顺序为 `990003`、`990002`、`990001`（`created_at DESC`）；`records[0].id="990003"` 且匹配 `^\d+$`；`records[0].userId=null`；`records[0].createdAt="2026-11-08T11:00:00.000"` 且匹配 `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$` |
| L-02 | `GET /api/admin/logs?userId=990101` | 200；`data.total=2`；每条 `userId="990101"`；每条 `userName="日志演示用户"` |
| L-03 | `GET /api/admin/logs?result=FAILURE` | 200；`data.total=1`；`records[0].id="990002"`；`operation="article.reject"` |
| L-04 | `GET /api/admin/logs?createdFrom=2026-11-08T10:00:00&createdTo=2026-11-08T11:00:00` | 200；`data.total=2`（10:00:00 含、11:00:00 不含）；返回 `990002`、`990001` |
| L-05 | `GET /api/admin/logs?operation=%20article.approve%20` | 200；`data.total=1`；`records[0].id="990001"`（首尾空白被去除后精确匹配） |
| L-06 | `GET /api/admin/logs?size=101` | 400；`code=400`；`message="每页数量必须在 1 到 100 之间"`；`data=null` |
| L-07 | `GET /api/admin/logs?result=OTHER` | 400；`code=400`；`message="结果只能是 SUCCESS 或 FAILURE"`；`data=null` |
| L-08 | `GET /api/admin/logs?createdFrom=2026-11-08T10:00:00&createdTo=2026-11-08T10:00:00` | 400；`code=400`；`message="起始时间必须早于结束时间"`；`data=null` |
| L-09 | `GET /api/admin/logs?current=0` | 400；`code=400`；`message="页码必须大于零"`；`data=null` |
| L-10 | `GET /api/admin/logs?size=0` | 400；`code=400`；`message="每页数量必须在 1 到 100 之间"`；`data=null` |
| L-11 | `GET /api/admin/logs/990001` | 200；`data.id="990001"`；`data.userId="990101"`；`data.userName="日志演示用户"`；`operation="article.approve"`；`requestUri="/api/admin/articles/1/approve"`；`httpMethod="POST"`；`result="SUCCESS"`；`durationMs=12` |
| L-12 | `GET /api/admin/logs/990003` | 200；`data.userId=null`；`data.userName=null`（LEFT JOIN 无用户）；`operation="article.publish"` |
| L-13 | `GET /api/admin/logs/999999` | 404；`code=404`；`message="日志不存在"`；`data=null` |
| L-14 | `GET /api/admin/logs/0` | 400；`code=400`；`message="日志 ID 必须大于零"`；`data=null` |
| L-15 | `GET /api/admin/logs/abc` | 400；`code=400`；`message="请求参数错误"`；`data=null` |
| L-16 | `GET /api/admin/logs?current=99` | 200；`records=[]`；`data.total=3`；`data.current=99`（越界页不报错，总数保留真实值） |
| L-17 | `GET /api/admin/logs?foo=bar` | 400；`code=400`；`message="未知的查询参数：foo"`；`data=null`；Service 不应被调用 |
| L-18 | `GET /api/admin/logs/990001?token=x` | 400；`code=400`；`message="未知的查询参数：token"`；`data=null` |

补充：`/api/admin/logs?operation=%20%20%20`（三个空格）应返回 200 且不筛选（total=3），用于验证空白操作名归一化，可与 L-05 合并为一个场景步骤。

## 3. 审计切面用例（待业务写接口合入后执行）

前置：审核/发布等写接口已加 `@AuditLog` 并合入 dev；每条用例执行前后用 SQL 记录 `SELECT COUNT(*) FROM sys_operation_log WHERE operation = '...'` 作为基线。SQL 断言直接使用测试库，不通过接口构造。

| 编号 | 场景 | 操作 | 断言 |
| --- | --- | --- | --- |
| A-01 | 成功写 SUCCESS | 调用 `POST /api/admin/articles/{id}/approve`（合法待审核文章、正确 version） | 接口 200；日志表新增 1 条 `operation="article.approve"`、`result="SUCCESS"`、`http_method="POST"`、`request_uri` 为该接口路径、`duration_ms >= 0`；`user_id` 与登录用户一致（A 合入后） |
| A-02 | 业务失败写 FAILURE | 用旧 `version` 再次调用 approve，预期 409 | 接口 409；日志表新增 1 条 `FAILURE`，且该次调用**没有**新增 `SUCCESS`；原业务错误响应仍返回给前端 |
| A-03 | 回滚不留 SUCCESS | 构造业务事务提交前失败（如文章状态不是 `PENDING_REVIEW`、或依赖的审核记录写入失败） | 业务返回 4xx/409；该次调用只出现 `FAILURE`，不出现 `SUCCESS`；`cms_review` 无新增记录 |
| A-04 | requestUri 不含查询参数 | 调用带查询参数的写接口（如 C 的逻辑删除 `DELETE /api/admin/articles/{id}?version=N`） | 日志 `request_uri` 只含路径，不含 `?version=N`；`http_method` 为实际方法 |
| A-05 | 不记录敏感信息 | 调用登录接口（应不产生通用审计记录）；检查日志表与接口返回 | `operation` 无 `auth.login` 记录；所有日志的 `operation`、`request_uri` 不含密码、Token；接口响应不回显密码或 Token |
| A-06 | 未登录 userId 为 null | 当前分支（无鉴权）直接调用带 `@AuditLog` 的写接口 | 新增日志 `user_id` 为 NULL；A/B 合入后此用例改为期望 401 且不新增日志 |

审计用例当前无法执行的准确原因：本分支只实现了日志查询与切面能力，审核/发布等业务接口属于后续提交（依赖 C 的 `ArticleWorkflowService` 和 A/B 的身份、权限契约），没有可调用的写接口。

## 4. 断言与判定规范

1. 外层响应固定四字段 `code`、`message`、`data`、`timestamp`；失败时 `data=null`，成功时分页结构为 `records/total/current/size`。
2. HTTP 状态码必须与 `code` 一致（200/400/401/403/404/409/500）。
3. 业务 ID 一律按 JSON 字符串断言（`^\d+$`），不能按数字比较。
4. 业务时间格式 `yyyy-MM-dd'T'HH:mm:ss.SSS`，不带时区偏移；外层 `timestamp` 为 UTC，以 `Z` 结尾。
5. 列表断言顺序：`created_at DESC, id DESC`；同一时间用 `id` 兜底。
6. 不用 `message` 文本作为业务分支判断，只用于断言提示语是否符合契约。
7. 失败用例同时断言"无副作用"：审核/发布类用例检查 `cms_review`、`cms_article` 未发生越权变更；日志类用例检查无意外新增记录。

Apifox 后置断言示例（Apifox 兼容 Postman 的 `pm` 脚本语法）：

```javascript
pm.test("HTTP 200", () => pm.response.to.have.status(200));
const body = pm.response.json();
pm.test("统一响应四字段", () => {
  pm.expect(body).to.have.all.keys("code", "message", "data", "timestamp");
});
pm.test("成功码", () => {
  pm.expect(body.code).to.eql(200);
  pm.expect(body.message).to.eql("操作成功");
});
pm.test("ID 为字符串", () => {
  pm.expect(body.data.records[0].id).to.match(/^\d+$/);
});
pm.test("时间毫秒精度", () => {
  pm.expect(body.data.records[0].createdAt).to.match(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$/);
});
```

## 5. 执行方式与留证

1. **手动执行：** 在 Apifox 按 L-01～L-18 顺序执行，保存请求/响应截图；错误用例至少截一张完整响应体。
2. **场景运行：** 把 L-01～L-18 加入"操作日志回归"测试场景，一条命令跑完，保存运行报告截图。
3. **报告留证：** 课程设计报告"测试"部分放 Apifox 运行结果截图 + `mvn -B clean verify` 通过截图；JUnit（38 项本模块用例）证明代码级正确性，Apifox 证明接口级契约与真实数据库行为。
4. **回归时机：** 修改查询参数、错误提示、VO 字段、权限注解后必须重跑 L 组用例；接入业务写接口后补跑 A 组用例。
5. **自动化替代：** 本地已提供 `backend/target/run_e2e.ps1`，导入种子后自动启动 jar、执行 L-01～L-18 并清理数据，可作为 Apifox 之外的回归手段（脚本须以 UTF-8 带 BOM 保存，Windows PowerShell 5.1 才能正确解析中文）。

## 6. 已知偏差

| 偏差 | 影响 | 处理 |
| --- | --- | --- |
| 权限注解未接入（B 的权限数据层已合入） | 未登录也能查日志；普通员工可读全局审计数据 | 待 B 交付 `@RequirePermission` 后补 401/403 用例并验收 |
| 审计事务分支未在真实业务事务验证 | 仅测试桩 + 手工同步环境覆盖 | 业务写接口合入后执行 A-01～A-06 |
| 完整构建受宿主机 Mockito attach 限制 | B 的两个 Mockito 测试类 13 项在本机 JDK 25 下报 `MockMaker` 初始化失败 | 排除后 101 项全过；环境问题需组长/B 统一处理 |

## 7. 本次执行记录（2026-11-09 第二轮）

- 环境：Docker 容器 `mysql8`（MySQL 8.4，已含 `database/002_seed_permission_baseline.sql` 权限基线）+ 后端 jar（JDK 25，`TZ=Asia/Shanghai`，`SPRING_DATASOURCE_PASSWORD` 环境变量注入），监听 8080。
- 自动化脚本 `backend/target/run_e2e.ps1` 导入第 1 节种子后按 L-01～L-18 顺序执行，**18/18 PASS**：默认倒序、userId/result/operation/时间筛选、5 类参数 400 提示语逐字匹配、详情关联 `日志演示用户`、空 userId、404/400、越界页、未知参数（列表与详情）400。
- 脚本结束后 `cleanup` 删除种子日志与演示用户（复查剩余 0 条），`Stop-Process` 停止后端并释放 8080。
- 执行前核对：`100` 示例已统一为种子用户 `990101`；未知参数行为已与契约 8.1 对齐，原"未知参数返回 200"偏差关闭。
