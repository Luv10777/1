# 腾讯云 COS 声音样本配置

当前存储桶：`yifangzhi-1480507480`，地域 `ap-shanghai`（上海），保持私有读写。
公网域名：`https://yifangzhi-1480507480.cos.ap-shanghai.myqcloud.com`。
阿里云 CosyVoice 可以通过 COS 的临时签名 HTTPS 链接下载样本，不要求音频存储也属于阿里云。

## 1. 本机配置

在 `backend/growth-api/.env` 填写腾讯云凭证（不要加引号，不要把密钥发到聊天或提交 Git）：

```dotenv
COS_REGION=ap-shanghai
COS_BUCKET=yifangzhi-1480507480
COS_SECRET_ID=<腾讯云子账号 SecretId>
COS_SECRET_KEY=<腾讯云子账号 SecretKey>
VOICE_SAMPLE_STORAGE=cos
```

Bucket 和地域已填入本机配置，密钥位置留空；在填好密钥前 `VOICE_SAMPLE_STORAGE` 保持 `minio`。环境变量优先于 `.env`。填写后将开关改为 `cos` 并重启后端。阿里云 `DASHSCOPE_*` 配置仍用于声音克隆和合成。

腾讯云 CAM 子账号需要对该桶 `cos/t*/voice-samples/*` 对象的以下操作授权：

- `name/cos:PutObject`：浏览器上传样本；
- `name/cos:GetObject`：签名下载，用于原样本试听及供应商读取；
- `name/cos:HeadObject`：确认上传后的大小和 MIME 类型；
- `name/cos:DeleteObject`：删除对应样本。

对象资源可限定为 `qcs::cos:ap-shanghai:uid/1480507480:yifangzhi-1480507480/cos/t*/voice-samples/*`。不要授予匿名读写；对象授权不需要账号管理或存储桶管理权限。

## 2. COS 跨域规则（CORS）

在 COS 控制台进入该桶的跨域访问 CORS 配置，为网页直传设置：

| 项目 | 值 |
| --- | --- |
| 来源 Origin | 本地使用 `http://localhost:4173`；若使用 Vite 开发服务器，再加实际端口来源，例如 `http://localhost:5173`；上线后加实际前端 HTTPS 域名 |
| 方法 | `PUT`、`GET`、`HEAD` |
| 允许请求头 | `Content-Type`、`Range` |
| 暴露响应头 | `ETag`、`Content-Length`、`Content-Range`、`Accept-Ranges` |
| 缓存时间 | `600` 秒 |

Origin 是浏览器地址栏的协议、域名和端口，不包含页面路径。CORS 只控制浏览器跨域，不会将私有桶变成公开桶。供应商在服务端下载不依赖 CORS。

## 3. 新旧样本

启用后，新录音通过 COS SDK 生成的签名 URL 直传，文件 key 以 `cos/t<租户ID>/voice-samples/` 开头；数据库保存目标 key。签名有效期为上传 10 分钟、克隆下载 10 分钟、试听下载 5 分钟。

已有 MinIO 样本及其他素材仍使用原地址，不会因开关改变而误到 COS 查找或删除。旧样本不会自动迁移。若要继续用之前的录音，可在原样本下载链接中保存 WAV 文件，再作为新样本上传到 COS，无需重新录音；或者另行执行保留对象 key/元数据关联的迁移。

配置完成后验证一次：录音或上传 → 样本确认 → 原样本试听 → 克隆 → 获得 `voice_id` → 状态 OK → 合成试听。没有填写凭证前，仅能完成本地自动化测试，不能认定真实 COS 或供应商已联通。
