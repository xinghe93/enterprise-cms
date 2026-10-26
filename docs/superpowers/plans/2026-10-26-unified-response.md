# Unified API Response Implementation Plan

**Goal:** 实现设计书约定的统一 JSON 响应与分页数据结构，供四个业务模块共用。

**Architecture:** Controller 显式返回 `ApiResponse<T>`；`ResultCode` 管理公共结果码；`PageResult<T>` 从 MyBatis-Plus 的 `IPage<T>` 提取对外分页字段。保持现有 Spring Boot JSON 配置和依赖。

**Tech Stack:** Java 25、Spring Boot 4.1.1、Jackson 3、MyBatis-Plus、JUnit 5。

## 确认的约定

- 顶层固定为 `code`、`message`、`data`、`timestamp`，空数据保留为 `null`。
- 成功码 `200`；失败码 `400`、`401`、`403`、`404`、`409`、`500`。
- `timestamp` 使用 UTC ISO 8601 字符串。
- 分页数据固定为 `records`、`total`、`current`、`size`，页码从 1 开始。
- 公共对象负责响应体；HTTP 状态由 Controller 或后续异常处理器设置。

## Task 1: 契约测试

**Create:** `backend/src/test/java/com/xinghe/cms/common/response/ApiResponseTests.java`

- [x] 使用应用实际的 `JsonMapper` 验证有数据、无数据、默认失败、自定义失败消息和分页 JSON。
- [x] 验证失败工厂不接受成功结果码，分页对象不随来源集合变化，非法分页参数被拒绝。
- [x] 执行 `./mvnw -o -Dtest=ApiResponseTests test`，确认因公共响应类型尚未实现而失败。

预期使用方式：

```java
ApiResponse.success(Map.of("id", 1));
ApiResponse.success();
ApiResponse.error(ResultCode.NOT_FOUND);
ApiResponse.error(ResultCode.CONFLICT, "用户名已存在");
ApiResponse.success(PageResult.from(page));
```

## Task 2: 公共类型

**Create:** `backend/src/main/java/com/xinghe/cms/common/response/ResultCode.java`

- [x] 定义成功、参数错误、未登录、无权限、不存在、业务冲突、服务器错误结果码及默认消息。

**Create:** `backend/src/main/java/com/xinghe/cms/common/response/ApiResponse.java`

- [x] 使用不可变泛型 record，固定 JSON 字段与空值行为。
- [x] 实现 `success(data)`、`success()`、`error(resultCode)`、`error(resultCode, message)` 工厂；生成非空时间戳并拒绝以成功码创建失败响应。

**Create:** `backend/src/main/java/com/xinghe/cms/common/response/PageResult.java`

- [x] 使用 record 暴露四个分页字段，复制记录集合，校验非负总数和正数页码、页大小。
- [x] 实现 `from(IPage<T>)`，避免直接对外序列化 MyBatis-Plus 的内部分页字段。

## Task 3: 验证与团队使用说明

**Create:** `docs/统一响应格式.md`

- [x] 记录 JSON 示例、Controller 用法、HTTP 状态关系、分页约定和 Apifox 验证方法。
- [x] 执行 `./mvnw -o clean verify`，确认全部测试与打包通过。
- [x] 执行 `git diff --check` 并检查变更范围。

本次范围为公共响应类型；全局异常处理和业务接口另行实现。按项目规范，不自动提交或推送。

## 执行结果

已确认实现前因缺少公共类型而失败；实现后 clean verify 和最终 verify 均通过，共 14 项测试，0 失败、0 错误。独立静态审查未发现重要问题。
