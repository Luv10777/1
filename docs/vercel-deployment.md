# Vercel 部署记录

当前阶段已完成 Vite production build，并提供了 `vercel.json`：

- 构建命令：`npm run build`
- 输出目录：`dist`
- SPA fallback：所有路径重写到 `/index.html`
- 环境模式：`VITE_AUTH_MODE=mock`

尚未执行 Vercel Preview/Production 部署，因为这需要仓库所有者通过 Vercel 官方授权。授权完成后按 Development → Preview → Production 的顺序发布，并记录每次部署的 commit、时间和回滚目标。
