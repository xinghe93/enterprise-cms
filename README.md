# EnterpriseCMS

面向企业内部员工的知识与内容管理平台，规划覆盖人员与组织、角色权限、文章分类、审核发布和操作审计。

当前处于项目初始化阶段：已创建 Spring Boot 后端骨架并配置基础依赖，业务接口、数据库脚本和前端应用尚未实现。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 25、Spring Boot 4.1.1、MyBatis-Plus 3.5.17 |
| 数据与缓存 | MySQL、Redis |
| 基础能力 | Spring MVC、参数校验、AspectJ、Lombok |
| 前端（规划） | Vue 3、Element Plus、Vue Router、Pinia、Axios |
| 构建 | Maven Wrapper（Maven 3.10.0） |

## 目录结构

```text
enterprise-cms/
├── backend/       # Spring Boot 后端
│   ├── .mvn/      # Maven Wrapper 配置
│   ├── src/main/  # 应用源码与配置
│   ├── src/test/  # 测试源码
│   └── pom.xml    # 依赖与构建配置
├── docs/          # 项目设计文档
├── .gitignore     # 仓库统一忽略规则
└── README.md
```

前端工程后续放在 `frontend/` 下；Git 不跟踪空目录。

## 环境准备

- 安装 JDK 25，并确认 `java -version` 使用正确版本。
- 首次执行 Maven Wrapper 需要联网下载 Maven 和项目依赖，无需单独安装 Maven。
- 后端已引入 MyBatis-Plus 和 MySQL 驱动，启动应用和运行完整上下文测试前需要配置数据源。
- 使用缓存功能时需要可连接的 Redis 服务。

## 后端启动

在仓库根目录执行以下命令，进入后端目录：

```bash
cd backend
```

通过环境变量配置本地 MySQL 数据源。以下为 macOS/Linux 示例，请将数据库、用户名和密码替换为本机配置，并提前创建对应数据库：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/enterprise_cms?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME='your_username'
read -r -s SPRING_DATASOURCE_PASSWORD
export SPRING_DATASOURCE_PASSWORD

./mvnw spring-boot:run
```

执行 `read` 后输入数据库密码并按回车，终端不会回显密码。也可以在 IDE 的运行配置中设置上述环境变量。

Windows 用户可在 IDE 的运行配置中设置相同环境变量，并在 IDE 中启动应用；也可在已配置好环境变量的 PowerShell 中执行 `.\mvnw.cmd spring-boot:run`，构建和测试命令同样将 `./mvnw` 替换为 `.\mvnw.cmd`。

Redis 可通过 `SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT` 和 `SPRING_DATA_REDIS_PASSWORD` 配置。

默认 HTTP 端口为 `8080`。当前尚未实现业务接口，访问根路径返回 404 属于预期行为。

## 构建与测试

在 `backend/` 目录中执行：

```bash
# 编译、运行测试并打包
./mvnw verify

# 单独运行测试
./mvnw test
```

`BackendApplicationTests` 使用完整的 Spring Boot 应用上下文，需要先按上述方式配置数据源。当前尚无数据库建表或初始化脚本。

当前骨架的测试尚未执行 SQL，仅验证上下文加载。在 macOS/Linux 下也可以临时提供 JDBC URL 完成构建：

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/enterprise_cms' ./mvnw verify
```

此检查不验证 MySQL 或 Redis 的实际连通性；后续增加数据库操作后，需要准备对应服务和测试数据。

本地环境文件 `.env` 和 `.env.*` 已被 Git 忽略，示例文件 `.env.example` 可以提交。Spring Boot 默认不会自动加载 `.env` 文件，请使用环境变量或 IDE 运行配置提供连接信息。

## 项目文档

- [项目设计计划书 V1.0](docs/EnterpriseCMS%20项目设计计划书%20V1.0.md)
