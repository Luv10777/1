# 直播语音供应商选型

核验日期：2026-09-28。仅阅读官方文档、服务条款、仓库 LICENSE 和模型卡；未开通账号、提交声音样本、调用付费接口或实测延迟。价格为本次读取到的公开原价，正式启用前以相应地域和账号的订购页为准。

## 建议

第一版推荐 **阿里云百炼托管 CosyVoice（北京地域）**。2026-09-30 按当前需求将克隆默认模型更新为 `cosyvoice-v3.5-flash`，使用该模型创建并返回的 `voice_id` 合成，具体配置与状态流程见 [v3.5 接入说明](cosyvoice-v35-clone-integration.md)。原先核验的 `cosyvoice-v3-flash` + `longanyang` 可通过配置继续使用，不把其系统音色或克隆 ID 自动迁移到 v3.5。

保留 **腾讯云「一句话版声音复刻」**作为国内托管备选。自部署选择 **Fun-CosyVoice3-0.5B-2512** 做第二阶段成本与效果评估，以 GPT-SoVITS 为效果对照；目前不为缺少数据的场景采购 GPU。

## 候选对比

| 候选 | 样本、克隆与流式能力 | 国内使用与商用依据 | 当前公开价格 | 文本提交到首段可播放延迟 |
|---|---|---|---|---|
| 阿里云百炼 CosyVoice，北京地域 | 官方建议 10–20 秒、最长 60 秒；至少 5 秒连续清晰朗读。WAV 16-bit / MP3 / M4A，≤10 MB，≥16 kHz；双声道仅处理第一声道。通过 `voice-enrollment` 建音色，不需要商家自行训练。支持 WebSocket 双向流式文本输入、音频输出。 | 北京地域服务；按量付费 API 的业务数据不用于模型训练。百炼协议明确商业化使用、素材授权和生成内容权利边界，见下文。 | `cosyvoice-v3-flash` **¥1 / 万计费字符**；`cosyvoice-v3.5-flash` **¥0.8 / 万**；v3.5-plus ¥1.5 / 万，v3-plus / v2 / v1 ¥2 / 万。**CosyVoice 创建音色免费**；账号最多 1,000 个音色，1 年未用于合成会自动清理。 | **未实测**。不能用 SDK 的服务端首包或模型宣传值代替浏览器首段播放。沿用下文统一验收指标。 |
| 腾讯云「一句话版声音复刻」+ 实时 / 流式文本语音合成 | 官方 FAQ：**5–15 秒**语义连贯录音；与要求接近。不是需要 10–20 分钟语料的「基础版」。官方计费页明确一句话版复刻音色支持实时语音合成和流式文本语音合成；WebSocket 返回二进制音频帧。 | 服务条款明确处理地点为中国境内；素材及人声需有合法授权；复刻 / 训练音色权利归用户或约定的权利主体。属于有付费合同的 PaaS 商用服务，不是开源权重授权。 | 训练最低公开包 **20 音色 ¥780（¥39/个）**，100 音色 ¥2,800。合成后付费日用量 0–10 万字符 **¥8 / 万字符**；更高阶梯 ¥7.6/7.2/6.8/6.4。音色创建后 3 个月免费存储，此后 **¥0.03 / 音色 / 日**。新账号可领取 5 音色 + 1 万合成字符试用，3 个月有效。 | **未实测**。当前没有账号，不能给供应商优劣排序或保证某个秒数。 |
| 自部署 CosyVoice / GPT-SoVITS | CosyVoice3 提供 zero-shot 与双向流式；先以同一段 10 秒样本做效果评估，未找到该权重对“固定 10 秒必达效果”的保证。GPT-SoVITS 官方说明支持 5 秒参考音频 zero-shot；`api_v2.py` 有 `streaming_mode` 和流式响应。 | 核验具体代码 LICENSE 与模型卡：CosyVoice3 代码和所列权重 Apache-2.0；GPT-SoVITS 代码 MIT，官方权重仓库模型卡 MIT。可以作为商用基础，仍需锁定实际使用的权重和独立依赖许可。 | 无按字符模型授权费；成本是 GPU / CPU、存储、带宽与运维。**未选机型和部署商，不能给出可靠月费或每小时成本**。按实测并发与 GPU 利用率计算，不以显存大小推算能承载多少直播间。 | **未实测**。冷启动、模型常驻、GPU 型号、并发、首句长度都会改变结果；不沿用原报告中未经实测的 0.8–4 秒区间。 |

来源：阿里云[声音复刻](https://help.aliyun.com/zh/model-studio/cosyvoice-clone-api)、[客户端事件](https://help.aliyun.com/zh/model-studio/cosyvoice-client-events)、[模型价格](https://help.aliyun.com/zh/model-studio/model-pricing)、[系统音色表](https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list)；腾讯云[一句话版样本要求](https://cloud.tencent.com/document/product/1283/47784)、[复刻计费](https://cloud.tencent.com/document/product/1283/93105)、[实时合成协议](https://cloud.tencent.com/document/product/1073/94308)。

### 价格不能直接按“汉字数”横比

阿里云当前语音计费说明：一个汉字计 **2 个字符**，英文、数字、标点、空格等通常各计 1 个字符。腾讯一句话版文档：汉字、字母、数字、标点、空格和回车均按 **1 个字符**计算。

例如仅算 10,000 个汉字、不含标点，阿里云 v3-flash 为约 **¥2**，v3.5-flash 约 **¥1.6**；腾讯一句话版低用量档约 **¥8**。这只是文本成本示例，不包含复刻、音色存储、我们的服务器和带宽，也不是直播一小时的固定成本。循环播放已合成的音频不会再次发生 TTS 字符调用费；改写并重新合成会再次计费。

腾讯普通「大模型音色」¥1.2/万字符等价格属于另一音色类别，**不能用来替代一句话版复刻音色 ¥8/万的价格**。阿里云 Qwen-TTS 克隆 ¥0.01/个也属于另一模型系列，不能套到 CosyVoice 上。

## 商用授权和数据条款核验

### 阿里云托管 API

阅读了现行[阿里云百炼服务协议](https://terms.alicdn.com/legal-agreement/terms/common_platform_service/20230728213935489/20230728213935489.html)，而非把开源 CosyVoice 的 Apache 许可套给托管服务：

- 4.3.1(2)、7.4：输入素材必须自有或获得授权；处理与生成不得侵犯人格权、著作权等。
- 4.3.1(3)：依法需要标识的生成内容应显著标识。
- 7.5：使用平台或关联公司提供的模型，且上传内容拥有合法知识产权，除另有约定或法律另有规定外，合成内容权利仍归用户；是否产生知识产权由用户判断。
- 7.6：允许评估后对外商业化使用，输入输出行为与相关后果由使用者负责；这不是“供应商兜底所有版权”的许可。
- 6.2.1–6.2.4：用户控制业务数据；按指令处理，不作未授权使用披露，支持按用户指令删除、更改。
- [隐私说明](https://help.aliyun.com/zh/model-studio/privacy-notice)及产品首页明确：**按量付费 API**的数据不用于模型训练。不要把此承诺扩展到 Token Plan 个人版 / Coding Plan，其数据条款不同。

因此适合有授权的商家自身声音用于商品播报。我们需要分别删除本地样本对象和供应商音色；删除本地录音不等于供应商音色已删除。音色删除接口可用。不能向商家暴露平台 API Key 或让多个商家共用一个裸露的供应商账号。

### 腾讯云托管 API

声音复刻[条款入口](https://cloud.tencent.com/document/product/1283/92971)明确适用[语音合成服务条款](https://cloud.tencent.com/document/product/301/120489)：

- 2.8：输入须有合法许可；涉及个人信息需知情同意，敏感个人信息需单独同意，授权应覆盖委托腾讯处理。
- 2.12：服务目的范围内，腾讯按用户委托使用音频、人声信息完成特定合成音训练。
- 2.13：处理地点中国境内；必要期间保留，期限结束后按条款或用户指示删除 / 返还；技术上暂不能删除时限制处理。
- 4.4–4.5：上传发布内容以及复刻 / 训练音色的权利归用户或其约定权利主体，用户仍需确保合法使用。
- 4.2：腾讯自己的模型、软件、系统音色等知识产权不随订阅转让，不得复制或转许可底层技术。

公开条款可支持商用 PaaS 接入；开通时仍需核对实际订单包含的一句话版音色权限、默认并发、音色删除方式和地域范围。目前没有账号，因此这些账号侧限制均未验证。

## 自部署许可证：锁到本次读取的版本

| 项目 / 精确对象 | 本次核验的代码或模型 revision | 许可证及结论 |
|---|---|---|
| CosyVoice 代码 | `074ca6dc9e80a2f424f1f74b48bdd7d3fea531cc` | [LICENSE](https://github.com/FunAudioLLM/CosyVoice/blob/074ca6dc9e80a2f424f1f74b48bdd7d3fea531cc/LICENSE)为 **Apache-2.0**。保留许可证、版权及 NOTICE（若存在），标识修改；不得暗示商标背书。 |
| `FunAudioLLM/Fun-CosyVoice3-0.5B-2512` 权重仓库 | `29e01c4e8d000f4bcd70751be16fa94bf3d85a18` | [该 revision 模型卡](https://huggingface.co/FunAudioLLM/Fun-CosyVoice3-0.5B-2512/blob/29e01c4e8d000f4bcd70751be16fa94bf3d85a18/README.md)元数据 `license: apache-2.0`。代码和这个权重均为宽松许可；不是对所有叫 CosyVoice 的第三方包做保证。 |
| GPT-SoVITS 代码 | `48b1a0169a28582a8984402f82cf438d3bfa6aca` | [LICENSE](https://github.com/RVC-Boss/GPT-SoVITS/blob/48b1a0169a28582a8984402f82cf438d3bfa6aca/LICENSE)为 **MIT**；保留版权与许可声明。 |
| `lj1995/GPT-SoVITS` 官方链接权重仓库 | `336b2ec4e8d4ac74740798dd40af44e74659ecaf` | [模型卡](https://huggingface.co/lj1995/GPT-SoVITS/blob/336b2ec4e8d4ac74740798dd40af44e74659ecaf/README.md)元数据 `license: mit`。该仓库包含多个世代文件；部署时仍须选定具体文件 hash，并审查另外下载的 BERT / HuBERT / BigVGAN 等组件的自身许可。 |
| `SWivid/F5-TTS` 官方权重 | `84e5a410d9cead4de2f847e7c9369a6440bdfaca` | [模型卡](https://huggingface.co/SWivid/F5-TTS/blob/84e5a410d9cead4de2f847e7c9369a6440bdfaca/README.md)为 **CC-BY-NC-4.0**。不能因代码是 MIT 就将这些权重投入收费 SaaS；本项目不选此官方权重作商用默认。 |

许可审查证明的是所读对象的公开许可状态，不证明训练数据、商家声音、广告内容或所有依赖均已授权。部署清单应记录代码 commit、模型文件 hash、LICENSE / 模型卡副本与第三方组件清单。

## 延迟口径与验收

**本次没有任何供应商首段延迟实测值。** 文档里的“150 ms”“首包”“实时”等宣传或局部指标，不直接填到对比表做端到端承诺。原报告中未经目标硬件实测的秒数已移除。

统一口径：`T_first_playable = 浏览器首次提交文本时间 → 首段真实语音可在 AudioContext 中排入播放并开始出声的时间`。记录同一条 trace 的：

1. 浏览器 → 我们后端的网络与鉴权耗时；
2. 我们的排队、文本预处理、供应商连接 / 重用耗时；
3. 供应商首段生成、返回首个可解码音频块的耗时；
4. 我们转发 → 浏览器的网络、解码及播放缓冲耗时。

如需首版容量设计，可暂给“浏览器和后端网络 + 转发”留 **100–300 ms**、“播放器缓冲”留 **100–250 ms**的工程预算；这只是国内网络良好且连接已建立时的**设计预算，不是供应商性能估算**。供应商生成与排队项没有账号就保持未知，不将这些预算加起来冒充完整延迟。冷连接额外计 DNS / TCP / TLS / WS 握手，首段可解码要求也要由实际编码验证。

取得账号后，用同一段已授权的 10 秒录音、同一组 20/80/200 字商品话术，分别测系统音色与克隆音色，冷 / 热连接、1/5/10 并发下至少各 30 次，记录 P50/P95、失败率、首段时间、整句时间与 request ID；再测 30 分钟连续播放。抖音观众端另有采集和平台缓冲，必须单独测，不能并入或省略后声称“秒回”。

## 第一版接口落地约束

`VoiceProvider` 应隔离供应商 model/voiceId：建立音色、查询状态、删除音色、合成音频。模型、地域、HTTP / WS endpoint、系统音色、连接和读取超时均可配置。账号未配置时明确返回“未配置语音服务”，不要将 mock 成功结果写成真实克隆完成。

阿里云当前已核验的协议（完整参数以官方文档为准）：

- 克隆 `POST https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/customization`，Bearer API Key；body `{"model":"voice-enrollment","input":{"action":"create_voice","target_model":"cosyvoice-v3-flash","prefix":"merchant","url":"<可访问的样本地址>"}}`。
- 查询把 `action` 改为 `query_voice` 并传 `voice_id`；删除为 `delete_voice` + `voice_id`。创建返回 `output.voice_id`；状态 `DEPLOYING / OK / UNDEPLOYED` 分别对应处理中 / 可用 / 未通过。
- WS `wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`；先 `run-task`，等待 `task-started`，再 `continue-task` 发送文本，最后 `finish-task`；音频通过 binary 帧返回，错误来自 `task-failed`。
- 复刻 `target_model` 与合成 `model` 必须一致；系统音色也必须来自该模型的音色表。当前官方系统音色表明确列出 `cosyvoice-v3-flash` + `longanyang`，不要把该音色自动迁移到 v3.5。
- `word_timestamp_enabled` 可用于句字时间元信息，但不是已计算好的安静停顿点；插播仍要服务端分析实际 PCM 能量并验证断点。`enable_aigc_tag` 支持范围依模型版本而异，隐性标识也不能替代直播平台要求的显著标识。
- 样本 URL 应使用短时签名的专用对象地址，不开放长期公共目录；供应商需要能访问它。创建音色与查询 / 删除应有服务端租户、门店和操作人校验。

官方协议来源：[接入方式](https://help.aliyun.com/zh/model-studio/cosyvoice-model-access)、[克隆 HTTP API](https://help.aliyun.com/zh/model-studio/voice-clone-design-http-api)、[客户端事件](https://help.aliyun.com/zh/model-studio/cosyvoice-client-events)、[服务端事件](https://help.aliyun.com/zh/model-studio/cosyvoice-server-events)。

## 本次未完成的验证

- 未注册、付费、提交真实样本或发起实际语音克隆，所以不声称克隆相似度、商家读音效果、供应商延迟已验证。
- 未下载权重或启动 GPU 推理；自部署吞吐和硬件成本未知。
- 火山引擎文档本次请求返回错误页，因此没有把未经核实的火山价格填入表格。已用腾讯云官方可读文档完成第二个托管候选。
- 本次建议只解决语音服务选型，不证明同机浏览器与抖音能同时稳定播放 / 录音；对录硬件、安卓 / iPhone 后台行为仍按单独的声音接法测试矩阵验证。
