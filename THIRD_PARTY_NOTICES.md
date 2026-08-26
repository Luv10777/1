# 第三方开源项目声明

本项目使用了以下开源项目的代码或思想。我们对这些项目的作者表示衷心感谢。

---

## ViMax - Agentic Video Generation

**项目信息**:
- **项目名称**: ViMax
- **项目地址**: https://github.com/HKUDS/ViMax
- **固定版本**: v1.2.0
- **固定 Commit**: 05a48943878312d88fe5a016c12a9654940ecc43
- **许可证**: MIT License
- **版权所有者**: Copyright (c) 2025 HKUDS

**使用方式**:
本项目复用了 ViMax 的以下模块，并进行了企业级改造以适应多租户 SaaS 架构：

### 直接复用（REUSE_AS_IS）
以下文件仅修改了 import 路径，保留了原始逻辑：
- `utils/robust_json_parser.py` - 容错 JSON 解析器
- `utils/retry.py` - 重试装饰器
- `utils/timer.py` - 计时器工具
- `utils/text.py` - 文本处理工具
- `utils/image.py` - 图片处理工具
- `tools/image_orientation.py` - 图片方向检测

### 适配改造（ADAPT）
以下模块进行了重大改造以支持多租户、商家事实快照、Prompt 版本化和服务端 AI Gateway：
- `agents/screenwriter.py` → 编剧 Agent（注入商家事实、租户上下文）
- `agents/storyboard_artist.py` → 分镜设计 Agent（注入连续性约束）
- `agents/character_extractor.py` → 角色提取 Agent
- `agents/scene_extractor.py` → 场景提取 Agent
- `agents/reference_image_selector.py` → 参考图选择器（注入商家素材约束）
- `agents/best_image_selector.py` → 最佳图片选择器（视觉质检）
- `agents/character_portraits_generator.py` → 角色肖像生成器
- `agents/camera_image_generator.py` → 镜头图片生成器
- `interfaces/*.py` → 所有 Pydantic Schema（增加租户、追踪、版本字段）
- `utils/video.py` → FFmpeg 视频处理工具（适配对象存储）
- `pipelines/idea2video_pipeline.py` → 拆解为独立无状态 Activity
- `pipelines/script2video_pipeline.py` → 拆解为独立无状态 Activity

### 参考设计（REFERENCE_ONLY）
以下模块仅参考设计思想，代码完全重新实现：
- `agent_runtime/` - Agent 运行时（参考会话管理思路）
- `tools/protocols.py` - 工具协议（参考抽象设计）
- `web/` - Web UI（参考交互模式，使用自有 Vue 3 技术栈）

### 排除模块（EXCLUDE）
以下模块未被使用，因为与本项目技术决策冲突：
- `tools/*_yunwu_*.py` - Yunwu 供应商实现（本项目统一使用 FluAPI/ToAPIs）
- `tools/*_openrouter_*.py` - OpenRouter 供应商实现
- `tools/*_google_*.py` - Google 供应商实现
- `configs/*.yaml` - 用户配置文件（本项目使用服务端配置）
- `main_*.py` - 单机入口脚本（本项目为多租户 SaaS）

**改造说明**:
1. **移除本地状态依赖**: 原 ViMax 使用 `working_dir` 作为状态源，改造后使用 PostgreSQL 数据库
2. **注入商家事实快照**: 所有 Agent 接受 `MerchantFactSnapshot` 参数，防止模型编造事实
3. **租户隔离**: 所有输入输出增加 `tenant_id`、`project_id`、`workflow_run_id`、`trace_id`
4. **Prompt 版本化**: 所有 Prompt 提取到模板文件，记录 `prompt_version`
5. **服务端 AI Gateway**: 替换直接供应商调用为统一 Platform Client
6. **幂等与恢复**: 支持任务崩溃恢复、镜头级重试、成本防重复扣减

**版权声明保留**:
所有复用的源文件头部保留了 ViMax 原始版权声明，符合 MIT License 要求。

---

## ViMax MIT License 全文

```
MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 其他依赖

本项目还使用了以下开源库，详细许可证信息请参见各自的 LICENSE 文件：

### Python 依赖
- langchain (MIT)
- openai (Apache-2.0)
- pydantic (MIT)
- fastapi (MIT)
- uvicorn (BSD-3-Clause)
- pyyaml (MIT)
- pillow (PIL License)
- opencv-python (MIT)
- moviepy (MIT)
- requests (Apache-2.0)
- tenacity (Apache-2.0)

### Java 依赖
- Spring Boot (Apache-2.0)
- Spring Security (Apache-2.0)
- PostgreSQL JDBC Driver (BSD-2-Clause)
- JJWT (Apache-2.0)
- Lombok (MIT)

### JavaScript 依赖
- Vue.js (MIT)
- Vue Router (MIT)
- Vite (MIT)
- ESLint (MIT)

---

**最后更新**: 2026-08-26  
**维护者**: 嘉兴市梧曜科技有限公司
