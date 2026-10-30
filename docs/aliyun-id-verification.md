# 阿里云实名核验配置

更新日期：2026-10-30。用户在「账号设置 → 实名认证」提交姓名和居民身份证号码后，后端调用阿里云实人认证的
身份二要素核验接口 `Id2MetaVerify`，由权威数据源判断二者是否一致。
接口文档见[身份二要素核验 · 纯服务端（API）接入](https://help.aliyun.com/zh/id-verification/information-verification/developer-reference/vatsl9lfmbwe74iv)。

## 结果怎么用

| 阿里云返回 | 实名申请状态 | 用户看到 |
|---|---|---|
| `BizCode=1` 一致 | 已通过 | 立即显示「实名核验通过」 |
| `BizCode=2` 不一致 | 未通过 | 提示核对后重新提交，并显示人工客服电话 |
| 其他结果、调用失败或超时 | 不保存 | 提示「实名核验暂时不可用，请稍后再试」 |

- 实名不做人工审核，只有明确的一致才算通过；拿不到结论时什么都不保存，也不算一次不一致。
- 同一用户滚动 24 小时内核验不一致满 3 次，之后提示「今天核验不一致的次数已用完，请明天再试」，不再调用阿里云。
  每次核验都计费，这样也防止有人用来批量试探别人的姓名和号码。
- 调用不自动重试（连接 3 秒、读取 5 秒超时），避免重复计费。
- 每次自动核验在平台审计里记一条「自动核验一致 / 不一致」，带阿里云请求号，便于和账单对账。
  日志和审计不记录姓名、号码或阿里云原始报文。
- 已经人工审核通过的旧记录保持不变。

## 开通

1. 登录[实人认证控制台](https://cloudauth.console.aliyun.com/)，开通服务，确认身份二要素核验可用，按需购买资源包。
2. 在控制台配置**消费预警和资源包余量预警**。官方文档提醒，信息核验不会按调用次数自动封顶。
3. 在 [RAM 访问控制](https://ram.console.aliyun.com/)中创建程序访问用户，只授予实人认证相关权限并创建 AccessKey。
   **不要复用短信的 AccessKey，也不要使用主账号 AccessKey。**

## 填写后端配置

本地写在 `backend/growth-api/.env`（Java properties 格式，值不加引号，已被 Git 忽略）；
生产在后端服务的环境变量或密钥管理中设置同名变量。

```properties
IDENTITY_VERIFIER=aliyun
ALIYUN_IDV_ACCESS_KEY_ID=填写RAM用户AccessKeyId
ALIYUN_IDV_ACCESS_KEY_SECRET=填写RAM用户AccessKeySecret
# 可选，默认上海地域
ALIYUN_IDV_ENDPOINT=cloudauth.cn-shanghai.aliyuncs.com
```

改完重启后端。`IDENTITY_VERIFIER` 默认是 `none`，此时提交实名会提示暂时不可用。
选了 `aliyun` 但 AccessKey 没填时，后端日志提示未配置，提交同样提示暂时不可用。
RAM 子账号缺 `AliyunYundunCloudAuthFullAccess` 时阿里云返回 `Forbidden.NoPermission`，表现也是暂时不可用。

**启用前**请确认：《隐私协议》和《实名审核与敏感个人信息处理说明》已写明向阿里云提供姓名和身份证号码用于核验，
且已经法务确认（`src/domain/legalPolicies.js`）。

## 联调

自动化测试使用模拟的阿里云客户端，不调用真实接口。配置真实 AccessKey 后，用本人资料在实名页面提交一次，
应立即显示「已通过」；再在平台管理的实名审计里核对请求号，并在阿里云控制台看到对应调用记录。
仅通过自动化测试不能认定真实接口已经跑通。

| 现象 | 处理方法 |
|---|---|
| 提交后提示「实名核验暂时不可用」 | 看后端日志：「AccessKey 未配置」检查三个变量；「没有给出结论」或「调用失败」按日志里的 providerCode 和 requestId 到阿里云控制台查，并核对 RAM 权限 |
| 本人资料显示不一致 | 核对姓名中的生僻字、「·」以及身份证末位 X，再提交一次 |
