# 梧曜星枢 · 仓库约定

## 目录

| 目录 | 说明 |
|------|------|
| `src/` | 前端 Vue 3 |
| `backend/growth-api/` | **唯一的后端主干**。新功能全写这里 |
| `legacy/` | 已退休的旧实现，只为查阅保留 |
| `docs/` | 设计文档。`adr/` 记录架构决策的来龙去脉 |

## ⛔ legacy/ 目录

`legacy/vimax-api`（Java）和 `legacy/ai-worker`（Python）是已经**放弃**的两次尝试。
保留只为在搬运 Provider 适配器、FFmpeg 处理等零件时查阅。

**禁止参考其中的代码风格、接口约定、错误处理方式和表结构。** 它们与当前约定直接冲突：

| legacy 里的写法 | 当前约定 |
|---|---|
| `@RequestHeader("X-User-Id", defaultValue="1")` | 租户和用户只从 JWT 取，接口不接受传参 |
| `ApiResponse.success(...)` | `ApiResponse.ok(...)` |
| 建表不加行级安全 | 每张业务表必须 `ENABLE/FORCE ROW LEVEL SECURITY` + POLICY |
| `@Scheduled` 直接操作业务数据 | 异步一律走 `tasks` 表 + `TaskHandler` |
| 手写 SQL 迁移脚本、自行编号 | Flyway 管理，`V?__模块.sql` |

零件搬完后整个 `legacy/` 会删除。

## 后端

技术栈 Java 21 + Spring Boot 3.5.16 + PostgreSQL + Flyway。
八条共享约定见 `backend/growth-api/README.md`，**改动前先商量**，那是并行开发的地基。

加新模块照 `backend/growth-api/src/main/java/com/wuyao/growth/asset/` 的结构写。

公共基础修复后的协作规则：

- 每个业务模块指定负责人，`common/` 和 `iam/` 的接口变更双方审查。
- 模块间通过公开 Service/DTO 交互，不跨模块调用 Repository 或复用 Entity。
- 新建 Flyway 版本前先同步主干并登记编号，已合并迁移不修改。
- `TaskHandler` 按至少一次执行设计，外部副作用使用稳定幂等键。
- 后端改动必须在 `backend/growth-api` 运行 `mvn verify`，需要 Docker。

## 前端

- 认证已接通真实后端；品牌 / 素材 / 知识 / 作品等模块后端尚未实现，仍返回演示数据
- `isDemoMode()` 只表示"该模块后端未实现"，与认证无关
- 请求统一走 `src/utils/request.js`，**不要在组件里直接 fetch，也不要硬编码后端地址**

## 提交前

```bash
npm test && npm run lint && npm run typecheck && npm run build
```

## 纪律

- 不写静态假数据冒充真实业务数据；未接通的能力必须显式标注
- 真实 API Key、Cookie、OAuth 凭证只能进服务端密钥管理，不得提交
- **不要在文档里声明未经运行验证的完成度**。这个仓库以前吃过大亏：
  两个后端各写了一份「BUILD SUCCESS / 所有阶段完成」的报告，
  而其中一个从第一个提交起就编译不过。
