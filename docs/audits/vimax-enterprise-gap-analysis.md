# ViMax 企业级改造差距分析报告

> **审计时间**: 2026-08-26  
> **审计人**: Claude Code (Opus 5)  
> **ViMax 上游版本**: v1.2.0 (commit: 05a48943878312d88fe5a016c12a9654940ecc43)  
> **现有平台仓库**: https://github.com/Luv10777/1  
> **当前分支**: audit/vimax-backend-reuse

---

## 执行摘要

本报告完成了对现有"梧曜星枢"平台仓库与 ViMax 上游项目的全面审计，明确了企业级 AI 视频工作流改造的技术边界、复用策略和实施风险。

### 关键发现

✅ **可复用优势**
- 现有平台已具备完整的前端工程、Mock 工作流编排器、领域契约和状态机
- ViMax 提供成熟的创作链路（Idea2Video、Script2Video）和结构化输出 Schema
- Spring Boot 3 后端骨架已建立，支持 JWT、多租户、商家和门店管理
- PostgreSQL 数据库 schema 已初始化，包含租户、用户、商家、门店、资产等核心表

⚠️ **重大差距**
- ViMax 使用本地 `working_dir` 作为状态源，不适合 SaaS 多租户架构
- ViMax 直接耦合 Yunwu、OpenRouter、Google 等供应商实现，需完全重写适配层
- 现有平台缺少 Python AI Worker、RabbitMQ 队列、Redis 分布式锁、MinIO 对象存储
- 缺少商家事实快照、Prompt 版本化、镜头级重试、成本账本和审计日志的完整实现
- ViMax Web UI/TUI 不能直接用于多租户 SaaS，需融入现有平台设计体系

🔴 **阻塞风险**
- **FluAPI 和 ToAPIs 真实 API 文档未提供**，无法完成真实调用验证
- ViMax MIT 许可证允许商业使用，但必须保留版权声明并建立 THIRD_PARTY_NOTICES.md
- 跨越前端、Java 后端、Python Worker、队列、对象存储的完整链路需要 10 个阶段逐步验收

---

## 1. 现有平台仓库审计

### 1.1 仓库基本信息

| 项目 | 值 |
|------|-----|
| **本地路径** | `C:\Users\Administrator\梧曜AI` |
| **GitHub 仓库** | https://github.com/Luv10777/1 |
| **当前分支** | audit/vimax-backend-reuse |
| **最新提交** | e8da7da docs: audit ViMax backend reuse for AI video workflow |
| **未提交修改** | PROJECT_MEMORY.md (已修改但未暂存) |
| **远程同步状态** | 与 origin/audit/vimax-backend-reuse 一致 |

### 1.2 已有功能分支

```
main                                    # 主分支（已合并 Phase 1-5）
feat/phase-1-functional-foundation      # 工程基础
feat/phase-2-ai-creative-compiler       # AI 创作编译器
feat/phase-3-growth-closed-loop         # 增长闭环
feat/phase-4-saas-commercialization     # SaaS 商业化
feat/phase-5-open-ecosystem-growth-os   # 开放生态
audit/vimax-backend-reuse               # 当前审计分支
```

### 1.3 前端技术栈

✅ **已完成**
- **框架**: Vue 3.5.13 + Vite 6.0.7 + Vue Router 4.5.0
- **语言**: JavaScript（ES2020+）+ TypeScript 类型定义
- **代码质量**: ESLint 9.17.0 (Flat Config) + 38 个单元测试全部通过
- **UI 风格**: 深空科技感、深色主题、星枢控制台设计体系
- **状态管理**: Pinia (auth、creative、billing、commerce stores)
- **路由**: 22 个业务路由 + 403/404 + SPA fallback
- **Mock 能力**: Mock 登录、Mock 商家/门店、Mock Provider、Mock Object Storage

✅ **领域模型**（`src/domain/`）
- `creative.js` - 工作流状态机、Campaign、Batch、Item、Step、FactSnapshot
- `orchestrator.js` - Mock 工作流编排器（幂等、额度预占、成本台账）
- `providers.js` - Provider 统一契约、Mock Text/Image Provider
- `compiler.js` - Prompt 编译器、事实快照、QA 报告
- `media.js` - Mock Object Storage、视觉 QA
- `video.js` - 视频脚本、分镜、角色/场景锁定
- `review.js` - 高风险内容审核
- `billing.js` - 成本预估、额度管理
- `connectors.js` - 平台连接器生命周期

✅ **已验证基线**
```bash
npm test          # 38 tests passed
npm run lint      # ESLint pass (--quiet)
npm run build     # Vite build success
```

⚠️ **缺失能力**
- TypeScript 严格模式未启用（`vue-tsc --noEmit` 未运行）
- 所有 AI 调用为 Mock，未接真实 Provider
- 对象存储为内存 Map，未接 MinIO/S3
- 工作流编排器为前端内存实现，未持久化到数据库

### 1.4 后端技术栈

✅ **已完成**（`server/` 目录）
- **框架**: Spring Boot 3.2.5 + Java 21
- **数据库**: Spring Data JPA + PostgreSQL Driver
- **安全**: Spring Security + JWT (jjwt 0.12.5)
- **缓存**: Spring Data Redis
- **工具**: Lombok + Maven

✅ **已实现功能**
- 手机验证码登录（SHA-256 哈希 + Redis 缓存 + 5 分钟过期）
- JWT Token 认证（Access Token 1 小时 + Refresh Token 30 天）
- 多租户架构（登录即创建租户）
- 商家与门店 CRUD（软删除、状态管理、完整度计算）
- 全局异常处理 + CORS 配置
- 参数校验（Bean Validation）

✅ **数据库 Schema**（`infra/database/001_init_schema.sql`）
```sql
tenants                 # 租户表
users                   # 用户表
tenant_members          # 租户成员关联表
roles                   # 角色表
permissions             # 权限表
role_permissions        # 角色权限关联表
merchants               # 商家表
stores                  # 门店表（关联商家）
```

⚠️ **缺失能力**
- **RabbitMQ 队列** - 未集成
- **MinIO/S3 对象存储** - 未集成
- **Python AI Worker** - 不存在
- **Flyway 数据库迁移** - 未配置
- **OpenTelemetry 追踪** - 未集成
- **视频工作流核心表** - 未建立（workflow_runs、shots、provider_jobs、cost_ledger 等）
- **商家事实快照表** - 未建立
- **Transactional Outbox/Inbox** - 未实现
- **AI Gateway** - 未实现
- **Provider 适配器** - 未实现（FluAPI、ToAPIs）

### 1.5 基础设施

✅ **已有配置**（`infra/` 目录）
- `docker-compose.yml` - 存在但内容未完整
- `database/001_init_schema.sql` - 基础表结构
- `database/002_resource_libraries.sql` - 资源库表（品牌、素材、知识、作品）
- `database/003_ai_gateway_tasks.sql` - AI 网关任务表

⚠️ **缺失基础设施**
- RabbitMQ 容器配置
- Redis 容器配置
- MinIO 容器配置
- PostgreSQL 容器配置（docker-compose 中未完整）
- Python AI Worker 服务定义
- Flyway 迁移脚本目录结构
- 监控、日志、追踪基础设施

### 1.6 明确的"未完成"边界

根据 `PROJECT_MEMORY.md` 和 `README.md`，现有平台明确标识以下能力**尚未完成**：

❌ Core API 真实业务服务端重新校验  
❌ 数据库事务性工作流状态机  
❌ 可恢复 Worker（队列消费、Provider 轮询、重试、幂等、死信）  
❌ 真实对象存储、签名上传和回调安全  
❌ 真实 FluAPI/ToAPIs/Seedance/Image2.0 接入  
❌ 真实视频生成与 FFmpeg 合成  
❌ 数字人授权/TTS/Avatar 链路  
❌ 审核中心前端  
❌ 平台 OAuth  
❌ 发布日历和真实发布  
❌ 评论同步  
❌ 数据回流  
❌ 运营分析  
❌ 成本结算  
❌ 退款  
❌ 运营后台和后端审计日志  

**结论**: 现有平台已完成前端工程基线、Mock 工作流编排器、Spring Boot 骨架和基础数据库表，但**企业级 AI 视频工作流的核心基础设施、持久化状态机、队列系统、对象存储、Python Worker 和真实 Provider 适配层均未实现**。

---

## 2. ViMax 上游项目审计

### 2.1 基本信息

| 项目 | 值 |
|------|-----|
| **GitHub 仓库** | https://github.com/HKUDS/ViMax |
| **固定版本** | v1.2.0 |
| **固定 Commit** | 05a48943878312d88fe5a016c12a9654940ecc43 |
| **许可证** | MIT License |
| **Python 版本** | >= 3.12 |
| **包管理器** | uv |
| **主要依赖** | langchain, openai, google-genai, moviepy, opencv-python, pillow, pyyaml, requests, tenacity |

### 2.2 核心工作流

✅ **Idea2Video** (`main_idea2video.py`, `pipelines/idea2video_pipeline.py`)
- 输入：一句话创意 + 用户要求 + 风格
- 输出：完整视频（包含脚本、分镜、参考图、镜头视频、音频、合成）

✅ **Script2Video** (`main_script2video.py`, `pipelines/script2video_pipeline.py`)
- 输入：场景脚本 + 用户要求 + 风格
- 输出：多场景、多镜头视频（保留创作意图）

✅ **Novel2Video** (`pipelines/novel2movie_pipeline.py`)
- 输入：长篇小说
- 输出：分集视觉叙事（叙事压缩、角色追踪、场景规划）

✅ **Agent Loop + TUI** (`main_agent.py`, `agent_runtime/`, `vimax tui`)
- 交互式规划、修订、渲染控制、会话恢复、上下文压缩

✅ **Web UI** (`web/`, `http://127.0.0.1:4173`)
- 命名项目、Agent 对话、Artifact 预览、Storyboard 预览、文件上传、Provider 设置

### 2.3 核心 Agents（`agents/` 目录）

| 文件 | 功能 | 复用评估 |
|------|------|---------|
| `screenwriter.py` | 编剧（生成结构化脚本） | ✅ ADAPT |
| `storyboard_artist.py` | 分镜设计师（镜头拆分、连续性设计） | ✅ ADAPT |
| `character_extractor.py` | 角色提取器 | ✅ ADAPT |
| `scene_extractor.py` | 场景提取器 | ✅ ADAPT |
| `event_extractor.py` | 事件提取器 | ✅ ADAPT |
| `reference_image_selector.py` | 参考图选择器 | ✅ ADAPT |
| `best_image_selector.py` | 最佳图片选择器 | ✅ ADAPT |
| `character_portraits_generator.py` | 角色肖像生成器 | ✅ ADAPT |
| `camera_image_generator.py` | 镜头图片生成器 | ✅ ADAPT |
| `script_planner.py` | 脚本规划器 | ✅ ADAPT |
| `script_enhancer.py` | 脚本增强器 | ✅ ADAPT |
| `global_information_planner.py` | 全局信息规划器 | ✅ ADAPT |
| `novel_compressor.py` | 小说压缩器 | ⚠️ REFERENCE_ONLY (本次不做 Novel2Video) |

### 2.4 核心 Interfaces（`interfaces/` 目录）

| 文件 | 功能 | 复用评估 |
|------|------|---------|
| `character.py` | 角色 Schema（Pydantic） | ✅ ADAPT |
| `scene.py` | 场景 Schema | ✅ ADAPT |
| `event.py` | 事件 Schema | ✅ ADAPT |
| `camera.py` | 镜头 Schema | ✅ ADAPT |
| `shot_description.py` | 镜头描述 Schema | ✅ ADAPT |
| `frame.py` | 帧 Schema | ✅ ADAPT |
| `environment.py` | 环境 Schema | ✅ ADAPT |
| `image_output.py` | 图片输出 Schema | ✅ ADAPT |
| `video_output.py` | 视频输出 Schema | ✅ ADAPT |

### 2.5 工具模块（`tools/` 目录）

| 文件 | 功能 | 复用评估 |
|------|------|---------|
| `protocols.py` | 工具协议定义 | ✅ REFERENCE_ONLY |
| `image_orientation.py` | 图片方向检测 | ✅ REUSE_AS_IS |
| `image_response.py` | 图片响应封装 | ✅ ADAPT |
| `render_backend.py` | 渲染后端抽象 | ✅ REFERENCE_ONLY |
| `reranker_bge_silicon_api.py` | BGE 重排序器 | ⚠️ EXCLUDE (非核心能力) |
| `image_generator_yunwu_*.py` | Yunwu 图片生成器 | ❌ EXCLUDE (供应商耦合) |
| `image_generator_openrouter_api.py` | OpenRouter 图片生成器 | ❌ EXCLUDE (供应商耦合) |
| `image_generator_google_api.py` | Google 图片生成器 | ❌ EXCLUDE (供应商耦合) |
| `video_generator_yunwu_*.py` | Yunwu 视频生成器 | ❌ EXCLUDE (供应商耦合) |
| `video_generator_openrouter_api.py` | OpenRouter 视频生成器 | ❌ EXCLUDE (供应商耦合) |
| `video_generator_google_api.py` | Google 视频生成器 | ❌ EXCLUDE (供应商耦合) |

### 2.6 工具函数（`utils/` 目录）

| 文件 | 功能 | 复用评估 |
|------|------|---------|
| `robust_json_parser.py` | 容错 JSON 解析 | ✅ REUSE_AS_IS |
| `retry.py` | 重试装饰器 | ✅ REUSE_AS_IS |
| `rate_limiter.py` | 速率限制器 | ✅ REFERENCE_ONLY |
| `timer.py` | 计时器 | ✅ REUSE_AS_IS |
| `text.py` | 文本处理工具 | ✅ REUSE_AS_IS |
| `image.py` | 图片处理工具 | ✅ REUSE_AS_IS |
| `video.py` | 视频处理工具（FFmpeg） | ✅ ADAPT |
| `provider_presets.py` | Provider 预设配置 | ❌ EXCLUDE (供应商耦合) |

### 2.7 不适合 SaaS 的特性

❌ **本地状态管理**
- `working_dir` 作为任务状态源
- 通过文件是否存在判断业务步骤是否完成
- JSON 文件存储中间结果和会话状态

❌ **供应商直连**
- 用户在 YAML 配置文件中直接填写 API Key
- Agent/Worker 直接调用供应商 API
- 无租户隔离、无成本账本、无审计日志

❌ **进程内并发**
- 使用 `asyncio.gather` 作为唯一调度方案
- 无持久任务队列
- 无跨进程恢复能力

❌ **Web UI 单用户设计**
- 本地 `configs/agent.local.yaml` 配置
- 无租户、无权限、无审核流程
- 直接暴露 Provider 设置给用户

❌ **无企业级约束**
- 无幂等键
- 无成本预占和额度保护
- 无商家事实快照和防编造机制
- 无人工审核强制点
- 无 Prompt 版本化
- 无镜头级重试和恢复

### 2.8 可复用的核心思想

✅ **创作链路**
- Idea → Creative Plan → Script → Storyboard → Reference Images → Shots → Video

✅ **结构化输出**
- 所有 Agent 输出使用 Pydantic Schema
- 严格校验、容错解析

✅ **参考图管理**
- 角色/场景参考图生成
- 候选图筛选（BestImageSelector）
- 视觉一致性保障

✅ **镜头连续性**
- Continuity Token
- 首帧/尾帧要求
- 角色服饰锁定

✅ **视频合成**
- FFmpeg concat
- 音频混音
- 字幕叠加

---

## 3. 复用矩阵与改造策略

### 3.1 ViMax 文件分类

根据实施方案第 3.2 节要求，对每个 ViMax 核心文件标记为：`REUSE_AS_IS`、`ADAPT`、`REFERENCE_ONLY`、`EXCLUDE`。

#### REUSE_AS_IS（可直接复用，仅修改 import 路径）

```
utils/robust_json_parser.py      # 容错 JSON 解析
utils/retry.py                   # 重试装饰器
utils/timer.py                   # 计时器
utils/text.py                    # 文本工具
utils/image.py                   # 图片工具
tools/image_orientation.py       # 图片方向检测
```

**处理方式**:
- 复制到 `packages/vimax-core/src/vimax_core/utils/`
- 保留原文件头部版权声明
- 添加来源注释：`# Adapted from ViMax (https://github.com/HKUDS/ViMax) - MIT License`
- 编写单元测试验证功能正确性

#### ADAPT（需适配商家事实、租户隔离、版本化）

```
agents/screenwriter.py                      → vimax_core/agents/screenwriter.py
agents/storyboard_artist.py                 → vimax_core/agents/storyboard_artist.py
agents/character_extractor.py               → vimax_core/agents/character_extractor.py
agents/scene_extractor.py                   → vimax_core/agents/scene_extractor.py
agents/reference_image_selector.py          → vimax_core/agents/reference_image_selector.py
agents/best_image_selector.py               → vimax_core/agents/best_image_selector.py
agents/character_portraits_generator.py     → vimax_core/agents/character_portraits_generator.py
agents/camera_image_generator.py            → vimax_core/agents/camera_image_generator.py

interfaces/character.py                     → vimax_core/schemas/character.py
interfaces/scene.py                         → vimax_core/schemas/scene.py
interfaces/camera.py                        → vimax_core/schemas/camera.py
interfaces/shot_description.py              → vimax_core/schemas/shot_description.py
interfaces/frame.py                         → vimax_core/schemas/frame.py
interfaces/image_output.py                  → vimax_core/schemas/image_output.py
interfaces/video_output.py                  → vimax_core/schemas/video_output.py

utils/video.py                              → vimax_core/media/video.py（FFmpeg 封装）
tools/image_response.py                     → vimax_core/responses/image_response.py

pipelines/idea2video_pipeline.py            → 拆解为独立 Activity
pipelines/script2video_pipeline.py          → 拆解为独立 Activity
```

**改造要求**:
1. **移除本地状态依赖**
   - 不读写 `working_dir` JSON 文件
   - 输入来自函数参数（Pydantic Model）
   - 输出返回结构化对象，不写入本地

2. **注入商家事实快照**
   - 所有 Agent 接受 `MerchantFactSnapshot` 参数
   - Prompt 中引用商家关键事实（地址、价格、营业时间、套餐）
   - 输出中标记使用的 `fact_id`

3. **注入租户和追踪信息**
   ```python
   class ActivityInput(BaseModel):
       tenant_id: str
       project_id: str
       workflow_run_id: str
       activity_id: str
       trace_id: str
       schema_version: str
       prompt_version: str
       fact_snapshot_version: str
       # ... 业务输入
   ```

4. **替换供应商调用为平台客户端**
   ```python
   # 原 ViMax
   from tools import ImageGeneratorYunwuAPI
   generator = ImageGeneratorYunwuAPI(api_key=config.api_key)
   
   # 改造后
   from vimax_core.clients import PlatformImageGenerator
   generator = PlatformImageGenerator(gateway_url=config.gateway_url)
   ```

5. **Prompt 版本化**
   - 每个 Agent 的系统 Prompt 提取到独立模板文件
   - 模板文件命名：`prompts/screenwriter_v1.jinja2`
   - 记录 `prompt_version` 到输出

6. **严格 Schema 校验**
   - 模型输出必须通过 Pydantic 校验才能返回
   - 校验失败抛出异常，不返回部分结果

#### REFERENCE_ONLY（参考思路，不直接复制代码）

```
agent_runtime/                   # Agent 运行时（参考会话管理、工具调用思路）
main_agent.py                    # Agent 入口（参考交互式规划）
tools/protocols.py               # 工具协议（参考抽象设计）
tools/render_backend.py          # 渲染后端（参考异步任务抽象）
utils/rate_limiter.py            # 速率限制（参考限流思路，实际用 Redis）
web/                             # Web UI（参考 UI 交互，不复用代码）
```

**处理方式**:
- 阅读代码理解设计思想
- 在企业级架构中重新实现
- 不复制源代码
- 在文档中引用 ViMax 相关设计

#### EXCLUDE（不复用，明确排除）

```
tools/image_generator_yunwu_*.py            # Yunwu 供应商实现
tools/image_generator_openrouter_api.py     # OpenRouter 供应商实现
tools/image_generator_google_api.py         # Google 供应商实现
tools/video_generator_yunwu_*.py            # Yunwu 供应商实现
tools/video_generator_openrouter_api.py     # OpenRouter 供应商实现
tools/video_generator_google_api.py         # Google 供应商实现
utils/provider_presets.py                   # 供应商预设配置
configs/agent.example.yaml                  # 用户配置模板
configs/idea2video.yaml                     # 工作流配置模板
main_idea2video.py                          # 单机入口
main_script2video.py                        # 单机入口
vimax                                       # CLI 工具
```

**原因**:
- 供应商实现与本项目技术决策（统一 FluAPI/ToAPIs）冲突
- 用户配置暴露 API Key，违反安全原则
- 单机入口不适合多租户 SaaS
- CLI 工具不符合 Web 平台交互模式

### 3.2 目标位置映射

```
ViMax 原位置                              → 梧曜星枢目标位置
--------------------------------------------------
agents/*.py                             → packages/vimax-core/src/vimax_core/agents/*.py
interfaces/*.py                         → packages/vimax-core/src/vimax_core/schemas/*.py
utils/*.py                              → packages/vimax-core/src/vimax_core/utils/*.py
pipelines/*.py                          → packages/vimax-core/src/vimax_core/activities/*.py
tools/image_*.py (非供应商)             → packages/vimax-core/src/vimax_core/responses/*.py

（新建）                                 → packages/vimax-core/src/vimax_core/clients/
                                           - platform_text_model.py
                                           - platform_vision_model.py
                                           - platform_image_generator.py
                                           - platform_video_generator.py

（新建）                                 → apps/ai-worker/
                                           - main.py (FastAPI + RabbitMQ Consumer)
                                           - activities/ (调用 vimax-core)
                                           - quality/ (技术/语义质检)
                                           - media/ (FFmpeg 合成)

（新建）                                 → apps/platform-api/src/main/java/com/wuyao/nexus/
                                           - gateway/ (AI Gateway)
                                           - adapters/fluapi/
                                           - adapters/toapis/
                                           - workflow/ (状态机)
                                           - outbox/ (Transactional Outbox)
```

---

## 4. 许可证合规

### 4.1 ViMax 许可证

✅ **MIT License**
- 允许商业使用
- 允许修改
- 允许分发
- 允许私有使用
- **必须保留版权声明**
- **必须包含许可证副本**

### 4.2 合规要求

✅ **必须完成**
1. 在复用的每个源文件头部保留 ViMax 原始版权声明
2. 创建 `THIRD_PARTY_NOTICES.md`，内容包括：
   - ViMax 项目 URL
   - 固定 commit SHA (05a48943878312d88fe5a016c12a9654940ecc43)
   - MIT License 全文
   - 复用文件列表及改造说明
3. 在 `packages/vimax-core/README.md` 中引用上游项目
4. 不删除源文件中的版权声明和许可证注释

### 4.3 依赖许可证扫描

需要在阶段 0 完成 Python 和 Java 依赖许可证扫描，确保无强传染性依赖（GPL、AGPL）被无意引入。

推荐工具：
- Python: `pip-licenses`
- Java: `mvn license:add-third-party`

---

## 5. 差距分析总结

### 5.1 架构差距

| 层级 | 现有平台 | ViMax | 目标架构 | 差距 |
|------|---------|-------|---------|------|
| **前端** | ✅ Vue 3 + Mock | ✅ React Web UI | ✅ 保留 Vue 3，融入 ViMax 交互思路 | 需新增视频工作流页面 |
| **业务 API** | ✅ Spring Boot 3 骨架 | ❌ 不存在 | ✅ Spring Boot 3 完整实现 | 需新增工作流状态机、AI Gateway、Outbox |
| **AI Worker** | ❌ 不存在 | ✅ Python Agent Runtime | ✅ Python FastAPI + RabbitMQ Consumer | **完全新建** |
| **ViMax Core** | ❌ 不存在 | ✅ Agents/Pipelines | ✅ 无状态 Activity 包 | **抽取改造** |
| **队列** | ❌ 不存在 | ❌ asyncio.gather | ✅ RabbitMQ | **完全新建** |
| **对象存储** | ❌ 不存在 | ❌ 本地文件 | ✅ MinIO/S3 | **完全新建** |
| **数据库** | ✅ 基础表 | ❌ 本地 JSON | ✅ PostgreSQL 完整 Schema | 需新增 30+ 表 |
| **Provider 适配** | ❌ 不存在 | ✅ Yunwu/OpenRouter/Google | ✅ FluAPI/ToAPIs | **完全重写** |

### 5.2 功能差距

| 功能模块 | 现有平台 | ViMax | 目标 | 差距评估 |
|---------|---------|-------|------|---------|
| **商家事实快照** | ❌ 未实现 | ❌ 不存在 | ✅ 必须 | **新建表+逻辑** |
| **创意规划** | ✅ Mock Compiler | ✅ Idea2Video 前半段 | ✅ 结合两者优势 | 需适配 |
| **脚本生成** | ❌ 未实现 | ✅ Screenwriter | ✅ 注入事实快照 | 需适配 |
| **分镜设计** | ❌ 未实现 | ✅ Storyboard Artist | ✅ 注入事实快照 | 需适配 |
| **参考图生成** | ❌ 未实现 | ✅ Image Generator | ✅ FluAPI Image 2.0 | 需适配+新建 Gateway |
| **参考图筛选** | ❌ 未实现 | ✅ BestImageSelector | ✅ 视觉 QA | 需适配 |
| **镜头视频生成** | ❌ 未实现 | ✅ Video Generator | ✅ ToAPIs Seedance 2.0 | 需适配+新建 Gateway |
| **视频合成** | ❌ 未实现 | ✅ FFmpeg Concat | ✅ 字幕+音轨+BGM | 需适配 |
| **质量检查** | ✅ Mock QA | ❌ 隐式检查 | ✅ 技术/语义/事实 QA | 需新建 |
| **人工审核** | ❌ 未实现 | ❌ 不存在 | ✅ 强制审核点 | 需新建 |
| **镜头重试** | ❌ 未实现 | ❌ 不支持 | ✅ 单镜头版本化 | 需新建 |
| **任务恢复** | ❌ 未实现 | ⚠️ 会话恢复（Agent Loop） | ✅ 崩溃恢复+轮询恢复 | 需新建 |
| **成本账本** | ✅ Mock Ledger | ❌ 不存在 | ✅ 追踪每次调用 | 需新建 |
| **Prompt 版本化** | ❌ 未实现 | ❌ 硬编码 | ✅ 模板+版本号 | 需新建 |

### 5.3 阻塞风险

🔴 **高优先级阻塞**
1. **FluAPI 和 ToAPIs 真实 API 文档缺失**
   - 无法完成真实请求/响应映射
   - 无法验证鉴权、错误码、用量统计、回调签名
   - **缓解措施**: 先完成契约测试骨架，用脱敏 fixture 验证，标记为"待真实验证"

2. **跨技术栈集成复杂度**
   - Vue → Spring Boot → RabbitMQ → Python Worker → Spring AI Gateway → FluAPI/ToAPIs
   - **缓解措施**: 分阶段交付，每阶段独立验收

3. **ViMax 无状态改造工作量**
   - 需改造 15+ Agent 和 Pipeline
   - **缓解措施**: 优先改造核心链路（Screenwriter → Storyboard → ReferenceImageSelector → Video）

⚠️ **中优先级风险**
1. 商家事实快照设计需要与业务方确认关键字段
2. Prompt 注入防御需要在每个 Agent 中加入分隔符
3. FFmpeg 合成参数需要实际视频测试调优

✅ **可控风险**
1. 前端页面开发（已有设计体系）
2. Spring Boot 业务逻辑（已有骨架）
3. 单元测试和集成测试（已有测试基线）

---

## 6. 推荐实施路径

### 6.1 阶段 0：审计与准备（当前）

✅ **已完成**
- 现有平台仓库审计
- ViMax 上游审计
- 复用矩阵输出
- 许可证合规检查

⏳ **待完成**
- [ ] 输出架构决策记录（ADR）
- [ ] 依赖许可证扫描
- [ ] 创建 `THIRD_PARTY_NOTICES.md`
- [ ] 创建开发分支 `feat/enterprise-vimax-video-workflow`

### 6.2 阶段 1-10 优先级

根据实施方案第 21 章，推荐执行顺序：

**关键路径**（阻塞后续阶段）:
1. 阶段 1：企业级基础设施（RabbitMQ + Outbox/Inbox + PostgreSQL 完整表）
2. 阶段 2：商家快照与素材底座
3. 阶段 3：ViMax Core 抽取
4. 阶段 4：AI Gateway 和 Provider Contract

**核心能力**（可并行）:
5. 阶段 5：创意、脚本、分镜工作流
6. 阶段 6：Image 2.0 参考图链路
7. 阶段 7：Seedance 2.0 镜头链路

**完整闭环**（依赖前置阶段）:
8. 阶段 8：质检、合成和人工审核
9. 阶段 9：商业化保护和管理端
10. 阶段 10：全链路测试、部署与交付

### 6.3 最小可验证路径（MVP）

如果需要快速验证核心链路，推荐 MVP 范围：

```
选择商家
→ 冻结事实快照（仅价格、地址、营业时间）
→ 一句话需求
→ 生成 1 条创意
→ 生成脚本（3 个镜头）
→ 生成分镜
→ FluAPI Image 2.0 生成 3 张参考图（契约测试）
→ 人工选择参考图
→ ToAPIs Seedance 2.0 生成 3 个镜头视频（契约测试）
→ FFmpeg 合成（无字幕、无音轨）
→ 人工审核
→ 保存作品库
```

MVP 验收标准：
- 使用 Mock Provider 可跑通完整流程
- 使用契约测试 fixture 可模拟真实 Provider
- 状态机、队列、对象存储、成本账本真实运行
- Worker 崩溃后可从数据库恢复

---

## 7. 建议与注意事项

### 7.1 技术决策

✅ **必须遵守**
1. **不推倒现有平台** - 在现有 Vue 3 + Spring Boot 3 基础上增量开发
2. **严格多租户隔离** - 所有表必须有 `tenant_id`，所有查询必须带租户条件
3. **统一 AI Gateway** - 业务层不直接调用 FluAPI/ToAPIs，只调用内部能力接口
4. **持久化状态机** - PostgreSQL 是唯一业务状态源，不依赖内存或本地文件
5. **Transactional Outbox** - 避免"数据库提交成功但消息丢失"

⚠️ **强烈建议**
1. **优先契约测试** - 在缺少真实 API 文档时，用 fixture 验证接口设计
2. **Prompt 版本化** - 每次修改 Prompt 必须升级版本号并记录
3. **防提示词注入** - 商家知识库内容与系统指令明确分隔
4. **镜头级幂等** - 重复调用不重复生成和扣费
5. **供应商产物转存** - 不依赖供应商临时 URL

❌ **禁止事项**
1. **禁止 ViMax 供应商实现直接用于生产** - Yunwu/OpenRouter/Google 适配器只能作为参考
2. **禁止用户配置 API Key** - 所有密钥只能在服务端
3. **禁止前端直连供应商** - 所有 AI 调用必须经过 Platform API
4. **禁止本地 JSON 作为状态源** - 工作流状态必须在数据库
5. **禁止将 Mock 数据标记为生产就绪** - 必须明确区分 Mock 和真实调用

### 7.2 质量保障

每个阶段必须完成：
- ✅ 单元测试覆盖核心逻辑
- ✅ 集成测试验证 Outbox/Inbox/队列/对象存储
- ✅ 契约测试验证 OpenAPI/AsyncAPI/Provider 接口
- ✅ 数据库迁移可前进、可回滚
- ✅ Git commit 使用 Conventional Commits
- ✅ 代码通过 Lint/格式化/类型检查

### 7.3 交付标准

**定义完成（Definition of Done）**需同时满足：
- ✅ 真实业务表和迁移存在
- ✅ 状态机、队列、Outbox/Inbox 真实运行
- ✅ ViMax Core 不依赖本地状态和供应商 Key
- ✅ FluAPI 和 ToAPIs 适配层完整（至少契约测试通过）
- ✅ 商家事实快照、事实引用和阻断规则生效
- ✅ 支持镜头级重试、版本、成本和恢复
- ✅ FFmpeg 成片、最终 QA 和人工审核真实运行
- ✅ 多租户、RBAC、审计和下载权限通过测试
- ✅ 关键监控、告警、Runbook 和部署文档齐全
- ✅ 没有假按钮、假状态、假成功

---

## 8. 附录

### 8.1 ViMax 核心文件统计

```
agents/         14 个文件
interfaces/     10 个文件
pipelines/       3 个文件
tools/          15 个文件
utils/           9 个文件
agent_runtime/   2 个文件
```

### 8.2 需新建的核心表（PostgreSQL）

```sql
-- 工作流核心表
workflow_definitions
workflow_runs
workflow_steps
workflow_step_attempts

-- 商家事实
merchant_facts
merchant_fact_snapshots
merchant_fact_snapshot_items

-- 素材授权
assets
asset_authorizations

-- 创作产物
creative_variants
video_scripts
storyboards
shots
shot_revisions
prompt_artifacts

-- 供应商任务
generation_tasks
provider_jobs

-- 质检与审核
quality_reports
review_records

-- 作品库
work_assets

-- 成本与额度
cost_ledger
quota_reservations

-- 消息与审计
outbox_events
inbox_messages
audit_logs
```

### 8.3 关键 ADR 主题

建议创建以下架构决策记录（`docs/adr/`）：

1. `001-adopt-vimax-core-with-stateless-adaptation.md`
2. `002-unified-ai-gateway-for-fluapi-toapis.md`
3. `003-transactional-outbox-for-rabbitmq.md`
4. `004-merchant-fact-snapshot-immutability.md`
5. `005-prompt-versioning-and-injection-prevention.md`
6. `006-shot-level-retry-and-version-control.md`
7. `007-provider-response-persistence-strategy.md`
8. `008-cost-ledger-append-only-design.md`

---

## 9. 审计结论

### 9.1 可行性评估

✅ **技术可行**
- ViMax 核心创作链路可复用，许可证允许商业使用
- 现有平台已具备前端基线和后端骨架，无需推倒重来
- Spring Boot 3 + Python FastAPI + RabbitMQ + PostgreSQL 技术栈成熟可靠

⚠️ **需要大量工程**
- 跨 10 个阶段、涉及前端、Java 后端、Python Worker、队列、对象存储
- ViMax 核心需要无状态改造，估计 15+ Agent 和 Pipeline
- 需新建 30+ 数据库表、AI Gateway、Provider 适配器、Outbox/Inbox

🔴 **依赖外部条件**
- **FluAPI 和 ToAPIs 真实 API 文档**是完成真实调用的前置条件
- 如果文档缺失，只能完成契约测试，无法标记为"生产就绪"

### 9.2 风险等级

| 风险类别 | 等级 | 缓解措施 |
|---------|------|---------|
| 许可证合规 | 🟢 低 | MIT 许可证友好，已明确合规要求 |
| 技术栈兼容性 | 🟢 低 | 现有技术栈与目标技术栈一致 |
| ViMax 适配工作量 | 🟡 中 | 分阶段交付，优先核心链路 |
| 真实 Provider 验证 | 🔴 高 | 优先完成契约测试，明确标记未验证部分 |
| 跨技术栈集成 | 🟡 中 | 每阶段独立验收，逐步集成 |
| 商家事实快照设计 | 🟡 中 | 与业务方确认关键字段后再实施 |

### 9.3 最终建议

**推荐执行**: 按照实施方案分 10 个阶段逐步完成，每个阶段必须通过验收门禁后才能进入下一阶段。

**关键成功因素**:
1. 严格执行阶段门禁，不跳过测试和文档
2. 优先完成契约测试，明确标记"待真实验证"
3. 保持现有平台代码的完整性，增量开发
4. 每个阶段输出可回滚的语义化 Git commit
5. 及时更新 `PROJECT_MEMORY.md` 和审计报告

**交付预期**:
- **契约验证完成**（无真实 API 文档）: 内部架构及契约已完成，真实供应商连通性未验收
- **真实验证完成**（有真实 API 文档 + Key）: 配置真实 API 后可生成 Image 2.0 图片和 Seedance 2.0 视频

---

**审计报告完成时间**: 2026-08-26  
**审计人签名**: Claude Code (Opus 5)  
**下一步**: 创建开发分支并进入阶段 1 实施
