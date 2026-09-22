# 阿里云验证码短信配置

更新日期：2026-09-18。后端通过阿里云官方 Java SDK 发送验证码，用户在手机上收取短信，
再输入登录页完成校验。阿里云返回受理成功不等于运营商已送达，实际到达情况以手机和发送记录为准。

## 开通与审核

1. 登录[短信服务控制台](https://dysms.console.aliyun.com/)，开通国内短信并完成要求的企业认证。
2. 在签名管理中申请签名，例如“一方志科技”；提交企业资质，使用最终审核通过的名称。
3. 在模板管理中申请“验证码”类型模板，变量名使用 `${code}`。示例内容：
   “您的验证码为 ${code}，5 分钟内有效，请勿泄露给他人。”保存审核通过的 `SMS_...` 模板编号。
4. 在 [RAM 访问控制](https://ram.console.aliyun.com/)中创建程序访问用户，
   授予 `dysms:SendSms` 权限并创建 AccessKey；不要使用主账号 AccessKey。
5. 确认账号余额或短信套餐可用，并在短信控制台配置发送频率和用量告警。

仅发送验证码的 RAM 自定义权限策略可以使用：

```json
{
  "Version": "1",
  "Statement": [
    { "Effect": "Allow", "Action": ["dysms:SendSms"], "Resource": "*" }
  ]
}
```

## 填写后端配置

本地配置文件为 `backend/growth-api/.env`，不是仓库根目录的前端 `.env`。
它采用 Java properties 格式，值不要加引号；密钥只保存在服务端，该文件已被 Git 忽略。

```properties
SMS_PROVIDER=aliyun
ALIYUN_SMS_ACCESS_KEY_ID=填写RAM用户AccessKeyId
ALIYUN_SMS_ACCESS_KEY_SECRET=填写RAM用户AccessKeySecret
ALIYUN_SMS_SIGN_NAME=填写已审核通过的签名名称
ALIYUN_SMS_TEMPLATE_CODE=填写已审核通过的SMS_模板编号
```

生产部署在后端服务的环境变量或密钥管理中设置同名变量，不使用 `VITE_` 前缀。
填写完成后，在后端目录重启 API 进程；环境变量优先于 `.env`。

```powershell
cd backend/growth-api
mvn spring-boot:run
```

`SMS_PROVIDER=console` 只用于本地调试：验证码打印在后端控制台，手机不会收到短信，
登录页会明确提示调试模式。`SMS_PROVIDER=aliyun` 配置不完整时返回“短信服务尚未配置完成”，
不会退回控制台模式，也不会返回验证码明文。

## 联调与故障定位

在登录页填写自己的中国大陆手机号，勾选协议后点击“获取验证码”，再输入手机收到的六位数字。
成功登录后，同一验证码不能再次使用；五分钟过期，连续输错五次后必须重新获取。
每个手机号一分钟最多发送一条，滚动 24 小时最多十条。

`POST /api/auth/send-code` 接收 `{"phone":"中国大陆手机号"}`，成功返回：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "developmentMode": false,
    "retryAfterSeconds": 60,
    "expiresInSeconds": 300
  }
}
```

登录仍调用 `POST /api/auth/login`，提交 `phone` 和 `code`，成功后签发登录令牌。
发送失败会回滚本次验证码记录；调用超时不会自动重试，以避免重复发送。
若超时后仍收到短信，该条验证码可能未落库，稍后重新获取即可。

| 现象或业务码 | 处理方法 |
|---|---|
| 页面提示本地调试模式 | 将后端 `SMS_PROVIDER` 改为 `aliyun` 并重启 |
| 2007，短信服务尚未配置 | 检查四个阿里云配置项是否都已填写，以及启动目录是否正确 |
| 2008，短信发送失败 | 检查 RAM 权限、签名与模板审核状态、余额；在阿里云控制台查看失败原因 |
| 2001 / 2002，发送受限 | 等待一分钟或滚动 24 小时额度恢复 |
| 已提交但手机未收到 | 查看阿里云发送记录及运营商回执，检查手机拦截箱 |

自动化测试使用模拟短信客户端和独立测试数据库，不向真实手机号发短信。
真实收信联调需在签名、模板及密钥配置完成后进行；仅通过自动化测试不能认定手机送达已验证。
