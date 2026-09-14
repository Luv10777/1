# 项目记忆

> 更新时间：2026-09-13
>
> 只记录经过实际运行验证的事实。**不写未经验证的完成度声明**——
> 这个仓库以前吃过大亏：两份文档各自宣称「BUILD SUCCESS / 所有阶段完成」，
> 而其中一个后端从第一个提交起就编译不过，没人跑过编译器。

## 当前状态

- 仓库：<https://github.com/Luv10777/1>
- 分支：`feat/growth-api-skeleton`
- 后端主干：`backend/growth-api/`（Java 21 + Spring Boot 3.5.16 + PostgreSQL + Flyway）
- 前端：`src/`（Vue 3 + Vite），开发地址 <http://localhost:4173>

## 已实际跑通并验证

后端（启动服务、连真实 PostgreSQL 实测）：

- Flyway 4 个迁移全部执行
- 手机验证码登录（登录即注册，自动创建租户）
- 发送限流：单手机号 1 分钟 1 条 / 1 天 10 条
- refresh token 轮换，旧 token 立刻失效
- **跨租户越权被数据库行级安全拦截**：`AssetRepository.findById` 代码里没有任何
  租户条件，商家 A 拿商家 B 的 id 访问返回「不存在」，且 B 的数据未被修改
- 任务表调度：提交 → worker 用 `FOR UPDATE SKIP LOCKED` 抢占 → 执行 → 回写结果，
  幂等键生效

前端（浏览器实测）：

- 未登录访问受保护路由被弹回登录页
- 通过 vite 代理调后端发送验证码、用真实验证码登录成功
- token 与 tenantId 正确持久化
- 伪造 token / 无 token 访问受保护接口均返回 1401

质量门：38 个 Node 测试、ESLint（0 error）、vue-tsc、vite build 全部通过。

## 明确未接通

- 对象存储：MinIO 预签名未验证（本地未启动 MinIO，仅验证失败时返回 1503）
- AI 供应商：`EchoProviderAdapter` 是占位实现，不发任何网络请求
- 品牌 / 素材 / 知识 / 作品等模块：后端未实现，前端仍返回演示数据
- 运营分析、全网发布、智能客服、GEO 增长：整块未开始
- 后端尚无单元测试

## 关键决策

- **一个后端主干**。此前并存的 `server/`（com.wuyao.nexus）和
  `backend/vimax-api`（com.wuyao.vimax）已退休，前者删除，后者移入 `legacy/`。
  退休前状态见 tag `archive/legacy-backends`。
- **不用消息队列**。异步调度靠 `tasks` 表 + `FOR UPDATE SKIP LOCKED`，
  行锁本身即互斥，worker 可任意扩缩，也不需要分布式锁。
- **租户隔离下沉到数据库**。PostgreSQL 行级安全 + Hibernate 多租户连接提供者，
  业务代码忘写租户条件也不会漏数据。
- **业务代码只认能力别名**（`TEXT_WRITER`、`IMAGE_PRIMARY` 等），
  不出现供应商名字，换供应商只改网关层。
- 前端认证与其他模块的演示态用两个独立开关控制，互不牵连。

## 协作约定

- 后端按业务模块竖切分包，各人只在自己的包内改动，`common/` 的八条约定改前先商量
- 提交前跑完 `npm test && npm run lint && npm run typecheck && npm run build`
- 真实凭证只进服务端密钥管理，不得提交
