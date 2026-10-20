# 商家入驻与实体商品展示

本次为现有页面扩展，面向准备开播的商家和购买直播收音配件的访客。页面先展示可售实物，再呈现商家入驻申请。商品名称、售价、图片与库存来自公开商品 API，订单复用现有交易接口。

视觉沿用一方志设计系统：宣纸 `--night`、墨青 `--ink`、朱砂 `--color-accent`、细线 `--line`。标题使用 `--font-serif`，正文使用 `--font-sans`，金额使用 `--font-mono`；4px 间距基准、4px 按钮圆角、6px 商品容器圆角。动效仅使用已有按钮反馈。

领域语汇为实体商品、直播收音、店铺、收货信息、订单与入驻档案。特征是实际配件双图与朱砂金额呈现，避免虚构销量、通用营销卡片和装饰性动效。设计尺度：构图变化 3、动效 1、信息密度 5、实物素材依赖 9、品牌保留 10。

品牌素材：`public/images/brand/yifangzhi-mark.png`；字体：`public/fonts/yifangzhi-serif-sc.woff2`、`public/fonts/yifangzhi-sans-sc.woff2`。产品图片由用户提供的 `一方志-32-1.png`、`一方志-32-2.png` 压缩为 JPEG 后通过素材上传接口入库，公开展示使用 API 返回的签名图片地址。

商城好物页（`/merchant-alliance`）的商品墙读取 `GET /api/public/products`，展示全平台已上架的实物商品，不再依赖手工维护的选品文件。访客可浏览并进入店铺下单；入驻申请在 `/merchant-alliance/join`，提交经营资料和读取申请记录需要认证。下单后由商家确认，目前没有线上付款。
