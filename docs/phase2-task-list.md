# 第二阶段可执行任务清单

## M0：架构落位

- [x] 第一阶段基线、分支和远端审计
- [x] Campaign/Batch/Item/Step/FactSnapshot/CostLedger 契约
- [x] 状态机与 Provider Contract
- [x] `docs/phase2-architecture.md`

## M1：工作流底座

- [x] Mock Orchestrator：解析、确认、批量启动、单项失败隔离
- [x] 幂等额度预占和成本台账
- [x] Provider 错误分类与异步任务形态
- [ ] Core API 持久化实现（需后端运行时与数据库选择）
- [ ] Redis/MQ Worker 和恢复演练

## M2：一句话创作编译器

- [ ] Intent JSON Schema 与严格校验
- [ ] 商家事实快照与缺失事实阻断
- [ ] Creative Plan / PromptArtifact / QAReport
- [ ] 创作工作台、计划预览、成本确认、批量启动

## M3-M6

- [ ] Image2.0 / Seedance 适配器与对象存储
- [ ] QA、审核中心、版本对比、数字人授权
- [ ] 平台连接器、发布日历、评价中心
- [ ] 数据回流、运营后台、成本/额度、审计与恢复

每个未勾选项必须在真实凭证、服务端运行时和契约测试具备后再标记完成。
