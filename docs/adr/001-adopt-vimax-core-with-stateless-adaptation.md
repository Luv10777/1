# ADR 001: 采用 ViMax Core 并进行无状态改造

**状态**: 已批准  
**日期**: 2026-08-26  
**决策者**: 架构团队  
**影响范围**: AI 视频工作流核心引擎

---

## 背景

梧曜星枢需要构建企业级 AI 视频工作流，支持从"一句话需求"到"完整视频"的全自动生成链路。核心挑战包括：

1. 创作链路复杂：需要创意规划、脚本生成、分镜设计、参考图生成、视频生成、合成
2. 视觉一致性：角色、场景、服饰需要跨镜头保持连续性
3. 结构化输出：每个环节需要严格的 Schema 校验，不能依赖自由文本
4. 多租户 SaaS：必须支持租户隔离、商家事实快照、幂等、恢复、审计

开源项目 [ViMax (v1.2.0)](https://github.com/HKUDS/ViMax) 提供了成熟的 Idea2Video 和 Script2Video 链路，但其设计面向单机使用，存在以下问题：
- 使用本地 `working_dir` 作为状态源
- 通过文件是否存在判断业务步骤完成
- 用户在 YAML 配置文件中直接填写 API Key
- 使用 `asyncio.gather` 作为唯一调度方案
- 无租户隔离、无商家事实约束、无幂等保护

## 决策

**我们决定采用 ViMax Core 的创作链路和 Pydantic Schema，但进行无状态改造以适应多租户 SaaS 架构。**

### 采用范围

✅ **复用**
- Agents: `screenwriter`, `storyboard_artist`, `character_extractor`, `scene_extractor`, `reference_image_selector`, `best_image_selector`
- Interfaces: 所有 Pydantic Schema (`character.py`, `scene.py`, `camera.py`, `shot_description.py` 等)
- Utils: `robust_json_parser`, `retry`, `timer`, `text`, `image`, `video`
- 核心思想: Idea2Video 链路、参考图管理、镜头连续性设计、FFmpeg 合成

❌ **排除**
- 供应商实现: `*_yunwu_*.py`, `*_openrouter_*.py`, `*_google_*.py`
- 本地配置: `configs/*.yaml`
- 单机入口: `main_*.py`, `vimax` CLI
- Web UI: `web/` (使用自有 Vue 3 技术栈)
- Agent Runtime: `agent_runtime/` (使用 FastAPI + RabbitMQ)

### 改造策略

#### 1. 移除本地状态依赖

**原 ViMax**:
```python
# 读取本地 JSON 判断步骤是否完成
script_file = working_dir / "script.json"
if script_file.exists():
    with open(script_file) as f:
        script = json.load(f)
```

**改造后**:
```python
# 输入来自函数参数，输出返回对象，不读写本地文件
def write_script(input: ScriptInput) -> VideoScript:
    # ... 调用模型生成
    return VideoScript(
        tenant_id=input.tenant_id,
        project_id=input.project_id,
        workflow_run_id=input.workflow_run_id,
        shots=[...],
        schema_version="1.0.0"
    )
```

#### 2. 注入商家事实快照

**新增输入**:
```python
class ScriptInput(BaseModel):
    tenant_id: str
    project_id: str
    workflow_run_id: str
    trace_id: str
    merchant_fact_snapshot: MerchantFactSnapshot  # 商家地址、价格、营业时间、套餐
    creative_variant: CreativeVariant
    schema_version: str
    prompt_version: str
```

**Prompt 模板**:
```jinja2
你是专业编剧，负责为商家生成视频脚本。

## 商家事实（必须严格遵守）
- 商家名称: {{ facts.name }}
- 地址: {{ facts.address }}
- 套餐价格: {{ facts.packages | json }}
- 营业时间: {{ facts.business_hours }}

## 禁止事项
- 不得编造价格、地址、营业时间
- 不得添加不存在的套餐或服务
- 必须在输出中标记每项事实的 fact_id

## 创意方向
{{ creative_variant.angle }}
```

#### 3. 替换供应商调用为平台客户端

**原 ViMax**:
```python
from tools import ImageGeneratorYunwuAPI

generator = ImageGeneratorYunwuAPI(
    api_key=config.api_key,
    base_url=config.base_url
)
result = generator.generate(prompt)
```

**改造后**:
```python
from vimax_core.clients import PlatformImageGenerator

generator = PlatformImageGenerator(
    gateway_url=settings.platform_gateway_url
)
result = generator.generate(
    request=ImageGenerationRequest(
        tenant_id=context.tenant_id,
        workflow_run_id=context.workflow_run_id,
        activity_id=context.activity_id,
        trace_id=context.trace_id,
        idempotency_key=context.idempotency_key,
        prompt_spec=prompt_spec
    )
)
```

#### 4. Prompt 版本化

提取 Prompt 到独立模板文件：
```
packages/vimax-core/src/vimax_core/prompts/
├── screenwriter_v1.jinja2
├── storyboard_artist_v1.jinja2
├── reference_image_selector_v1.jinja2
└── ...
```

每次修改 Prompt 必须升级版本号并记录 Changelog。

#### 5. 输入输出标准化

所有 Activity 统一接口：
```python
class ActivityInput(BaseModel):
    """所有 Activity 的基础输入"""
    tenant_id: str
    project_id: str
    workflow_run_id: str
    activity_id: str
    trace_id: str
    schema_version: str = "1.0.0"
    prompt_version: str

class ActivityOutput(BaseModel):
    """所有 Activity 的基础输出"""
    tenant_id: str
    activity_id: str
    status: Literal["SUCCEEDED", "FAILED"]
    output_refs: list[str]  # 对象存储 Key
    prompt_version_used: str
    model_alias_used: str
    usage: dict
    error: Optional[dict]
```

### 目标架构

```
packages/vimax-core/                   # 纯 Python 包，无状态
├── src/vimax_core/
│   ├── agents/                        # 改造后的 Agent
│   │   ├── screenwriter.py
│   │   ├── storyboard_artist.py
│   │   └── ...
│   ├── schemas/                       # Pydantic Schema
│   │   ├── character.py
│   │   ├── scene.py
│   │   └── ...
│   ├── clients/                       # 平台客户端（调用 Spring AI Gateway）
│   │   ├── platform_text_model.py
│   │   ├── platform_image_generator.py
│   │   └── platform_video_generator.py
│   ├── utils/                         # 工具函数
│   ├── prompts/                       # Prompt 模板
│   └── __init__.py
└── tests/

apps/ai-worker/                        # FastAPI + RabbitMQ Consumer
├── main.py
├── activities/                        # 调用 vimax_core
│   ├── creative_planning.py
│   ├── script_writing.py
│   ├── storyboard_design.py
│   └── ...
├── quality/                           # 质检
└── media/                             # FFmpeg 合成

apps/platform-api/                     # Spring Boot
└── src/main/java/com/wuyao/nexus/
    ├── gateway/                       # AI Gateway
    ├── adapters/
    │   ├── fluapi/                    # FluAPI 适配器
    │   └── toapis/                    # ToAPIs 适配器
    └── workflow/                      # 状态机
```

## 后果

### 优势

✅ **快速获得成熟创作链路**
- ViMax 已在 Idea2Video/Script2Video 中验证，代码质量高
- 节省从零开发编剧、分镜、参考图选择等核心 Agent 的时间

✅ **结构化输出保障**
- 继承 ViMax 的 Pydantic Schema，确保每个环节输出可校验
- 容错 JSON 解析器提高 LLM 输出鲁棒性

✅ **视觉一致性设计**
- 参考图管理、Continuity Token、首尾帧约束等机制成熟

✅ **MIT 许可证友好**
- 允许商业使用、修改、分发
- 只需保留版权声明

### 劣势与缓解

⚠️ **改造工作量大**
- 需改造 15+ Agent 和 Pipeline
- **缓解**: 分阶段实施，优先核心链路（Screenwriter → Storyboard → Reference → Video）

⚠️ **供应商适配器全部重写**
- ViMax 的 Yunwu/OpenRouter/Google 实现不能用于生产
- **缓解**: 参考其抽象设计，独立实现 FluAPI/ToAPIs 适配器

⚠️ **Prompt 需要调优**
- ViMax Prompt 面向通用故事创作，需调整为商家推广场景
- **缓解**: Prompt 版本化，逐步迭代优化

⚠️ **测试覆盖要求高**
- 无状态改造后必须确保功能正确性
- **缓解**: 每个 Agent 编写单元测试，使用 fixture 验证输入输出

### 风险

🔴 **ViMax 版本演进**
- 上游持续更新，我们固定在 v1.2.0 (commit 05a4894)
- **应对**: 定期评估上游更新，谨慎合并改进

🟡 **商家场景适配**
- ViMax 面向故事/小说/脚本，我们面向本地生活商家推广
- **应对**: 通过商家事实快照和 Prompt 定制化解决

## 替代方案

### 方案A: 从零开发（已拒绝）

**优势**: 完全可控  
**劣势**: 开发周期长（估计 6-12 个月），质量无保障  
**决策**: 拒绝，时间成本过高

### 方案B: 使用其他开源项目（已拒绝）

**候选**: MoviePy, Manim, VideoCrewAI  
**劣势**: 
- MoviePy 只是视频处理库，无创作链路
- Manim 面向数学动画，不适合商家推广
- VideoCrewAI 成熟度不如 ViMax

**决策**: 拒绝，ViMax 是当前最佳选择

### 方案C: 直接使用 ViMax TUI/Web（已拒绝）

**劣势**: 
- 无租户隔离
- 无商家事实约束
- 无审核流程
- 用户直接配置 API Key（安全风险）

**决策**: 拒绝，不符合企业级 SaaS 要求

## 相关决策

- [ADR 002: 统一 AI Gateway](./002-unified-ai-gateway-for-fluapi-toapis.md)
- [ADR 004: 商家事实快照不可变性](./004-merchant-fact-snapshot-immutability.md)
- [ADR 005: Prompt 版本化与注入防御](./005-prompt-versioning-and-injection-prevention.md)

## 参考资料

- [ViMax GitHub](https://github.com/HKUDS/ViMax)
- [ViMax Technical Report](https://arxiv.org/abs/2606.07649)
- [差距分析报告](../audits/vimax-enterprise-gap-analysis.md)
- [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)

---

**批准人**: 技术总监  
**生效日期**: 2026-08-26
