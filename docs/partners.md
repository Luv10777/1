# 用户 UID 与服务商

2026-11-11 新增。业务规则由业务方拍板，代码在 `backend/growth-api/.../partner/`、前端 `partner/`（服务商后台）和
`src/views/PlatformPartnersView.vue`（管理后台「服务商」页），迁移 V79。

## UID

- 每个账号有一个 UID（`users.uid`）：8 位随机数字，注册时由数据库默认值 `next_user_uid()` 生成，永不重复。
  V79 给已有账号逐个补号。
- 用户在 wy 站「套餐与权益」页能看到自己的 UID，登录接口和 `/api/auth/me` 也返回 `uid`。
- 管理后台「用户管理」能按 UID 搜索。

## 服务商

服务商就是平台开通的合作方，用自己已有的一方志账号登录 **partner.yifangzhi.com**。一个账号最多属于一家服务商，
一家服务商可以有几个登录账号。

| 谁 | 能做什么 |
|---|---|
| 平台（管理后台「服务商」页，限计费运营名单 `BILLING_OPERATOR_USER_IDS`） | 建服务商并指定登录账号（按 UID）；线下收款后给服务商发烟火；按套餐标识分别设激活码总额度（例如月卡 20 个）；停用 / 启用；看名下商户和烟火流水；按 UID 查商户归属并改归属或解除归属 |
| 服务商（partner.yifangzhi.com） | 看可分配烟火、各套餐额度和流水；按 UID 把商户加到名下；把烟火分给名下商户；在额度内生成激活码 |

### 名下商户与归属

- 归属按**商户**算（同一商户的管理员和店员一起），以第一次为准：
  - 服务商按 UID 添加（来源 `UID`）；
  - 商户兑换了某家服务商生成的激活码，且当时还没有归属（来源 `CODE`）。
- 已经归属的商户，别家服务商不能再按 UID 添加；兑换别家的激活码照样开通会员，但不改归属。
- 只有平台能改归属（来源 `PLATFORM`）或解除归属。

### 烟火

- 平台发放：服务商余额增加，记一条 `GRANT` 流水（备注写收款流水号）。同一请求只发一次。
- 服务商分给名下商户：服务商余额减少（`TRANSFER` 流水），商户的**充值烟火**增加（商户账本 `PURCHASE`「服务商「名称」分配烟火」）。
  不按这笔记邀请返利。只能分给自己名下的商户，余额不足时拒绝。
- 两边分两步记账：先扣服务商、再给商户入账，两步都按 `requestKey` 去重。商户入账那一步失败时，页面保留同一个
  `requestKey`，再点一次「分配」即可补上，不会重复扣服务商的烟火。
- 烟火看板的「累计购买」把这部分单列为来源 `PARTNER`。

### 激活码

- 额度按套餐标识设（`partner_code_quotas`），平台设的是总数，不能低于已经生成的个数。
- 服务商生成时选套餐和数量，按该标识**最新**的版本生成（上没上架都行，站内不卖的套餐不上架），额度扣减和生成在同一事务；同一请求重复提交返回同一批码，不重复扣额度。
- 服务商生成的码记在 `billing_redeem_codes.partner_id`，和平台生成的激活码一样一码一用；兑换后按上面的规则决定归属。

## 数据

V79 新增 `partners`、`partner_members`、`partner_code_quotas`、`partner_tenants`、`partner_credit_ledger`，
并给 `billing_redeem_codes` 加 `partner_id`。这几张表和 `billing_redeem_codes`、`billing_plans` 一样是平台级数据，
**不开行级隔离**：服务层先核对平台运营或服务商身份，服务商只能读写自己那一家。分给商户的烟火写进商户自己的账本，照旧受 RLS 约束。

## 接口

服务商后台（登录账号必须属于一家启用中的服务商，否则 403）：

| 接口 | 说明 |
|---|---|
| `GET /api/partner/me` | 服务商、可分配烟火、各套餐额度、名下商户数 |
| `GET /api/partner/tenants` | 名下商户（含会员状态和烟火余额），`keyword` 按商户名、联系人、UID 搜 |
| `POST /api/partner/tenants` | `{uid}` 按 UID 添加 |
| `POST /api/partner/tenants/{tenantId}/credits` | `{requestKey, credits, note}` 分烟火 |
| `GET/POST /api/partner/codes` | 查看 / 生成激活码，生成参数 `{requestKey, planCode, quantity, note}` |
| `GET /api/partner/ledger` | 烟火流水 |

平台（计费运营名单）：`/api/platform/partners` 下的列表、新建、登录账号增删、启停、发烟火、设额度、名下商户、流水、
`GET lookup?uid=` 查归属、`PUT assignments/{tenantId}` 改归属。

## 上线

1. 后端随 V79 发布（V78 激活码需同时或先行上线）。
2. 构建服务商后台：`npm run build:partner`，产物在 `dist-partner/`。部署方式同管理后台（静态文件 + `/api/` 反向代理到 API，
   页面加 `X-Robots-Tag: noindex`），参照 `deploy/nginx.admin.conf`。
3. 在阿里云给 `partner.yifangzhi.com` 加解析，配好 HTTPS 证书。页面和 `/api/` 同域（nginx 反向代理），
   但和 wy 子域名一样，要把 `https://partner.yifangzhi.com` 加进 API 配置的 `growth.cors.allowed-origins` 并重启 API，否则请求会被跨域校验拒绝。
4. 在管理后台「会员套餐」新建月卡、季卡、年卡（不上架）后，才能给服务商分额度。

## 未做

- 服务商不能作废自己生成的激活码；需要作废时由平台在「兑换码」页作废（额度不退回）。
- 服务商分给商户的烟火不能撤回。
