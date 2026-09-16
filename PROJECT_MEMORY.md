# 项目记忆

> 更新时间：2026-09-14
>
> 只记录经过实际运行验证的事实。**不写未经验证的完成度声明**——
> 这个仓库以前吃过大亏：两份文档各自宣称「BUILD SUCCESS / 所有阶段完成」，
> 而其中一个后端从第一个提交起就编译不过，没人跑过编译器。

## 当前状态

- 仓库：<https://github.com/Luv10777/1>
- 当前修复分支：`codex/growth-foundation-hardening`，基于 `feat/growth-api-skeleton`
- 后端主干：`backend/growth-api/`（Java 21 + Spring Boot 3.5.16 + PostgreSQL + Flyway）
- 前端：`src/`（Vue 3 + Vite），开发地址 <http://localhost:4173>

## 已实际跑通并验证

后端（启动服务、连真实 PostgreSQL 实测）：

- Flyway 4 个迁移全部执行
- 手机验证码登录；失败次数独立持久化，五次失败后锁定，同一验证码并发只能消费一次
- 发送限流：单手机号 1 分钟 1 条 / 1 天 10 条，并发检查受数据库锁保护
- refresh token 加锁轮换，同一个令牌并发只有一个请求成功
- **跨租户越权被数据库行级安全拦截**：`AssetRepository.findById` 代码里没有任何
  租户条件，商家 A 拿商家 B 的 id 访问返回「不存在」，且 B 的数据未被修改
- 任务表调度：并发幂等提交、领取互斥、独立续租、按执行轮次回写、过期退避及重试上限
- 独立 Compose 环境自动建桶，真实预签名 PUT 上传 → 确认 READY → worker 完成；
  文件未上传时确认被拒绝，大小取自对象存储

前端（浏览器实测）：

- 未登录访问受保护路由被弹回登录页
- 通过 vite 代理调后端发送验证码、用真实验证码登录成功
- token 与 tenantId 正确持久化
- 伪造 token / 无 token 访问受保护接口均返回 1401

本次质量门：Java 21 下 `mvn verify` 通过，23 项后端测试零失败、零跳过；
38 个 Node 测试、vue-tsc、vite build 通过，ESLint 为 0 error、30 warning。
后端测试使用独立 PostgreSQL 16 和 MinIO。新增 GitHub Actions 配置，远端运行与
分支保护仍需在提交到 GitHub 后确认。

## 明确未接通

- 媒体元数据：宽高、时长和服务端 SHA-256 尚未解析，任务明确返回 `probed=false`
- AI 供应商：`EchoProviderAdapter` 是占位实现，不发任何网络请求
- 素材后端提供上传、确认、分页参考实现；前端素材页仍为演示数据。
  品牌 / 知识 / 作品等模块后端尚未实现
- 运营分析、全网发布、智能客服、GEO 增长：整块未开始

## 关键决策

- **一个后端主干**。此前并存的 `server/`（com.wuyao.nexus）和
  `backend/vimax-api`（com.wuyao.vimax）已退休，前者删除，后者移入 `legacy/`。
  退休前状态见 tag `archive/legacy-backends`。
- **不用消息队列**。任务表行锁保护领取，worker 按执行轮次续租和回写。
  崩溃重试采用至少一次语义，供应商调用和扣费等外部副作用必须自行保证幂等。
- **租户隔离下沉到数据库**。PostgreSQL 行级安全 + Hibernate 多租户连接提供者，
  业务代码忘写租户条件也不会漏数据。
- **业务代码只认能力别名**（`TEXT_WRITER`、`IMAGE_PRIMARY` 等），
  不出现供应商名字，换供应商只改网关层。
- 前端认证与其他模块的演示态用两个独立开关控制，互不牵连。

## 协作约定

- 后端按业务模块竖切分包，各人只在自己的包内改动，`common/` 的八条约定改前先商量
- 模块间仅依赖公开 Service/DTO；迁移先登记版本号，已合并迁移不改写
- 后端提交前运行 `mvn verify`，本地启动先通过脚本生成随机 JWT 密钥
- 提交前跑完 `npm test && npm run lint && npm run typecheck && npm run build`
- 真实凭证只进服务端密钥管理，不得提交
