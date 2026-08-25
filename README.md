# 梧曜星枢 · AI 商家增长平台

**嘉兴市梧曜科技有限公司**  
**产品：梧曜星枢 / WUYAO NEXUS**

梧曜星枢是面向商家和门店的 AI 内容增长工作台。

## 当前状态

- **分支**: `feat/phase-1-functional-foundation`
- **GitHub**: https://github.com/Luv10777/1
- **运行模式**: 演示模式（Demo Mode）
- **最新提交**: 阶段一工程基础修复完成

## 本地运行

```bash
npm install
npm run dev
```

默认开发地址：http://localhost:4173/

生产构建：

```bash
npm run build
npm run preview
```

## 质量检查

提交前必须全部通过：

```bash
npm test          # 38个测试全部通过
npm run lint      # ESLint检查通过
npm run typecheck # TypeScript类型检查通过
npm run build     # 生产构建成功
```

## 演示登录

- **手机号**: 任意11位、以`1`开头的手机号
- **验证码**: 任意6位数字
- **认证模式**: `VITE_AUTH_MODE=mock`

## 已完成能力

### 阶段一：工程基础（✅ 已完成）

- ✅ TypeScript 配置和类型检查
- ✅ ESLint Flat Config 配置
- ✅ 标准目录结构（api、components、stores、types、utils、services）
- ✅ 统一请求封装和错误处理（`utils/request.js`）
- ✅ 环境配置管理（`utils/config.js`）
- ✅ 表单验证工具（`utils/validator.js`）
- ✅ 通用工具函数（`utils/index.js`）
- ✅ API客户端模块（`api/index.js`）
- ✅ 通用组件库：
  - Loading（加载状态）
  - Empty（空状态）
  - ErrorState（错误状态）
  - StatusBadge（状态徽章）
  - ConfirmDialog（确认对话框）
- ✅ 删除虚假的 FluAPI/ToAPIs READY 状态
- ✅ 明确标识演示模式
- ✅ 单元测试基础（38个测试通过）
- ✅ 包命名修正（wuyuo → wuyao）

### 前期已完成

- Vue 3 + Vite + Vue Router 工程基础
- 深色"星枢控制台"视觉系统
- Mock 登录态和路由守卫
- 22个业务路由和页面框架
- 领域契约和工作流状态机
- Mock 工作流编排器
- 一句话创作工作区原型

## 当前架构

```
src/
├── api/              # API客户端（auth、merchant、store、brand等）
├── components/       # 通用组件（Loading、Empty、ErrorState等）
├── domain/           # 领域逻辑和业务契约
├── layouts/          # 页面布局（AppShell）
├── stores/           # 状态管理（auth、creative、billing等）
├── types/            # 类型定义
├── utils/            # 工具函数（request、config、validator等）
├── views/            # 业务页面
├── App.vue
├── main.js
└── router.js
```

## 演示模式说明

当前项目运行在**演示模式**下：

- ✅ 前端工程完整，可本地运行和构建
- ✅ 领域契约和业务逻辑已定义
- ✅ 所有测试通过，代码质量基线建立
- ⚠️ **未配置后端API**（`VITE_API_BASE_URL`为空）
- ⚠️ **未配置AI模型**（FluAPI、ToAPIs等）
- ⚠️ **未配置对象存储**
- ⚠️ **使用Mock登录**（`VITE_AUTH_MODE=mock`）

所有API调用会明确提示"演示模式"或"未配置"，**不会伪造真实业务数据**。

## 待完成能力（阶段二至七）

### 阶段二：登录、权限和商家基础
- [ ] 手机验证码登录（服务端）
- [ ] JWT Token 和 Refresh Token
- [ ] 多租户、用户、角色、权限模型
- [ ] 商家与门店管理
- [ ] 数据库表结构（PostgreSQL）
- [ ] Redis 会话管理

### 阶段三：四大资源库
- [ ] 品牌库（品牌定位、视觉资产、语言风格）
- [ ] 素材库（图片、视频、音频，预签名直传）
- [ ] 知识库（文档解析、向量化、结构化提取）
- [ ] 作品库（生成结果、审核流程、发布追踪）

### 阶段四：统一AI模型网关与任务中心
- [ ] Provider接口抽象（FluAPI、ToAPIs适配器）
- [ ] 模型别名路由（TEXT_FAST、IMAGE_PRIMARY等）
- [ ] 异步任务管理（RabbitMQ）
- [ ] 成本预估与额度管理
- [ ] Webhook回调与状态轮询
- [ ] 任务失败重试和取消机制

### 阶段五：一句话批量内容生成
- [ ] 意图识别和Prompt优化
- [ ] CampaignPlan JSON生成与校验
- [ ] 内容变量矩阵（商品×角度×钩子×场景）
- [ ] 成本确认和批量执行
- [ ] 图片/视频质量检测
- [ ] 人工审核流程

### 阶段六：补齐全部菜单模块
- [ ] 模型对话
- [ ] 文案提取/重写
- [ ] 视频结构分析
- [ ] AI图片/视频创作
- [ ] 数字人播报
- [ ] 评论与AI客服
- [ ] 内容发布与运营分析
- [ ] 清除所有PlaceholderView

### 阶段七：视觉统一、测试覆盖和交付
- [ ] 设计系统一致性
- [ ] 完整测试覆盖
- [ ] 部署文档
- [ ] 环境变量清单
- [ ] 创建Pull Request

## 生产边界

当前**不具备生产能力**：

- ❌ 无后端API（Spring Boot需开发）
- ❌ 无数据库（PostgreSQL需配置）
- ❌ 无真实AI模型接入
- ❌ 无对象存储
- ❌ 无消息队列（RabbitMQ）
- ❌ 无真实用户认证
- ❌ 无多租户数据隔离
- ❌ 无支付和计费

**真实API Key、OAuth凭证和商家生产数据不得写入Git。**

## 文档

- [PROJECT_MEMORY.md](PROJECT_MEMORY.md) - 项目长期记忆
- [docs/project-status.md](docs/project-status.md) - 项目状态
- [docs/phase2-progress.md](docs/phase2-progress.md) - 第二阶段进度
- [DESIGN.md](DESIGN.md) - 视觉设计
- [CHANGELOG.md](CHANGELOG.md) - 变更日志

## 开发原则

1. **不允许静态假数据和虚假状态**
2. **未配置的能力必须明确标识"未配置"或"演示模式"**
3. **API Key和密钥只能在服务端**
4. **所有AI任务必须进入任务中心**
5. **所有生成结果必须进入作品库**
6. **保持深空科技感视觉风格**
7. **提交前必须通过lint、typecheck、test、build**

## 技术栈

- **前端**: Vue 3, Vite, Vue Router
- **计划后端**: Java 21 + Spring Boot 3
- **计划数据库**: PostgreSQL + Redis
- **计划消息队列**: RabbitMQ
- **计划对象存储**: MinIO / 腾讯云COS / 火山TOS
- **计划AI适配**: FluAPI、ToAPIs

## 许可

嘉兴市梧曜科技有限公司版权所有
