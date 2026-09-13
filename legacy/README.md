# legacy · 已退休的实现

这里的代码**已经放弃**，不再演进，也不参与构建。保留只有一个目的：
在往 `backend/growth-api/` 搬零件时查阅。

## 里面是什么

### `vimax-api/` — Java，com.wuyao.vimax
2026-08-26 起写的第二版后端。99 个 Java 文件，7857 行。

- ✅ 能编译（Spring Boot 4.0.0，实测 BUILD SUCCESS）
- ❌ 起不动：`ddl-auto: validate`，但 `credit_transactions`、`tenant_credit_accounts`、
  `provider_configs` 三张表在迁移脚本里根本不存在
- ❌ 完全没有 Spring Security，`X-User-Id` 头默认值是 `1`
- ❌ 存在两套同名的 `QualityCheckService` 和 `CostCalculationService`
- ❌ 人工审核接口是假成功桩：函数体只有 TODO，却返回「审核已提交」

**还没搬走、可能有用的**：Provider 适配器的请求格式（FluAPI / ToAPIs，未验证）、
MinIO 封装、质检与人工审核队列的结构设计。

### `ai-worker/` — Python，FastAPI + RabbitMQ
配合 vimax-api 的执行器。14 个文件，973 行。

`workflow_executor.py` 六个任务分支**全是 TODO**，返回「Mock 创意内容」。
唯一可能有用的是 `services/ffmpeg_processor.py`（182 行）。

## 关于 ViMax 开源项目

`docs/adr/001` 记录了「采用开源项目 HKUDS/ViMax 并改造」的决策，
`THIRD_PARTY_NOTICES.md` 曾声明复用了 17 个文件。

**实际上一行代码都没有引入。** 没有 `packages/vimax-core/`，
没有 `agents/screenwriter.py`，`requirements.txt` 里连相关依赖都没有。
那个决策只留下了包名、库名和队列名前缀。

## 删除时机

零件搬完就整个删掉。删除前的完整状态已经打了 tag：

```bash
git show archive/legacy-backends:<路径>              # 看单个文件
git checkout archive/legacy-backends -- <路径>       # 取回目录
```
