# 视频方案验证记录

2026-10-23（Asia/Shanghai），验证分支 `claude/video-plan` 的「帮我出方案」功能。
后端完整验证和前端测试、lint、类型检查、构建、浏览器检查均已执行。

## 自动检查

| 检查 | 结果 | 本地证据 |
|---|---|---|
| 后端 `mvn -B verify` | BUILD SUCCESS；929 项，926 通过、3 跳过，零失败、零错误 | `backend/growth-api/target/video-plan-docker-verify.log` |
| 前端 `npm test` | 289 项全部通过，无跳过 | `video-plan-frontend-final.log` |
| `npm run lint` | 通过，零错误；保留 183 条格式和未使用变量警告 | `video-plan-lint-final.log` |
| `npm run typecheck` | 通过 | `video-plan-typecheck-final.log` |
| `npm run build` | 通过；Vite 提示现有主包体积超过 500 kB | `video-plan-build-final.log` |
| `npm run build:admin` | 通过 | `video-plan-admin-build.log` |

后端在官方 `maven:3.9-eclipse-temurin-21` 容器中验证，使用当前 `pom.xml` 和完整 `src/`
的独立副本，依赖缓存复制到容器本地；Testcontainers 启动真实 PostgreSQL、Redis、MinIO。
测试报告保存在 `backend/growth-api/target/video-plan-docker-reports/`，构建出的 jar
保存在 `backend/growth-api/target/growth-api-0.1.0.jar`。

Windows 本机 JDK 的 NIO 回环连接报错导致直接执行的集成测试无法启动上下文，
因此以 Docker 中成功完成的整套验证为最终结果。
跳过的三项属于已有 `VideoFrameExtractorTest`，原因是该 Maven 镜像未安装 FFmpeg；
新增视频方案测试没有跳过。

前端测试输出仍有既有 Vite 热更新端口 `24678` 占用提示，不影响测试结果。

## 修复与回归覆盖

- 修复请求幂等检查遗漏 `referenceVideo`：同一请求编号改变参考视频标记后返回冲突。
- 参考图编号列表中的 `null` 现在由请求校验拒绝，避免在复制列表时触发空指针。
- ESLint 排除 `.claude/worktrees/**`，避免扫描旧工作树及其构建产物。
- 服务测试覆盖相同请求复用、变更请求冲突、过期 worker 无权发布或结算、重复失败事件只退款一次。
- 数据库集成测试实际执行方案 handler，验证 PNG 转 JPEG、1200×600 缩为 1024×512、
  稳定模型请求编号、三个有效方案、重复执行保护、成功扣一次烟火、无效结果退款和租户隔离。
- 前端交互测试覆盖输入校验、轮询与按钮禁用、选用方案及模型时长上限、
  换一批失败保留旧结果、网络中断后重用请求编号，以及模型未配置时的禁用提示。

## 浏览器验收

使用 Chrome 打开本地验收页面，桌面 CSS 视口为 1639×736，手机窄屏为 391×845。
页面使用实际 Vue 工作台和样式，接口由临时本地测试服务提供；测试服务及标签页已关闭，
临时视口设置已恢复。

已检查方案入口、空输入提示、风格与自定义风格、生成中反馈、三张方案卡片、
提示词与比例/时长填入、换一批成功、失败提示、返回上一批、手机选用第三个方案、
未配置模型时继续自行填写描述。桌面和手机均无页面横向溢出，控制台未发现错误或警告。

浏览器未完成本地文件选择器上传：Chrome 扩展未开启文件 URL 访问权限。
后端集成测试已经覆盖真实参考图的读取、缩放和模型请求组装；本轮浏览器使用文字需求完成交互验收。
外部付费模型的真实输出质量与线上部署不在这次验证结果中。

本地截图保存在 `logs/video-plan-desktop.jpg` 和 `logs/video-plan-mobile.jpg`。
日志、截图和构建产物由仓库既有规则忽略，不进入 Git。
