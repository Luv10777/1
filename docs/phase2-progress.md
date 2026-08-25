# 第二阶段进度记录

## 已完成（M0 / M1 / M2 首批 / M3 首批）

- 状态机：Campaign、Batch、Item、Step 的合法迁移和终态。
- Provider：内部能力别名、统一结果、Mock 文本/图片异步 Provider。
- 编排：自然语言解释、计划确认、幂等额度预占、批量执行、失败项隔离、事件记录。
- 编译器：Intent JSON 结构校验、已确认事实快照、缺失事实阻断、CreativePlan、PromptArtifact、QAReport。
- 媒体底座：租户隔离的 Mock Object Storage、比例/商品参考/文字安全区视觉 QA、确定性文字叠加规范。
- 前端：`/creative` 一句话创作工作区、事实边界、Prompt 展开、QA 摘要、成本确认和批量启动。

## 当前仍是 Mock 的部分

- 没有真实 FluAPI、ToAPIs、Seedance、Image2.0 或 TTS/数字人凭证。
- 没有生产数据库、Redis、MQ、对象存储或后端服务端鉴权。
- 当前生成结果和资产 URL 仅用于本地演示，不能作为生产发布能力。

## 下一步（M3/M4）

1. 把图片批量执行拆为可恢复的 Worker 任务，并把对象存储替换为服务端签名上传。
2. 增加视频 brief、脚本、角色/场景锁定、分镜和 Seedance Provider Contract。
3. 增加单项重试、QA 修复建议和批量审核中心。
