# 公共异常处理 Implementation Plan

**Goal:** 为四个业务模块提供统一业务异常和 MVC 异常响应，复用现有 `ApiResponse`。

**Architecture:** `BusinessException` 携带现有 `ResultCode` 和可公开提示；`GlobalExceptionHandler` 使用 `@RestControllerAdvice`，继承 Spring MVC 的 `ResponseEntityExceptionHandler`，保留框架错误的 HTTP 状态与协议头。未知异常和服务端错误统一隐藏内部详情，并在服务端记录堆栈。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Spring MVC 7、Jackson 3、Jakarta Validation、JUnit 5、MockMvc；不增加依赖。

## 已确认范围与设计

- 用户已确认前一轮列出的业务异常、全局处理、参数错误、系统日志、测试及使用说明范围，并要求在 `feature/` 分支完成。
- `common/exception/BusinessException.java`：提供默认消息及自定义消息构造器，拒绝成功结果码、空结果码和空白消息。
- `common/exception/GlobalExceptionHandler.java`：业务错误使用对应状态；请求体和方法参数校验返回可公开的校验消息；缺参、类型错误和 JSON 解析失败返回通用 400；404、405、415 等保留原状态；5xx 不返回内部异常消息。
- MVC 以外的过滤器、容器错误分派、已经提交的响应不属于本次统一处理范围；不新增演示业务接口。
- 只记录系统异常堆栈，不主动输出请求体、请求参数、密码或令牌。业务异常自定义消息须由开发者提供安全提示。

## Task 1：确认测试基线

- [x] 阅读设计计划书、统一响应文档及现有代码。
- [x] 从当前本地 `dev` 新建 `feature/global-exception-handler`。
- [x] 在 `backend/` 执行 `./mvnw -o -Daether.syncContext.named.factory=rwlock-local test`，14 项测试通过。

## Task 2：测试与实现

**Create:** `backend/src/test/java/com/xinghe/cms/common/exception/GlobalExceptionHandlerTests.java`

**Create:** `backend/src/test/java/com/xinghe/cms/common/exception/BusinessExceptionTests.java`

**Create:** `backend/src/main/java/com/xinghe/cms/common/exception/BusinessException.java`

**Create:** `backend/src/main/java/com/xinghe/cms/common/exception/GlobalExceptionHandler.java`

- [x] 先以测试专用 Controller 和实际 MVC/Jackson 配置验证非法 JSON 返回 HTTP 400 及统一响应；执行测试，确认缺失处理器时契约失败。
- [x] 补充业务错误码、DTO 校验、查询参数校验、缺参、类型错误、404/405/415、未知异常脱敏及成功响应回归测试。
- [x] 实现业务异常与 Advice，仅捕获 `Exception`，保留 MVC 原有状态和响应头，对已提交响应遵循框架处理。
- [x] 执行定向测试，修正实现直至契约全部通过。

使用方式：

```java
throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
throw new BusinessException(ResultCode.NOT_FOUND);
```

## Task 3：文档、审查及最终验证

**Create:** `docs/公共异常处理.md`

**Modify:** `docs/统一响应格式.md`

- [x] 记录适用范围、各类异常响应、DTO 校验及业务异常用法、日志规则和 Apifox 验证步骤。
- [x] 更新统一响应文档中尚未实现全局异常处理的说明。
- [x] 审查变更，执行 `./mvnw -o -Daether.syncContext.named.factory=rwlock-local clean verify` 和 `git diff --check`。
- [x] 按项目约束保留本地未提交改动，不自动提交、推送或合并。

## 执行结果

初始非法 JSON 契约测试因缺少统一响应体而失败。实现后 26 项定向测试通过；审查发现表单 DTO 类型绑定失败会回传内部转换信息，新增回归测试成功复现，并修复为通用 400。复审未发现其他重要问题。最终 clean verify：41 项测试，0 失败、0 错误、0 跳过，可执行 JAR 打包成功。
