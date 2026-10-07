# CosyVoice v3.5 Flash 声音克隆接入

2026-09-30 核对阿里云官方[声音复刻](https://help.aliyun.com/zh/model-studio/cosyvoice-clone-api)、[复刻 HTTP API](https://help.aliyun.com/zh/model-studio/voice-clone-design-http-api)和[合成客户端事件](https://help.aliyun.com/zh/model-studio/cosyvoice-client-events)。

## 产品流程

新建声音克隆 → 填名称 → 麦克风录音或上传 → 试听样本 → 确认授权 → 开始克隆训练 → 等待处理 → 试听合成音色并设为主播。

页面“训练”对应供应商的声音复刻服务，不是本地训练模型。录音调用浏览器真实麦克风；WebM/MP4 录音解码后写成单声道 PCM16 WAV，保留解码采样率（至少 16 kHz）。建议朗读 10～20 秒，录音入口 30 秒自动停止，少于 10 秒提示重录。上传支持 WAV/MP3/M4A，最大 10 MB；供应商要求最长 60 秒、至少 5 秒连续清晰人声。录音权限需要 HTTPS 或 localhost。

可选择“仅保存样本”；语音服务未配置时只保存，不生成假的完成状态。音色卡片提供训练状态、原样本试听、音色 ID 和授权记录。

## 服务端配置

```dotenv
DASHSCOPE_API_KEY=<服务端密钥>
DASHSCOPE_CLONE_URL=https://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/customization
DASHSCOPE_WEBSOCKET_URL=wss://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference
DASHSCOPE_TTS_MODEL=cosyvoice-v3.5-flash
DASHSCOPE_BUILTIN_VOICE=
```

按实际账号地域和工作空间复制控制台地址。样本使用对象存储的短时签名 HTTPS URL，必须能被阿里云访问；localhost MinIO 不满足此条件。API Key 仅在后端使用。默认不将 v3 的系统音色 `longanyang` 当作 v3.5 音色；若显式配置系统音色，须自行核对对应模型支持情况。已有环境变量仍会覆盖新默认值，升级时要同步部署配置并重启后端。

### 本地 MinIO 为什么不能克隆

`MINIO_ENDPOINT=http://localhost:39000` 仅适合本机上传和试听。创建接口需要供应商主动下载样本，供应商无法读取开发电脑的 localhost。当前后端会在改变样本训练状态前检查下载地址，对这类配置直接提示“样本已保存，存储尚未配置公网 HTTPS 地址”，不再误报成样本格式错误。

可以将 MinIO 部署到服务器，配置有效证书及公网 HTTPS 入口后，将 `MINIO_ENDPOINT` 指向真实可用的入口。该入口需从浏览器、后端及供应商网络均可访问，并正确保留对象存储签名所用的 Host、路径和查询参数；不能只替换已签名链接的域名，也不能仅把本机地址的协议改成 HTTPS。桶仍保持私有。

目前已加入腾讯云 COS SDK 适配，可让新声音样本直接上传 COS，见 [COS 配置说明](tencent-cos-voice-storage.md)。旧样本按原存储 key 继续读取 MinIO，不会自动迁移。OSS 仍需单独适配。迁移或下载后重新上传现有音频都无需重新录音。尚未创建成功的样本不会有供应商音色 ID。

## 创建并保存 ID

后端使用 Bearer 鉴权向 `DASHSCOPE_CLONE_URL` POST：

```json
{
  "model": "voice-enrollment",
  "input": {
    "action": "create_voice",
    "target_model": "cosyvoice-v3.5-flash",
    "prefix": "merchant",
    "url": "https://storage.example/signed-sample.wav"
  }
}
```

返回 `output.voice_id`，例如格式为 `cosyvoice-v3.5-flash-merchant-<唯一标识>`。以返回值为准，不自行拼接音色 ID。保存到当前租户、门店的 `voice_samples.provider_voice_id`；数据库 `id` 是本地样本 ID，与供应商 `voice_id` 不同。

创建拿到 ID 后先提交到数据库，再查询状态。状态查询失败保留 `CLONING` 和已有 ID，后续只查询，不重复创建。

## 状态查询与合成

同一 HTTP 地址发送：

```json
{"model":"voice-enrollment","input":{"action":"query_voice","voice_id":"<创建返回的 ID>"}}
```

| `output.status` | 本地状态 | 操作 |
| --- | --- | --- |
| `DEPLOYING` | `CLONING` | 等待处理，每 5 秒查询一次；查询失败暂停自动轮询并提示手动重试 |
| `OK` | `READY` | 可合成试听、设为主播 |
| `UNDEPLOYED` | `FAILED` | 审核未通过，提示换样本重新创建 |

页面打开后会继续查询已有处理中音色。`POST /api/voice-samples/{id}/refresh` 按当前登录用户校验租户和门店，不接受客户端自行传入供应商音色 ID。

合成 WebSocket 的 `run-task.payload` 使用：

```json
{
  "task_group": "audio",
  "task": "tts",
  "function": "SpeechSynthesizer",
  "model": "cosyvoice-v3.5-flash",
  "parameters": {
    "text_type": "PlainText",
    "voice": "<创建返回的 voice_id>",
    "format": "pcm",
    "sample_rate": 24000
  },
  "input": {}
}
```

收到 `task-started` 后发送 `continue-task` 文本，再发 `finish-task`，接收二进制 PCM。合成 `model` 必须等于创建时的 `target_model`；后端校验 CosyVoice ID 的官方模型前缀，阻止旧模型音色被当前模型误用。前端只传 `sampleId`，后端查库取得 ID 后用于试听及直播合成。

删除时用 `delete_voice + voice_id` 先删除供应商音色，再删除样本对象；保留授权审计。

## 验证边界

录音编码、权限异常与取消清理、创建请求参数、ID 保存及复用、状态转换和跨门店权限有自动化测试。真实麦克风音质与阿里云账号的克隆、合成效果仍需使用已授权样本实测；单元测试不会调用真实供应商。
