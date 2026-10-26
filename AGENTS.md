
# EnterpriseCMS 项目开发规范

## 1. 项目定位

EnterpriseCMS 是企业内部知识与内容管理平台。
核心业务包括员工与部门管理、角色权限、文章分类、
知识文章、审核发布、标签管理和操作日志。

开始任务前，先阅读 `docs/EnterpriseCMS 项目设计计划书 V1.0.md`。
如果文档尚不存在，先询问用户，不要自行编造业务需求。

## 2. 技术栈

- Java 25
- Spring Boot 4.1.1
- Maven
- Spring Web
- MyBatis-Plus（使用兼容 Spring Boot 4 的 Starter）
- MySQL
- Spring Data Redis
- Lombok
- Validation
- Vue 3
- Element Plus
- Apifox
- JMeter

## 3. 后端规范

- Java 包名统一使用 com.xinghe.cms。
- 使用 Controller、Service、Mapper 分层。
- 常规单表 CRUD 优先使用 MyBatis-Plus。
- 多表关联查询使用明确的 SQL 或 Mapper XML。
- 使用统一响应格式、参数校验和异常处理。
- 权限校验必须在后端实施，不能仅依赖前端隐藏菜单。
- 避免 N+1 查询，必要时使用批量查询或 JOIN。
- 数据库变更必须提供 SQL 脚本。

## 4. 开发约束

- 修改前先阅读相关代码和现有配置。
- 一次只完成明确指定的任务，不擅自扩展需求。
- 未经确认，不升级框架版本或引入不必要的依赖。
- 不覆盖用户已有代码，不删除未理解的文件。
- 不执行未经确认的数据库删除、清空或重置操作。
- 不把数据库密码、Token 或其他秘密写入 Git。
- 保持现有命名规范和代码风格。

## 5. 验证要求

- 修改后运行适当的构建和测试。
- 无法执行测试时，说明原因，不得声称测试通过。
- 汇报修改的文件、关键实现、测试结果和剩余问题。
- 新增业务功能时，说明如何通过 Apifox 验证。

## 6. 团队协作

- 遵守 docs/EnterpriseCMS 项目设计计划书 V1.0.md 中的四人分工。
- 不擅自修改其他成员负责的模块。
- 不自动提交、推送或合并代码，除非用户明确要求。
- 发现跨模块接口不一致时，先提出方案并等待确认。
