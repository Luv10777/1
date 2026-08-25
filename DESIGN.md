# 梧曜星枢设计系统

深夜控制台（Night Console）：用夜幕墨色承载高密度运营工作，以星尘紫表达行动，以冷电青/绿色表达系统状态。页面服务于“扫读状态 → 选择动作 → 查看结果”的日常节奏。

## Tokens

- `--night` `#070914`：全局背景；`--night-panel` `#101221`：面板层
- `--ink` `#f4f2ff` / `--ink-soft` `#b9b7c7` / `--ink-muted` `#7b798c`：文字层级
- `--violet` `#625BF6` / `--violet-bright` `#8B5CF6`：主要操作和品牌识别
- `--cyan` `#22D3EE`：运行中、内容工具的辅助状态
- `--green` `#5DD39E` / `--amber` `#F3B66D` / `--red` `#F47786`：语义状态

标题/数字使用 Manrope，中文正文使用 Noto Sans SC，技术状态使用 IBM Plex Mono。4px 为基础间距，面板 14px 圆角、控件 9px 圆角。深度由低对比边框和微弱内阴影构成，不使用大面积阴影或装饰渐变。

所有主要控件覆盖 default / hover / focus / disabled / loading；第一阶段默认 Mock API，不展示真实供应商数据。
