# 微信网站应用扫码登录

更新日期：2026-10-09。数据库版本：V46。

登录页的微信入口跳转到微信官方二维码页，使用网站应用授权 `snsapi_login`。
扫码授权后回到一方志；已绑定账号直接登录，首次登录验证手机号后绑定现有账号或创建账号。
短信和密码登录共用原有用户及 JWT 会话。

## 使用与配置

微信开放平台需要已审核的网站应用及微信登录权限，授权回调域填写 `yifangzhi.com`。
网站应用的 AppID 和 AppSecret 配置在服务端，前端无需配置密钥。

| 配置项 | 正式值或存放要求 |
|---|---|
| `WECHAT_APP_ID` | `wx616f6e728d7d9a67` |
| `WECHAT_APP_SECRET` | 仅存服务端私有环境文件，不写入仓库 |
| `WECHAT_CALLBACK_URL` | `https://yifangzhi.com/api/auth/wechat/callback` |
| `WECHAT_FRONTEND_LOGIN_PATH` | `/login` |

本地 Java 配置位于 `backend/growth-api/.env`，正式 systemd 配置位于
`/etc/wuyao/growth-api.env`。文件采用 Java properties 格式；更新正式配置时只修改
`WECHAT_*` 四个键，保留数据库、JWT、短信及模型服务配置。
默认前端入口是同源 `/api/auth/wechat/start`，可用 `VITE_WECHAT_LOGIN_URL` 覆盖。

```bash
curl -sS -D - -o /dev/null https://yifangzhi.com/api/auth/wechat/start
```

配置及发布完成后应返回 `302`，跳转到 `open.weixin.qq.com/connect/qrconnect`，
并设置 `HttpOnly; Secure; SameSite=Lax` 的 `wechat_login_state` Cookie。
从 `www` 或其他域名发起时，会先跳转到回调域再发起授权，以保证 Cookie 来自同一域名。

## 首次绑定与密码

首次微信登录先验证手机号和短信验证码，验证前不透露账号是否已经设置密码。
已有密码账号绑定后直接登录，保留原有密码；新账号或尚无密码的账号进入设置密码步骤。
第二步只提交微信票据和密码，不重复消耗短信验证码。
密码长度为 8–64 位，UTF-8 编码最多 72 字节；完成后可用手机号在密码入口登录。

后续扫码复用原绑定，提交其他手机号不会改变绑定账号。
停用账号不能通过微信登录。票据过期或刷新丢失时，重新扫码即可。

## 接口与状态

| 接口 | 用途 |
|---|---|
| `GET /api/auth/wechat/start` | 创建十分钟 OAuth 状态并跳转官方二维码页 |
| `GET /api/auth/wechat/callback` | 校验浏览器 Cookie 与状态，服务端交换授权 code |
| `POST /api/auth/wechat/complete` | 查询绑定、验证手机、设密及发行平台会话 |

回调地址使用 URL 片段 `#wechat_ticket=...` 携带十分钟临时票据，前端读取后立即清除。
票据不包含微信 access token，成功登录后原子消费；重复及并发消费受到保护。
数据库事务锁保证同一微信身份无法被并发请求覆盖，同一手机号不会并发创建多个用户。
`wechat_accounts` 属于登录前使用的系统身份表，沿用 `users` 的无 RLS 约定。

## 发布与回滚

正式服务器使用 systemd，后端入口是 `/home/ubuntu/wuyao-current`，前端入口是
`/var/www/wuyao/current`。发布时使用独立新目录，保留旧目录、链接目标及环境文件备份。
发布前备份数据库，将经过验证的 JAR 和前端构建产物写入新目录；前端包含主干的 Mac 下载入口。
API 启动时由 Flyway 执行新增 V46，然后确认健康和微信入口，再更新前端。

切换后端链接后，API 与全部 worker 应加载同一版 JAR，重启范围见
[systemd 部署说明](../deploy/systemd/README.md)。
回滚时恢复前后端旧链接和环境文件备份，重启对应服务；V46 是新增表，可保留，
旧版代码不会使用它。不要为回滚删除微信身份或用户数据。
桌面安装包位于 `/var/www/wuyao/downloads`，独立于前端发布目录。

## 验证边界

前端在合并 `0ee5604` 主干后通过 217 项测试、类型检查、生产构建和 ESLint。
ESLint 当前有 235 项既有警告，零错误。
浏览器已用明确的测试响应验证两步绑定、已有账号跳过设密、票据过期和手机布局。
后端在 Linux / Java 21 环境完成完整 `mvn verify`，732 项测试全部通过，无失败或跳过。
正式入口与数据库迁移验证将在发布完成后补充。

真实授权 code 只能由微信扫码授权产生。最终验收仍需真实微信扫码、短信验证及首次绑定，
再重新扫码确认复用账号，并检查手机号密码入口。测试响应通过不能代替真实微信联调。
