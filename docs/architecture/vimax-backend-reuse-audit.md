# ViMax 后端链路复用审计报告

> **审计日期**: 2026-08-25  
> **审计目标**: 分析 ViMax 开源项目，找出可复用到梧曜星枢"AI视频创作工作流"的后端代码  
> **审计范围**: 从用户输入到视频输出的完整链路  
> **审计方法**: 静态代码审计 + 调用链追踪 + 复用等级评估

---

## 执行摘要

### 核心结论

**ViMax 不能整体作为梧曜星枢的后端服务使用**，但其**核心视频生成链路、数据结构设计和 Provider 抽象层具有高复用价值**。

建议采用**模块化抽取+改造**策略：
- **直接复用**：数据契约（Character、Shot、Camera）、Provider 协议、FFmpeg 拼接
- **改造复用**：Pipeline 编排思想、异步任务模式、参考图选择逻辑
- **只参考设计**：本地文件状态管理、Agent Loop 交互、断点恢复机制
- **必须重写**：租户隔离、数据库持久化、任务队列、商家资料集成

---

## 一、两个仓库的真实现状

### 1.1 ViMax 仓库状态

| 项目 | 值 |
|------|-----|
| **GitHub** | https://github.com/HKUDS/ViMax |
| **固定版本** | v1.2.0 |
| **Commit SHA** | `05a48943878312d88fe5a016c12a9654940ecc43` |
| **提交时间** | 2026-07-29 16:56:44 +0800 |
| **许可证** | MIT License |
| **语言** | Python 3.12+ |
| **核心依赖** | langchain 0.3+, moviepy 2.2+, aiohttp 3.12+, pydantic, asyncio |
| **技术架构** | 桌面/CLI 工具，本地文件状态管理，异步 Pipeline |
| **代码规模** | 核心代码约 6,453 行（pipelines + agents + tools） |

**关键特性**：
- ✅ Idea2Video 和 Script2Video 完整流程
- ✅ Character/Scene/Shot/Camera 结构化数据模型
- ✅ 多镜头并行生成（asyncio.gather）
- ✅ 首帧/尾帧参考图生成
- ✅ FFmpeg 视频拼接
- ✅ Provider 协议抽象（ImageGenerator / VideoGenerator）
- ✅ 本地工作目录断点恢复（检查文件是否存在）
- ⚠️ **工作目录状态管理**（JSON文件存储，无数据库）
- ⚠️ **进程内并发**（asyncio，非持久化队列）
- ❌ 无租户隔离
- ❌ 无服务端 API
- ❌ 无任务持久化和恢复（依赖本地文件）

### 1.2 梧曜星枢当前状态

| 项目 | 值 |
|------|-----|
| **本地目录** | C:\Users\Administrator\梧曜AI |
| **GitHub** | https://github.com/Luv10777/1 |
| **当前分支** | main |
| **Commit SHA** | `844e9d4ea8ba670eff07b3cde670b9d371a42442` |
| **提交时间** | 合并 Phase 1 功能基础 |
| **前端技术栈** | Vue 3 + Vite + Vue Router |
| **后端技术栈** | Spring Boot 3 + Java 21（已有骨架） |
| **数据库** | PostgreSQL 14+（计划中） |
| **消息队列** | RabbitMQ（计划中） |

**已完成模块**：
- ✅ Vue 3 前端工程（22个路由）
- ✅ Spring Boot 后端骨架（server/ 目录存在）
- ✅ 认证/商家/门店基础接口（Controller + Service）
- ✅ Mock 登录和路由守卫
- ✅ 深色星枢控制台视觉系统
- ✅ 前端 Mock 工作流状态机
- ✅ 34/34 单元测试通过

**明确未接通**：
- ❌ 后端 AI 模型调用（FluAPI/ToAPIs）
- ❌ 视频生成链路（Seedance 2.0/2.5）
- ❌ 图片生成链路（Image 2.0）
- ❌ 对象存储（MinIO/COS）
- ❌ 异步任务队列（RabbitMQ）
- ❌ 视频合成工作流（FFmpeg）
- ❌ 商家资料快照系统

---

## 二、ViMax 后端架构分析

### 2.1 完整调用链

```
用户输入（idea / script）
  ↓
main_idea2video.py / main_script2video.py
  ↓
Idea2VideoPipeline / Script2VideoPipeline (pipelines/)
  ↓
[文本生成阶段]
  ├─ Screenwriter.develop_story() → LLM
  ├─ Screenwriter.write_script_based_on_story() → LLM
  ├─ CharacterExtractor.extract_characters() → LLM
  ├─ StoryboardArtist.design_storyboard() → LLM
  └─ StoryboardArtist.decompose_visual_descriptions() → LLM
  ↓
[结构化数据]
  ├─ CharacterInScene (interfaces/character.py)
  ├─ ShotBriefDescription (interfaces/shot_description.py)
  ├─ ShotDescription (interfaces/shot_description.py)
  └─ Camera (interfaces/camera.py)
  ↓
[并行生成阶段]
  ├─ generate_character_portraits() → ImageGenerator
  │   ├─ generate_front_portrait()
  │   ├─ generate_side_portrait()
  │   └─ generate_back_portrait()
  │
  ├─ generate_frames_for_single_camera() [asyncio 并行]
  │   ├─ CameraImageGenerator.construct_camera_tree()
  │   ├─ ReferenceImageSelector.select_reference_images_and_generate_prompt()
  │   ├─ ImageGenerator.generate_single_image() [首帧]
  │   └─ ImageGenerator.generate_single_image() [尾帧，如需要]
  │
  └─ generate_video_for_single_shot() [asyncio 并行]
      └─ VideoGenerator.generate_single_video()
          ├─ create_video_generation_task() → ToAPIs/Seedance
          └─ query_video_generation_task() [轮询直到完成]
  ↓
[FFmpeg 合成阶段]
  └─ concatenate_video_files() → moviepy + FFmpeg
  ↓
final_video.mp4 (本地文件)
```

### 2.2 关键设计模式

#### 2.2.1 异步并行生成

```python
# Script2VideoPipeline.__call__() 第 280-301 行
tasks = [
    self.generate_frames_for_single_camera(camera, ...)
    for camera in camera_tree
]
video_tasks = [
    self.generate_video_for_single_shot(shot_description, ...)
    for shot_description in shot_descriptions
]
tasks.extend(video_tasks)
await asyncio.gather(*tasks)  # 所有镜头并行生成
```

**优点**：
- 镜头并行生成，大幅缩短总耗时
- 使用 asyncio.Event 同步依赖关系（首帧生成完才能生成视频）

**限制**：
- 进程内并发，重启后丢失进度
- 无法跨服务器分布式执行
- 内存占用与并发数成正比

#### 2.2.2 本地文件断点恢复

```python
# Script2VideoPipeline.design_storyboard() 第 745-762 行
storyboard_path = os.path.join(self.working_dir, "storyboard.json")
if os.path.exists(storyboard_path):
    with open(storyboard_path, 'r', encoding='utf-8') as f:
        storyboard = json.load(f)
    # 从文件加载，跳过 LLM 调用
else:
    # 调用 LLM 生成
    storyboard = await self.storyboard_artist.design_storyboard(...)
    # 保存到文件
    with open(storyboard_path, 'w', encoding='utf-8') as f:
        json.dump(storyboard, f, ensure_ascii=False, indent=4)
```

**优点**：
- 简单有效，避免重复调用昂贵的 LLM/图片/视频 API
- 支持手动删除中间文件重新生成单个镜头

**限制**：
- 强依赖本地文件系统，无法多实例共享
- 无法区分"生成中"和"已完成"状态
- 并发写入同一文件会冲突

#### 2.2.3 Provider 协议抽象

```python
# tools/protocols.py 第 14-35 行
@runtime_checkable
class ImageGenerator(Protocol):
    async def generate_single_image(
        self,
        prompt: str,
        reference_image_paths: List[str],
        **kwargs,
    ) -> ImageOutput: ...

@runtime_checkable
class VideoGenerator(Protocol):
    async def generate_single_video(
        self,
        prompt: str,
        reference_image_paths: List[str],
        **kwargs,
    ) -> VideoOutput: ...
```

**实现类**：
- `ImageGeneratorOpenRouterAPI` (GPT Image 2)
- `ImageGeneratorNanobananaGoogleAPI` (Gemini)
- `VideoGeneratorDoubaoSeedanceYunwuAPI` (Seedance via yunwu.ai)
- `VideoGeneratorVeoGoogleAPI` (Google Veo)
- `VideoGeneratorOpenRouterAPI` (通用 OpenRouter)

**优点**：
- 供应商中立，易于切换
- 结构化输出（ImageOutput / VideoOutput）
- 统一异步接口

---

## 三、文件级复用清单

### 3.1 数据结构与契约（A类：直接复用）

| ViMax 文件 | 目标模块 | 复用等级 | 评分 | 说明 |
|-----------|---------|---------|------|------|
| `interfaces/character.py` | `domain/video/Character.java` | **A** | 85 | CharacterInScene 可直接映射为 Java Entity |
| `interfaces/shot_description.py` | `domain/video/Shot.java` | **A** | 90 | ShotDescription/ShotBriefDescription 核心数据模型 |
| `interfaces/camera.py` | `domain/video/Camera.java` | **A** | 80 | Camera 树结构，parent_shot_idx 逻辑清晰 |
| `interfaces/image_output.py` | `domain/media/ImageOutput.java` | **A** | 75 | 输出格式抽象（url/b64/pil → url/base64/byte[]） |
| `interfaces/video_output.py` | `domain/media/VideoOutput.java` | **A** | 75 | 视频输出抽象 |

**迁移要点**：
- Python Pydantic → Java Entity + Lombok
- 字段类型映射：`List[int]` → `List<Integer>`，`Optional[str]` → `String`（nullable）
- 保留字段语义，添加数据库注解（@Entity, @Id, @Column）

### 3.2 Provider 适配器抽象（A类：直接复用协议，B类：改造实现）

| ViMax 文件 | 目标模块 | 复用等级 | 评分 | 说明 |
|-----------|---------|---------|------|------|
| `tools/protocols.py` | `providers/ImageProvider.java` | **A** | 95 | Protocol 转为 Java Interface，方法签名完全可复用 |
| `tools/video_generator_doubao_seedance_yunwu_api.py` | `providers/toapis/SeedanceAdapter.java` | **B** | 70 | **硬编码 yunwu.ai 地址**，需改为 ToAPIs 官方端点 |
| `tools/render_backend.py` | `providers/ProviderFactory.java` | **B** | 65 | 配置驱动实例化思想可复用，但 Python importlib → Java Reflection/Spring |

**关键发现**：

**⚠️ Seedance 适配器绑定了第三方中转地址**：
```python
# tools/video_generator_doubao_seedance_yunwu_api.py 第 59 行
url = "https://yunwu.ai/volc/v1/contents/generations/tasks"
```

**不能直接复用为 ToAPIs 正式适配器**。必须：
1. 替换为 ToAPIs 官方 Seedance 端点
2. 调整鉴权方式（yunwu.ai 的 Bearer Token → ToAPIs 的签名或 API Key）
3. 验证请求/响应格式差异
4. 修改错误码映射

**可复用部分**：
- `create_video_generation_task()` 的请求结构思想（t2v/i2v/i2v-flf 模型选择）
- `query_video_generation_task()` 的轮询逻辑
- 重试机制（`max_create_attempts` / `consecutive_errors`）

### 3.3 Pipeline 编排逻辑（B类：改造复用）

| ViMax 文件 | 目标模块 | 复用等级 | 评分 | 说明 |
|-----------|---------|---------|------|------|
| `pipelines/script2video_pipeline.py` | `workflows/video_creation/ScriptToVideoWorkflow.java` | **B** | 75 | 核心流程可复用，但需替换存储方式 |
| `pipelines/idea2video_pipeline.py` | `workflows/video_creation/IdeaToVideoWorkflow.java` | **B** | 70 | Idea → Story → Script → Video 流程清晰 |
| `agents/storyboard_artist.py` | `services/StoryboardService.java` | **B** | 65 | LLM Prompt 工程可参考，但 Langchain → 直接 HTTP 调用 |
| `agents/character_extractor.py` | `services/CharacterExtractionService.java` | **B** | 65 | 角色提取逻辑 |
| `agents/reference_image_selector.py` | `services/ReferenceImageService.java` | **B** | 70 | 参考图选择思想 |

**改造要点**：
1. **状态存储**：`working_dir/*.json` → PostgreSQL 表 + 对象存储 URL
2. **并发模型**：`asyncio.gather()` → RabbitMQ 任务队列 + Worker 池
3. **LLM 调用**：Langchain → FluAPI HTTP Client
4. **重试机制**：`tenacity` 装饰器 → Spring Retry 或手动实现

### 3.4 FFmpeg 视频合成（A类：直接复用逻辑）

| ViMax 文件 | 目标模块 | 复用等级 | 评分 | 说明 |
|-----------|---------|---------|------|------|
| `utils/video.py` | `workers/media/FFmpegWorker.java` | **A** | 80 | `concatenate_video_files()` 逻辑直接翻译 |

**核心代码**：
```python
# utils/video.py 第 26-44 行
def concatenate_video_files(video_paths, output_path, codec="libx264", preset="medium"):
    clips = []
    final = None
    try:
        for path in video_paths:
            clips.append(VideoFileClip(path))
        final = concatenate_videoclips(clips)
        final.write_videofile(output_path, codec=codec, preset=preset)
    finally:
        # 关键：确保释放 ffmpeg 进程和文件句柄
        if final is not None:
            final.close()
        for clip in clips:
            clip.close()
    return output_path
```

**Java 实现策略**：
- 使用 `ProcessBuilder` 调用 ffmpeg 命令行
- 或使用 `jcodec` / `Xuggler` 库
- 确保进程清理，避免句柄泄漏

### 3.5 不可复用模块（D类）

| ViMax 文件 | 原因 | 备注 |
|-----------|------|------|
| `main_agent.py` | Agent Loop 交互逻辑，绑定 CLI/TUI | 梧曜前端已有独立交互 |
| `agent_runtime/` | 会话管理、上下文压缩 | 桌面端特性，不适合 SaaS |
| `web/` | ViMax 自带前端 | 梧曜已有 Vue 前端 |
| `vimax_benchmark/` | 评测工具 | 非生产链路 |
| 所有本地文件路径硬编码 | 无租户隔离 | 必须改为对象存储 + 数据库 |

---

## 四、类与函数级复用清单（Top 20）

| 优先级 | 源文件:函数/类 | 目标模块 | 复用等级 | 评分 | 核心价值 |
|-------|--------------|---------|---------|------|---------|
| 1 | `interfaces/shot_description.py:ShotDescription` | `Shot.java` | **A** | 95 | 视频镜头核心数据模型 |
| 2 | `interfaces/character.py:CharacterInScene` | `Character.java` | **A** | 90 | 角色一致性追踪 |
| 3 | `tools/protocols.py:ImageGenerator` | `ImageProvider.java` | **A** | 95 | 图片生成协议 |
| 4 | `tools/protocols.py:VideoGenerator` | `VideoProvider.java` | **A** | 95 | 视频生成协议 |
| 5 | `utils/video.py:concatenate_video_files()` | `FFmpegWorker.concat()` | **A** | 85 | FFmpeg 拼接逻辑 |
| 6 | `interfaces/camera.py:Camera` | `Camera.java` | **A** | 85 | 摄像机树结构 |
| 7 | `pipelines/script2video_pipeline.py:Script2VideoPipeline.__call__()` | `ScriptToVideoWorkflow.execute()` | **B** | 80 | 完整流程编排 |
| 8 | `pipelines/script2video_pipeline.py:generate_frames_for_single_camera()` | `FrameGenerationService` | **B** | 75 | 单摄像机帧生成逻辑 |
| 9 | `pipelines/script2video_pipeline.py:generate_video_for_single_shot()` | `VideoTaskService.submitShot()` | **B** | 80 | 单镜头视频生成 |
| 10 | `tools/video_generator_doubao_seedance_yunwu_api.py:create_video_generation_task()` | `SeedanceAdapter.createTask()` | **B** | 65 | 任务提交结构（需改 URL） |
| 11 | `tools/video_generator_doubao_seedance_yunwu_api.py:query_video_generation_task()` | `SeedanceAdapter.pollTask()` | **B** | 70 | 轮询逻辑 |
| 12 | `agents/storyboard_artist.py:StoryboardArtist.design_storyboard()` | `StoryboardService.generate()` | **B** | 70 | 分镜设计 Prompt 工程 |
| 13 | `agents/storyboard_artist.py:StoryboardArtist.decompose_visual_descriptions()` | `ShotService.decompose()` | **B** | 70 | 视觉描述拆解 |
| 14 | `agents/reference_image_selector.py:select_reference_images_and_generate_prompt()` | `ReferenceImageService.select()` | **B** | 65 | 参考图选择算法 |
| 15 | `pipelines/script2video_pipeline.py:construct_camera_tree()` | `CameraTreeService.construct()` | **B** | 70 | 摄像机依赖关系构建 |
| 16 | `interfaces/image_output.py:ImageOutput` | `ImageOutput.java` | **A** | 80 | 图片输出抽象 |
| 17 | `interfaces/video_output.py:VideoOutput` | `VideoOutput.java` | **A** | 80 | 视频输出抽象 |
| 18 | `utils/video.py:download_video()` | `MediaDownloadService.download()` | **B** | 60 | 视频下载逻辑 |
| 19 | `agents/character_extractor.py:extract_characters()` | `CharacterService.extract()` | **B** | 65 | 角色提取 |
| 20 | `pipelines/idea2video_pipeline.py:Idea2VideoPipeline.__call__()` | `IdeaToVideoWorkflow.execute()` | **B** | 70 | Idea 转 Video 流程 |

---

## 五、复用等级分类统计

### A类：可以直接迁移（7项）

| 模块 | 文件 | 迁移工作量 |
|------|------|-----------|
| 数据模型 | `interfaces/character.py` | 2小时（Python → Java Entity） |
| 数据模型 | `interfaces/shot_description.py` | 3小时（复杂字段映射） |
| 数据模型 | `interfaces/camera.py` | 2小时 |
| 数据模型 | `interfaces/image_output.py` | 1小时 |
| 数据模型 | `interfaces/video_output.py` | 1小时 |
| 协议接口 | `tools/protocols.py` | 2小时（Protocol → Java Interface） |
| 工具函数 | `utils/video.py:concatenate_video_files()` | 4小时（moviepy → ProcessBuilder） |

**合计工作量**: 约 15小时  
**复用代码行数**: 约 500行（数据模型） + 50行（FFmpeg）

### B类：可以抽取后改造（13项）

| 模块 | 文件 | 主要改造点 | 工作量 |
|------|------|-----------|--------|
| Pipeline | `pipelines/script2video_pipeline.py` | 状态存储 → 数据库，asyncio → RabbitMQ | 40小时 |
| Pipeline | `pipelines/idea2video_pipeline.py` | 同上 | 30小时 |
| Provider | `tools/video_generator_doubao_seedance_yunwu_api.py` | yunwu.ai → ToAPIs 官方 | 16小时 |
| Agent | `agents/storyboard_artist.py` | Langchain → FluAPI HTTP | 20小时 |
| Agent | `agents/character_extractor.py` | 同上 | 12小时 |
| Agent | `agents/reference_image_selector.py` | 同上 | 12小时 |
| Agent | `agents/camera_image_generator.py` | 同上 | 16小时 |
| Service | `pipelines/script2video_pipeline.py:generate_frames_for_single_camera()` | 事件同步 → 数据库状态 | 20小时 |
| Service | `pipelines/script2video_pipeline.py:generate_video_for_single_shot()` | 并发 → 任务队列 | 16小时 |
| Service | `pipelines/script2video_pipeline.py:construct_camera_tree()` | 数据结构保留，存储改造 | 8小时 |
| Factory | `tools/render_backend.py` | importlib → Spring Bean Factory | 8小时 |
| Utils | `utils/video.py:download_video()` | requests → OkHttp | 4小时 |
| Utils | `utils/image.py` (未列出但需要) | PIL → BufferedImage | 8小时 |

**合计工作量**: 约 210小时  
**参考代码行数**: 约 4,000行（需大幅改造）

### C类：只参考设计（5项）

| 模块 | 原因 | 参考价值 |
|------|------|---------|
| 本地文件断点恢复 | 无租户隔离，不适合 SaaS | 思想：检查中间产物是否存在 → 改为数据库状态字段 |
| asyncio.Event 同步 | 进程内，无法跨 Worker | 思想：任务依赖关系 → 改为数据库 `dependencies` 表 |
| working_dir 目录结构 | 本地路径 | 思想：按 `{tenant}/{project}/{shot}` 组织对象存储路径 |
| Agent Loop 会话管理 | 桌面端交互 | 思想：上下文管理 → 前端已有交互设计 |
| Prompt 模板 | Langchain ChatPromptTemplate | 思想：System + Human Prompt 结构 → 梧曜自定义 Prompt 库 |

### D类：不得复用（8项）

| 模块 | 原因 |
|------|------|
| `main_agent.py` | CLI 入口，非 Web API |
| `agent_runtime/` | 桌面端 Agent Loop |
| `web/` | ViMax 前端（梧曜已有 Vue 前端） |
| `vimax_benchmark/` | 评测工具 |
| 所有硬编码本地路径 | 无租户隔离 |
| `ui/` TUI 界面 | 非 Web 场景 |
| `tests/` 桌面端测试 | 测试场景不同 |
| `utils/provider_presets.py` | OpenRouter 预设配置（梧曜用 FluAPI/ToAPIs） |

---

## 六、目标后端模块映射

基于当前梧曜项目 Spring Boot 后端骨架，建议以下模块映射：

```
server/src/main/java/com/wuyao/nexus/
├── domain/video/                     [来源: ViMax interfaces/]
│   ├── Character.java               ← CharacterInScene
│   ├── Shot.java                    ← ShotDescription
│   ├── Camera.java                  ← Camera
│   ├── Storyboard.java              ← List<ShotBriefDescription>
│   └── VideoProject.java            [新增：项目根实体]
│
├── domain/media/                     [来源: ViMax interfaces/]
│   ├── ImageOutput.java             ← ImageOutput
│   ├── VideoOutput.java             ← VideoOutput
│   └── MediaAsset.java              [新增：对象存储元数据]
│
├── providers/                        [来源: ViMax tools/]
│   ├── ImageProvider.java           ← ImageGenerator Protocol
│   ├── VideoProvider.java           ← VideoGenerator Protocol
│   ├── fluapi/
│   │   ├── FluAPIImageAdapter.java  [新增：对接 FluAPI Image 2.0]
│   │   └── FluAPITextAdapter.java   [新增：对接 FluAPI 文本模型]
│   ├── toapis/
│   │   └── SeedanceAdapter.java     ← video_generator_doubao_seedance_yunwu_api.py（改造）
│   └── ProviderFactory.java         ← render_backend.py（改造）
│
├── workflows/video_creation/         [来源: ViMax pipelines/]
│   ├── ScriptToVideoWorkflow.java   ← Script2VideoPipeline
│   ├── IdeaToVideoWorkflow.java     ← Idea2VideoPipeline
│   └── WorkflowOrchestrator.java    [新增：状态机编排]
│
├── services/
│   ├── StoryboardService.java       ← agents/storyboard_artist.py
│   ├── CharacterService.java        ← agents/character_extractor.py
│   ├── ReferenceImageService.java   ← agents/reference_image_selector.py
│   ├── FrameGenerationService.java  ← generate_frames_for_single_camera()
│   └── VideoTaskService.java        ← generate_video_for_single_shot()
│
├── workers/
│   ├── video/
│   │   ├── VideoTaskPoller.java     [新增：轮询 Seedance 任务状态]
│   │   └── VideoTaskConsumer.java   [新增：RabbitMQ Consumer]
│   ├── media/
│   │   └── FFmpegWorker.java        ← utils/video.py
│   └── qa/
│       └── VideoQAWorker.java       [新增：质检]
│
├── persistence/
│   ├── VideoProjectRepository.java
│   ├── ShotRepository.java
│   └── MediaAssetRepository.java
│
└── api/
    └── VideoProjectController.java   [新增：RESTful API]
```

---

## 七、必须重写的模块

以下模块 ViMax 不提供或不适合复用，必须从零开发：

### 7.1 租户隔离与权限

**需求**：
- 多租户数据隔离（Tenant → Merchant → Store）
- 用户权限验证（RBAC）
- API 鉴权（JWT Token）

**实现**：
- Spring Security + JWT
- `@TenantScope` AOP 切面
- 数据库行级隔离（tenant_id 字段）

### 7.2 商家资料快照

**需求**：
- 读取商家/门店/品牌/知识库数据
- 生成结构化快照（JSON）
- 注入到视频生成 Prompt

**ViMax 状态**：无此功能（假设用户直接提供 script）

**实现**：
- `MerchantSnapshotService.java`
- 从 `merchants`、`stores`、`brands`、`knowledge` 表聚合数据
- 生成 `FactSnapshot` 对象

### 7.3 持久化任务队列

**需求**：
- 任务提交、状态追踪、失败重试
- 服务重启后任务恢复
- 分布式 Worker 消费

**ViMax 状态**：进程内 `asyncio.gather()`，重启丢失

**实现**：
- RabbitMQ + Spring AMQP
- 任务状态表（`video_tasks`）
- Dead Letter Queue 处理失败任务

### 7.4 对象存储集成

**需求**：
- 视频/图片上传到 MinIO/COS/TOS
- 预签名 URL 生成
- 租户隔离路径（`{tenant_id}/{project_id}/{shot_id}/`）

**ViMax 状态**：本地文件系统（`working_dir/`）

**实现**：
- `ObjectStorageService.java`
- MinIO SDK 或云厂商 SDK
- 路径规范：`{bucket}/{tenant_id}/{project_id}/shots/{shot_id}/first_frame.png`

### 7.5 幂等性与事务

**需求**：
- 防止重复提交（幂等键）
- 成本预扣与确认
- 事务一致性（任务状态 + 成本台账）

**ViMax 状态**：无（单用户桌面工具）

**实现**：
- `@Transactional` 注解
- Redis 幂等键缓存
- 数据库乐观锁（`version` 字段）

---

## 八、许可证与第三方依赖风险

### 8.1 许可证分析

| 项目 | 许可证 | 商业使用 | 衍生作品 | 署名要求 | 风险等级 |
|------|--------|---------|---------|---------|---------|
| **ViMax** | MIT | ✅ 允许 | ✅ 允许 | ⚠️ 需保留版权声明 | **低** |
| langchain | MIT | ✅ | ✅ | ⚠️ | 低 |
| moviepy | MIT | ✅ | ✅ | ⚠️ | 低 |
| pydantic | MIT | ✅ | ✅ | ⚠️ | 低 |
| aiohttp | Apache 2.0 | ✅ | ✅ | ⚠️ | 低 |

**结论**：ViMax MIT 许可证允许商业使用和修改，**复用无许可证风险**，但需在最终产品中保留 MIT 许可证声明。

### 8.2 第三方依赖风险

| 依赖 | ViMax 用途 | 梧曜替代方案 | 风险 |
|------|-----------|-------------|------|
| **yunwu.ai** | Seedance 视频生成中转 | ❌ **不能使用**，改为 ToAPIs 官方 | **高** |
| moviepy | FFmpeg 封装 | Java ProcessBuilder 直接调用 ffmpeg | 低 |
| langchain | LLM 调用封装 | FluAPI HTTP Client（OkHttp） | 低 |
| OpenRouter | LLM 聚合平台 | 梧曜不使用（直接对接 FluAPI） | 无影响 |

**⚠️ 关键风险**：
- ViMax 的 Seedance 适配器使用 `yunwu.ai` 作为中转代理，**不能直接用于生产**
- 必须重写为 ToAPIs 官方 Seedance 端点
- 验证 ToAPIs 的请求格式、鉴权方式和错误码

---

## 九、最小迁移顺序（分 3 个迭代）

### 迭代 1：数据模型 + Provider 协议（1周）

**目标**：建立核心数据结构和供应商抽象层

| 任务 | 来源 | 工作量 | 验收标准 |
|------|------|--------|---------|
| 创建 `Character.java` | ViMax `CharacterInScene` | 2h | 单元测试通过 |
| 创建 `Shot.java` / `Camera.java` | ViMax 数据模型 | 4h | 单元测试通过 |
| 创建 `ImageProvider.java` 接口 | ViMax `ImageGenerator` | 2h | 接口编译通过 |
| 创建 `VideoProvider.java` 接口 | ViMax `VideoGenerator` | 2h | 接口编译通过 |
| 实现 `FluAPIImageAdapter` | 新增（对接 FluAPI Image 2.0） | 8h | 可生成测试图片 |
| 实现 `SeedanceAdapter`（临时 Mock） | 新增 | 4h | 返回 Mock 任务 ID |
| 数据库表结构 | `video_projects`, `shots`, `cameras` | 6h | 表创建成功 |

**交付物**：
- `domain/video/` 包（5个实体类）
- `providers/` 包（2个接口 + 2个实现）
- 数据库迁移脚本

### 迭代 2：单镜头生成闭环（2周）

**目标**：实现"用户输入 → 脚本 → 单镜头视频"最小闭环

| 任务 | 来源 | 工作量 | 验收标准 |
|------|------|--------|---------|
| `StoryboardService` | ViMax `storyboard_artist.py` | 20h | 可调用 FluAPI 生成分镜 |
| `CharacterService` | ViMax `character_extractor.py` | 12h | 提取角色列表 |
| `FrameGenerationService` | ViMax `generate_frames_for_single_camera()` | 20h | 生成首帧 PNG |
| `VideoTaskService` | ViMax `generate_video_for_single_shot()` | 16h | 提交 Seedance 任务 |
| `VideoTaskPoller` | ViMax `query_video_generation_task()` | 12h | 轮询任务状态 |
| `ObjectStorageService` | 新增 | 8h | 上传/下载 MinIO |
| RabbitMQ 集成 | 新增 | 12h | 任务队列可消费 |
| `VideoProjectController` | 新增 | 8h | RESTful API 可调用 |

**交付物**：
- 可通过 API 提交脚本，生成单个镜头视频
- 视频存储在对象存储
- 任务状态可查询

### 迭代 3：多镜头拼接 + 商家资料（2周）

**目标**：完整"商家资料 → 多镜头视频 → FFmpeg 拼接"

| 任务 | 来源 | 工作量 | 验收标准 |
|------|------|--------|---------|
| `MerchantSnapshotService` | 新增 | 12h | 聚合商家数据为 JSON |
| `CameraTreeService` | ViMax `construct_camera_tree()` | 8h | 构建摄像机依赖树 |
| `FFmpegWorker` | ViMax `concatenate_video_files()` | 16h | 拼接多个 MP4 |
| 多镜头并行生成 | ViMax `asyncio.gather()` 思想 | 20h | RabbitMQ 并发消费 |
| `VideoQAWorker` | 新增 | 12h | 质检（分辨率/时长/比例） |
| 失败重试机制 | 新增 | 8h | Dead Letter Queue |
| 前端集成 | 调用新 API | 16h | 创作工作区可用 |

**交付物**：
- 完整"一句话 → 多镜头视频"闭环
- 支持失败镜头单独重试
- 质检和人工审核流程

---

## 十、测试建议

### 10.1 单元测试

| 模块 | 测试重点 | 参考 ViMax |
|------|---------|-----------|
| `Character.java` | 字段序列化/反序列化 | ViMax 使用 Pydantic 自动验证 |
| `Shot.java` | `variation_type` 逻辑 | - |
| `SeedanceAdapter` | 任务提交、轮询、错误处理 | ViMax `create_video_generation_task()` 重试逻辑 |
| `FFmpegWorker` | 拼接逻辑、进程清理 | ViMax `finally` 块清理 `VideoFileClip` |

### 10.2 集成测试

| 场景 | 测试步骤 | 预期结果 |
|------|---------|---------|
| 单镜头生成 | 提交脚本 → 轮询任务 → 下载视频 | MP4 文件可播放 |
| 多镜头拼接 | 生成 3 个镜头 → FFmpeg 拼接 | final_video.mp4 包含 3 段 |
| 失败重试 | 模拟 Seedance 失败 → 触发重试 | 任务最终成功或进入 DLQ |
| 租户隔离 | 租户 A 查询租户 B 的项目 | 返回 403 Forbidden |

### 10.3 压力测试

| 指标 | 目标 | 参考 ViMax |
|------|------|-----------|
| 并发镜头数 | 20 个镜头并行 | ViMax 使用 `asyncio.gather()` 可并行 |
| Seedance 轮询频率 | 2秒/次，最多 300 次（10分钟） | ViMax `poll_interval=2`, `max_poll_attempts=300` |
| FFmpeg 拼接速度 | 10 个 5秒镜头 < 30秒 | ViMax 未测试，需实测 |

---

## 十一、关键问题答疑

### 1. ViMax 能否整体作为梧曜后端服务？

**不能**。ViMax 是桌面/CLI 工具，架构决策不适合 SaaS：
- ✅ 核心算法和数据结构有价值
- ❌ 状态管理（本地文件）不支持多租户
- ❌ 并发模型（asyncio）不支持分布式
- ❌ 无 API 层，无权限控制

### 2. 应该整体改造还是抽取模块？

**抽取模块**（推荐）：
- **直接复用**：数据模型、Provider 协议、FFmpeg 逻辑
- **改造复用**：Pipeline 流程思想、Prompt 工程
- **重新开发**：租户隔离、任务队列、对象存储、商家资料

**整体改造成本 > 3倍抽取模块成本**，且风险更高。

### 3. Seedance 适配器能否直接用？

**不能**。ViMax 使用 `yunwu.ai` 作为 Seedance 中转代理：
```python
url = "https://yunwu.ai/volc/v1/contents/generations/tasks"
```

**必须改造**：
- 替换为 ToAPIs 官方 Seedance 端点
- 验证请求/响应格式差异（模型名称、参数格式、错误码）
- 重新实现鉴权（yunwu.ai Bearer Token → ToAPIs 签名/Key）

**可复用思想**：
- t2v / i2v / flf2v 模型选择逻辑
- 轮询机制（2秒间隔，最多 300 次）
- 重试策略（`max_create_attempts=3`，指数退避）

### 4. 如何支持单镜头重试？

**ViMax 机制**：
- 手动删除 `working_dir/shots/{idx}/video.mp4`
- 重新运行 Pipeline，自动跳过已存在文件

**梧曜实现**：
- 数据库字段：`shots.status` (`PENDING`, `GENERATING`, `COMPLETED`, `FAILED`)
- API：`POST /api/video-projects/{id}/shots/{shotId}/retry`
- Worker：查询 `status=FAILED` 的镜头，重新提交到 RabbitMQ

### 5. 如何实现断点恢复？

**ViMax 机制**：
```python
if os.path.exists(storyboard_path):
    # 从文件加载
else:
    # 生成并保存
```

**梧曜实现**：
```java
Shot shot = shotRepository.findById(shotId);
if (shot.getStatus() == ShotStatus.COMPLETED) {
    return shot.getVideoUrl(); // 已完成，返回结果
} else {
    // 重新生成
}
```

**关键差异**：
- ViMax：检查文件是否存在
- 梧曜：检查数据库状态字段

### 6. FFmpeg 拼接是否可独立抽取？

**是**。ViMax `utils/video.py:concatenate_video_files()` 逻辑清晰：
```python
clips = [VideoFileClip(path) for path in video_paths]
final = concatenate_videoclips(clips)
final.write_videofile(output_path, codec="libx264", preset="medium")
```

**Java 实现**（两种方案）：

**方案 1：ProcessBuilder 调用 ffmpeg**（推荐）
```java
// 生成 concat 文件列表
// file 'shot_0.mp4'
// file 'shot_1.mp4'
ProcessBuilder pb = new ProcessBuilder(
    "ffmpeg", "-f", "concat", "-safe", "0",
    "-i", "filelist.txt", "-c", "copy", "output.mp4"
);
pb.start().waitFor();
```

**方案 2：jcodec 库**
```java
SequenceEncoder encoder = new SequenceEncoder(new File("output.mp4"));
for (String path : videoPaths) {
    encoder.encodeNativeFrame(readFrame(path));
}
encoder.finish();
```

**推荐方案 1**：兼容性更好，性能更高。

### 7. 如何防止重复提交和重复扣费？

**ViMax 状态**：无此功能（单用户工具）

**梧曜实现**：
1. **幂等键**：前端生成 UUID，后端 Redis 缓存 `idempotency:{key}` → `project_id`
2. **成本预扣**：
   ```sql
   UPDATE tenants SET balance = balance - estimated_cost WHERE id = ? AND balance >= estimated_cost
   ```
3. **状态机**：`DRAFT → CONFIRMING → QUEUED → RUNNING`，不允许跳跃
4. **事务**：项目创建 + 成本预扣在同一事务

### 8. Image 2.0 参考帧链路如何复用？

**ViMax 逻辑**（`generate_frames_for_single_camera()`）：
1. 生成角色肖像（front/side/back）
2. 选择参考图（`ReferenceImageSelector`）
3. 生成首帧（`ImageGenerator.generate_single_image()`）
4. 如需尾帧，重复步骤 2-3

**梧曜复用**：
- **直接复用**：角色肖像三视图思想
- **改造复用**：参考图选择算法（从多个候选中选择最佳参考）
- **新增**：商家 Logo/商品图片作为额外参考

### 9. 最小可跑通闭环需要多少代码？

**估算**（从零到单镜头生成）：

| 模块 | 行数 | 工作量 |
|------|------|--------|
| 数据模型（5个实体） | 500 | 15h |
| Provider 接口 + FluAPI 实现 | 400 | 16h |
| StoryboardService（LLM 调用） | 300 | 20h |
| FrameGenerationService | 400 | 20h |
| VideoTaskService + Poller | 500 | 28h |
| ObjectStorageService | 200 | 8h |
| Controller + DTO | 300 | 12h |
| RabbitMQ 配置 | 100 | 8h |
| **合计** | **2,700 行** | **127小时** |

**约 3-4 周**（2 名后端工程师）

### 10. ViMax 是否支持视频反推、数字人、自动发布？

**不支持**。ViMax 专注于"文本 → 视频"生成链路：
- ✅ Idea2Video / Script2Video
- ✅ Novel2Video（长文本压缩）
- ✅ AutoCameo（人物照片植入）
- ❌ 视频反推（Video → Script）
- ❌ 数字人（TTS + Avatar）
- ❌ 自动发布（抖音/小红书）

梧曜需要这些功能，需独立开发。

### 11. 最优先迁移哪 3-5 个模块？

**Top 5**（按价值排序）：

1. **数据模型**（`Character` / `Shot` / `Camera`）  
   **理由**：核心数据结构，后续模块都依赖，迁移成本低（15h）

2. **Provider 协议**（`ImageProvider` / `VideoProvider`）  
   **理由**：供应商抽象层，解耦业务逻辑与 API 调用（12h）

3. **FFmpeg 拼接**（`concatenate_video_files()`）  
   **理由**：独立功能，无外部依赖，价值明确（16h）

4. **Seedance 轮询逻辑**（`query_video_generation_task()`）  
   **理由**：异步任务核心，改造后可复用（20h）

5. **StoryboardService Prompt 工程**（`design_storyboard()` 的 Prompt）  
   **理由**：分镜设计是视频质量关键，Prompt 可直接翻译（20h）

**合计**: 83小时，可在 2 周内完成核心基础。

### 12. 必须重写的模块有哪些？

**核心必须重写**（ViMax 不提供或不适用）：

1. **租户隔离与权限**（Spring Security + RBAC）
2. **商家资料快照**（聚合 Merchant/Store/Brand 数据）
3. **持久化任务队列**（RabbitMQ + 数据库状态表）
4. **对象存储集成**（MinIO/COS/TOS）
5. **幂等性与成本控制**（Redis 幂等键 + 数据库事务）
6. **RESTful API 层**（Controller + DTO）
7. **任务恢复机制**（服务重启后从数据库恢复任务）
8. **质检工作流**（分辨率/时长/内容审核）

**估算工作量**: 约 300 小时（6-8 周，2 名后端工程师）

---

## 十二、未确认问题

以下问题需进一步验证或与用户确认：

### 12.1 技术验证

| 问题 | 当前状态 | 验证方法 |
|------|---------|---------|
| ToAPIs Seedance 官方端点格式 | 未知 | 查阅 ToAPIs 文档，编写测试脚本 |
| FluAPI Image 2.0 参考图传参方式 | 未知 | 查阅 FluAPI 文档 |
| Seedance 2.5 是否支持首尾帧 | 未知 | 咨询 ToAPIs 技术支持 |
| FFmpeg 在 Windows Server 的性能 | 未知 | 部署环境压测 |
| RabbitMQ 最大并发镜头数 | 未知 | 负载测试 |

### 12.2 业务需求

| 问题 | 影响模块 | 需确认对象 |
|------|---------|-----------|
| 是否需要视频时长控制（5秒/10秒） | `SeedanceAdapter` | 产品经理 |
| 商家 Logo 是否作为参考图 | `FrameGenerationService` | 产品经理 |
| 失败镜头是否自动重试 3 次 | `VideoTaskPoller` | 产品经理 |
| 质检不通过是否阻断发布 | `VideoQAWorker` | 产品经理 |
| 是否支持手动修改分镜脚本 | `StoryboardService` | 产品经理 |

### 12.3 成本与性能

| 问题 | 影响 | 需确认对象 |
|------|------|-----------|
| 单个视频平均镜头数 | Worker 并发设计 | 业务数据 |
| Seedance 生成速度（5秒视频需要多久） | 轮询超时设置 | ToAPIs 实测 |
| 对象存储带宽限制 | 并发上传/下载 | 运维团队 |
| 单租户每日视频生成量 | 数据库分表策略 | 业务预测 |

---

## 十三、最终结论

### 13.1 核心判断

| 维度 | 结论 |
|------|------|
| **整体复用** | ❌ 不能，架构不适合 SaaS |
| **模块复用** | ✅ 可以，数据模型 + Provider + FFmpeg 高价值 |
| **改造成本** | ⚠️ 中等，约 210 小时（B类模块） |
| **重写成本** | ⚠️ 高，约 300 小时（租户/队列/存储） |
| **总工作量** | **约 525 小时（13 周，2 人）** |
| **许可证风险** | ✅ 无，MIT 许可 |
| **技术风险** | ⚠️ Seedance 适配器需重写 |

### 13.2 推荐策略

**分三阶段渐进式复用**：

**阶段 1：数据模型 + Provider（1 周）**
- 复用 ViMax 数据结构（`Character` / `Shot` / `Camera`）
- 实现 `ImageProvider` / `VideoProvider` 接口
- 对接 FluAPI Image 2.0

**阶段 2：单镜头闭环（2 周）**
- 参考 ViMax Pipeline 编排思想
- 实现 StoryboardService / FrameGenerationService
- 对接 ToAPIs Seedance（重写适配器）
- RabbitMQ 任务队列

**阶段 3：多镜头拼接 + 商家资料（2 周）**
- 复用 ViMax FFmpeg 拼接逻辑
- 实现 `MerchantSnapshotService`
- 多镜头并行生成
- 质检与审核流程

### 13.3 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| ToAPIs Seedance 接口与 yunwu.ai 差异大 | 中 | 高 | 提前对接 ToAPIs 技术支持，获取文档 |
| FFmpeg 拼接性能不足 | 低 | 中 | 压测验证，考虑硬件加速 |
| 多镜头并发导致 API 限流 | 中 | 中 | 实现速率限制器（RateLimiter） |
| 本地文件断点恢复逻辑迁移复杂 | 低 | 低 | 用数据库状态字段替代文件检查 |

### 13.4 价值评估

**复用 ViMax 的核心价值**：

1. **节省设计时间**：数据模型和流程已验证，无需从零设计
2. **降低试错成本**：Pipeline 编排、参考图选择等核心算法已调优
3. **加速开发**：直接复用约 500 行数据模型代码，改造复用约 4,000 行业务逻辑
4. **技术参考**：Prompt 工程、异步并发、轮询机制等实现细节

**预计节省**: 约 **200 小时**（相比完全从零开发）

---

## 附录 A：ViMax 关键代码片段

### A.1 ShotDescription 数据模型

```python
# interfaces/shot_description.py
class ShotDescription(BaseModel):
    idx: int  # 镜头索引
    cam_idx: int  # 摄像机索引
    visual_desc: str  # 视觉描述
    variation_type: Literal["large", "medium", "small"]  # 变化程度
    ff_desc: str  # 首帧描述
    ff_vis_char_idxs: List[int]  # 首帧可见角色
    lf_desc: str  # 尾帧描述
    lf_vis_char_idxs: List[int]  # 尾帧可见角色
    motion_desc: str  # 运动描述
    audio_desc: str  # 音频描述
```

### A.2 Seedance 任务提交

```python
# tools/video_generator_doubao_seedance_yunwu_api.py
async def create_video_generation_task(
    self,
    prompt: str,
    reference_image_paths: List[str],
    resolution: Literal["480p", "720p", "1080p"] = "720p",
    fps: Literal[16, 24] = 16,
    duration: Literal[5, 10] = 5,
) -> str:
    # 根据参考图数量选择模型
    if len(reference_image_paths) == 0:
        model = self.t2v_model  # text-to-video
    elif len(reference_image_paths) == 1:
        model = self.ff2v_model  # first-frame-to-video
    elif len(reference_image_paths) == 2:
        model = self.flf2v_model  # first-last-frame-to-video

    # 构造请求
    content = [{"type": "text", "text": prompt + f" --rs {resolution} --dur {duration}"}]
    for img_path in reference_image_paths:
        content.append({
            "type": "image_url",
            "image_url": {"url": image_path_to_b64(img_path)},
            "role": "first_frame" or "last_frame"
        })

    # 提交任务
    response = await session.post(
        "https://yunwu.ai/volc/v1/contents/generations/tasks",
        headers={"Authorization": f"Bearer {self.api_key}"},
        json={"model": model, "content": content}
    )
    return response["id"]
```

### A.3 异步并行生成

```python
# pipelines/script2video_pipeline.py
async def __call__(self, script, user_requirement, style):
    # 提取角色
    characters = await self.extract_characters(script)
    
    # 生成分镜
    storyboard = await self.design_storyboard(script, characters, user_requirement)
    
    # 拆解视觉描述
    shot_descriptions = await self.decompose_visual_descriptions(storyboard, characters)
    
    # 构建摄像机树
    camera_tree = await self.construct_camera_tree(shot_descriptions)
    
    # 并行生成帧和视频
    frame_tasks = [
        self.generate_frames_for_single_camera(camera, ...)
        for camera in camera_tree
    ]
    video_tasks = [
        self.generate_video_for_single_shot(shot, ...)
        for shot in shot_descriptions
    ]
    await asyncio.gather(*frame_tasks, *video_tasks)
    
    # FFmpeg 拼接
    final_video_path = concatenate_video_files(video_paths, "final_video.mp4")
    return final_video_path
```

---

## 附录 B：梧曜目标数据模型（Java）

```java
// domain/video/Shot.java
@Entity
@Table(name = "shots")
public class Shot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Long projectId;
    private Integer idx;  // 镜头索引
    private Integer camIdx;  // 摄像机索引
    
    @Column(columnDefinition = "TEXT")
    private String visualDesc;  // 视觉描述
    
    @Enumerated(EnumType.STRING)
    private VariationType variationType;  // LARGE / MEDIUM / SMALL
    
    @Column(columnDefinition = "TEXT")
    private String ffDesc;  // 首帧描述
    
    @Column(columnDefinition = "TEXT")
    private String lfDesc;  // 尾帧描述
    
    @Column(columnDefinition = "TEXT")
    private String motionDesc;  // 运动描述
    
    @Enumerated(EnumType.STRING)
    private ShotStatus status;  // PENDING / GENERATING / COMPLETED / FAILED
    
    private String firstFrameUrl;  // 对象存储 URL
    private String lastFrameUrl;
    private String videoUrl;
    
    // Getters / Setters
}
```

---

**报告完成时间**: 2026-08-25  
**审计执行人**: Claude (Opus 5)  
**审计工具**: 静态代码分析 + 调用链追踪  
**固定版本**: ViMax v1.2.0 (05a4894)
