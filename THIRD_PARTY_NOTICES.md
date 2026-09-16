# 第三方开源项目声明

## 当前状态

**本仓库目前没有直接包含任何第三方项目的源代码。**

后端 `backend/growth-api/` 通过 Maven 引入的依赖（Spring Boot、Flyway、
PostgreSQL JDBC、jjwt、MinIO SDK、Lombok 等）以库的形式使用，各自遵循其许可证，
完整清单见 `backend/growth-api/pom.xml`。

前端依赖见 `package.json`。

## 关于 ViMax

`docs/adr/001-adopt-vimax-core-with-stateless-adaptation.md` 记录了 2026-08-26
「采用 [HKUDS/ViMax](https://github.com/HKUDS/ViMax) 的创作链路并做无状态改造」的决策。

**该决策未被实施。** 复核后确认：

- ADR 中规划的 `packages/vimax-core/` 目录不存在
- 曾声明「直接复用」的 6 个 utils 文件、「适配改造」的 11 个 agents/interfaces 模块，
  在仓库中均不存在
- `legacy/ai-worker/requirements.txt` 未引入任何相关依赖
- 仓库中唯一的 "ViMax" 字样是 FastAPI 应用的 title 字符串

留下的只有包名（`vimax-api`）、数据库名（`wuyao_vimax`）和消息队列名前缀，
这些都随旧实现一起进入 `legacy/`。

本文件此前声明了实际未使用的复用内容，现予以更正。若将来真的引入 ViMax
（MIT License，Copyright (c) 2025 HKUDS）或其他开源代码，须在此重新如实声明并保留其版权声明。

---

嘉兴市梧曜科技有限公司
