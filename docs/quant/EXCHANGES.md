# 交易所与账户接入

## 已接入范围（2026-10-03）

OKX 保留既有现货私有交易链路。Binance 已接入公开行情、历史下载/回测，以及独立 DPAPI 凭据、HMAC 签名、账户余额、交易规则、限价下单/查询/撤单和成交费用适配。2026-10-05 已完成实际小额 BUY/SELL 成交与费用、归属库存/交易所余额和零挂单对账，保留无法单独出售的尾仓；默认执行已关闭。具体证据与限制见 BACKTEST，自动信号实盘仍待独立验收。两交易所不得互换凭据。

历史回测页面新增公开行情面板和下载交易所选择。选择 Binance 行情不改变当前交易账户、策略门禁、组合或执行开关。公开请求使用独立的主机代理 `QUANT_DATASET_HTTP_PROXY`，不加载账户凭据。

Binance 使用官方公开数据域名 `data-api.binance.vision` 的 `/api/v3/time`、`/api/v3/ticker/bookTicker` 和 `/api/v3/klines`。接口及时间字段依据 [公开行情域名说明](https://developers.binance.com/en/docs/products/spot/faqs/market_data_only) 与 [K 线接口](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/market)。K 线按 UTC 开盘时间排序，排除未收盘项，拒绝重复、缺口、无效 OHLCV、无效收盘时间及过期闭盘数据。

历史下载保留结束日不含、240 根预热、连续小时校验、摘要和编号不可覆盖。原请求省略 exchange 时仍为 OKX，其幂等摘要保持兼容；相同请求标识改变交易所拒绝。命令行可用 `python script/quant/prepare_dataset.py --exchange binance --id <新编号> --start <UTC日期> --end <UTC日期>`，参数不包含交易密钥。

## 固定账户归属

028 增量新增 `quant_exchange_account`，原门禁、订单和组合的 account_id 固定迁入 `okx-primary`，不搬移库存或重算历史费用。自动会话和信号沿其门禁关联账户。账户登记为部署级资源，同一账户仍由所属策略、租户和用户共享账户总限额；登记本身不授予策略操作权限。

单体配置位于 `yudao-server/application-quant.yaml`：

- `QUANT_LIVE_EXCHANGE`，默认 okx。
- `QUANT_LIVE_ACCOUNT_ID`，默认 okx-primary。
- `QUANT_LIVE_CREDENTIAL_FILE`，默认空；显式配置时须位于当前工作目录的 credentials 下，并匹配登记的加密文件名。

私有客户端先核对配置交易所与账户、加密文件引用，再通过 [OKX 账户配置接口](https://www.okx.com/docs-v5/en/#trading-account-rest-api-get-account-configuration) 核对当前 UID。数据库仅保存交易所/UID 摘要，不保存明文 UID 或密钥；允许同一 UID 正常轮换 API Key，不允许换成另一真实账户复用原账本，也不允许同一 UID 改名登记为另一账户以重置额度。身份写入若随订单检查事务回滚，后续请求会重新持久化核对，不因内存缓存跳过绑定。

账户额度互斥、活动预约、日交易额及超时订单按 account_id 隔离；归属库存仍按原门禁和会话计算。门禁启用、订单令牌、订单查询/撤单/退出、组合启停和私有只读查询均拒绝非当前账户的执行操作。历史记录继续可读，接口返回 accountId；旧收益不会转为新账户资金。准入执行另核对成功回测所属交易所，Binance 回测不能启用 OKX 门禁。

当前单体一次只选择一个私有账户，不支持同时调度多个交易所账户。切换需先停止会话/组合、处理挂单、关闭执行开关，再明确选择已登记账户及其独立加密文件并重启；旧账户历史仍保留。私有客户端按配置路由；Binance 凭据必须带 exchange=binance 标签，旧无标签文件只兼容 OKX。

## 接口

- GET `/quant/backtest/exchange-account/current`：当前固定账户、身份核对状态、私有执行支持范围；不返回密钥、文件路径或 UID 摘要。
- POST `/quant/backtest/exchange-account/register`：id、exchange（okx/binance）、credentialFileName（仅安全文件名，以 .dpapi 结尾）；仅执行开关关闭时登记，不创建密钥、不覆盖已有账户、不自动切换。新账户必须使用自己的加密文件，随后由交易所核对真实身份。
- GET `/quant/backtest/market/snapshot?exchange=binance`：公开行情、交易所时间、最新闭盘时间和规范化 OHLCV；同入口接受 okx。
- POST `/quant/backtest/dataset-download/create`：原请求追加可选 exchange；下载任务、清单和回测保留交易所来源。

## 实际证据与后续

Binance/OKX 公共行情各实际读取 199 根闭盘 K 线。下载任务 2d8029be-2c17-464c-ae4a-7b1a5297434c 成功，数据集 binance-btc-20260929-20261001-v1 包含 288 根 K 线、0 缺口，摘要 d187c965b9025452bb95815047ad5f52b005414c19777a48686035dba01807b1。EMA30/90 的 Freqtrade 回测 e8d8e0e0-2806-45dc-a751-59b4a31f394b 成功，证明 Binance 数据能进入原回测链路；两天样本不作为策略盈利证据。

原 OKX 加密凭据仅在三个执行开关均关闭的临时只读服务核对，okx-primary 身份绑定已完成；结束后恢复无凭据默认服务。78 项 Java 测试、4 项 Python 测试、单体 install、前端 ts:check/build:local 通过；包含事务回滚后身份绑定、跨账户额度及跨交易所准入拒绝。本轮无真实订单或新增浏览器交互验收。

Binance 独立凭据、权限、同版本研究/模拟和准入双确认及手动小额交易已完成。自动会话已有短时自然 NONE 信号、重复 K 线幂等、对账/停机与历史账户显示证据，尚无自动 BUY/SELL 实际成交证据。运行面板账户筛选仅查看历史，不切换当前部署；错账户私有操作拒绝。新会话要求实际 USDT 可用余额覆盖所选预算；2026-10-05 首轮零余额已阻止新会话，后续统一资金入口实际复查可用 USDT 为 9.14387178，不自动划转资金或改写历史库存。多交易所策略方案与运行计划入口已统一；下一项 Binance 候选受控运行与收益跟踪，旧 OKX 候选仍待各自人工复核，不视为组合已运行。

## 统一运行准备入口（2026-10-05）

GET `/live-control/run-plan` 显示研究/模拟来源交易所与部署执行账户，并在同报告门禁中优先选择当前账户；只做本地证据/预算校验。POST `/live-control/run-check?reportId=...` 显式读取当前账户，先校验源码、双确认和账户来源，再检查 USDT/BTC 可用余额、实时首次 BUY 预估、规则/权限、挂单、共享日额与敞口。请求预算为 orderNotional、maxSessionLoss、feeBps、slippageBps，实际余额缺失不按零补造。候选选择不会切换凭据，导出的资金结果不预约或锁定资金。

Binance 复用已有最小金额、数量步长、价格刻度、权限及 BNB 费用检查；预算 5 USDT 向下取数量可能低于最低金额，界面展示该缺口，不自动调大。OKX 新增只读规则核对依据 [OKX API 文档](https://www.okx.com/docs-v5/en/) 的 public/instruments（SPOT、live、tickSz/lotSz/minSz）与 account/config 的 perm；需要 read_only,trade 且无提现权限。这里只增加运行准备检查，原实际 OKX 下单路径保持。

两家实际只读检查已在所有执行开关关闭时通过资金、规则及零挂单/额度项，readiness 因 HALTED 门禁与关闭开关仍 false，无新会话或订单。Binance 当前可用 USDT 9.14387178，OKX 74.42323355990514；均为查询时余额，不承诺后续可用。102 项 Java 回归、单体构建及前端类型/构建检查通过；默认恢复 okx-primary、三开关 false。详细价格/数量与非秘密摘要见 BACKTEST。本阶段没有自动实际成交或浏览器交互验收，不支持同时运行两家私有账户。

## Binance 私有适配与操作（2026-10-04）

已登记部署账户 binance-primary，独立文件名 binance-live.dpapi，身份已绑定。默认仍为 okx-primary，三个执行开关关闭。新增 POST `/exchange-account/binance-verify` 核对 UID 与 API 权限，不发送订单、不启用门禁；GET `/market/binance-rules` 使用无凭据公开域名。原策略只读路径继续兼容，并新增 `/live-control/private-readiness`、`/live-control/private-read`；Binance 确认语为 CONFIRM_BINANCE_PRIVATE_READ。

在项目目录的 PowerShell 隐藏输入 Binance HMAC API Key 与 Secret（无需 Passphrase）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\script\quant\save_live_credential.ps1 -Exchange binance -CredentialPath .\.runtime\quant\credentials\binance-live.dpapi
```

文件只由当前 Windows 用户解密，不进入 Git、数据库、命令行参数或聊天。账户选择参数为 QUANT_LIVE_EXCHANGE=binance、QUANT_LIVE_ACCOUNT_ID=binance-primary、QUANT_LIVE_CREDENTIAL_FILE 指向上述文件。私有基础域名限定 https://api.binance.com；公开数据使用独立主机代理 QUANT_DATASET_HTTP_PROXY，私有使用 QUANT_EXCHANGE_PROXY。没有凭据不切换默认服务。

使用 PowerShell 7。旧 ConsoleHost 的安全输入可能把 Ctrl+V 记录为 U+0016，而非粘贴内容；单个星号及保存成功提示不能证明输入有效。脚本现仅在明确收到 Ctrl+V 粘贴字符时读取本机剪贴板，不打印内容；空、单字符和控制字符输入会重试，成功接收后显示实际字符数量。先复制当前字段的完整值，再粘贴、回车，两项需分别复制。若机器没有 pwsh 命令，可让助手打开已有的 PowerShell 7 运行时窗口。保存成功仍需实际只读账户验证。

签名与字段依据 [签名及时间窗口](https://developers.binance.com/en/docs/products/spot/rest-api)、[账户/订单/成交接口](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/account)、[限价下单/撤单](https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/rest-api/trade)、[API 权限](https://developers.binance.com/en/docs/catalog/core-trading-wallet/api/rest-api/account) 与 [过滤器](https://developers.binance.com/en/docs/products/spot/filters)。每次私有操作核对 UID 和账户摘要，不能跨账号复用账本；仅 GET 传输错误有限重试，下单/撤单最多发送一次。超时、5xx 或未知错误保持提交不确定及原预约，按原 clientOrderId 对账，不自动重下。诊断不含签名 URL、密钥、原始响应或异常原因链。

下单前核对现货权限，提现/划转/借贷/合约等额外权限必须明确关闭；权限字段缺失也拒绝交易。新下单要求关闭 BNB 手续费折扣，不替用户修改交易所设置。账户权益只估值 BTC/USDT；BTC 包含可用与锁定数量，以实时买价估值，不宣称覆盖其他币种的全账户权益。

规则在消费令牌/预约之前检查，并在实际下单前复查。自动买价向下、卖价向上取 PRICE_FILTER 刻度，数量向下取 LOT_SIZE，调整后的金额进入原门禁；人工价格/数量不默默改写。实际公开查询得到 tickSize=0.01、stepSize=0.00001 BTC、minNotional=5 USDT。默认 5 USDT 预算向下取数量后可能低于最低金额，需要在既有 10 USDT 单笔上限内明确选择可成交预算，不能自动扩大资金。

成交按订单 ID 读取最多 1000 笔 myTrades，核对成交 ID 唯一、交易对、方向、归属、累计数量与报价总额，计算实际均价和费用。累计费用覆盖写入，不重复累加。缺失、重复、数量不齐或混合币种费用保持成本不完整；第三币种（如 BNB）保留实际金额与币种，现有估值明确不完整，不按研究费率补造或换成零费用。达到 1000 笔不宣称费用完整，需后续分页扩展。

模拟盘就绪和命令预览按研究批次成功任务选择交易所，混合来源拒绝。Binance 准入另核对同版本候选模拟的绑定就绪清单交易所及摘要，旧 OKX 候选模拟不能替代。已有平台长期模拟/风险停机机制证据可复用，不要求再等待 24 小时；新报告仍需人工复核，历史报告不重写。真实 Binance 私有连接已验证，交易仍待权限及上述证据。

## Binance 实际只读连接（2026-10-04）

- 通过平台 binance-verify 接口使用独立 DPAPI 文件完成真实 HMAC 请求和 UID 摘要绑定，connected=true、ordersSent=0；没有输出 Key、Secret 或 UID。非秘密摘要保存在 .runtime/quant/binance-account-readonly-20261004.json。
- 实际返回 canTrade=true、enableReading=true，但 enableSpotAndMarginTrading=false；账户允许交易不等于此 API Key 被授予现货交易权限。enableWithdrawals、enableMargin、enableFutures 均 false，ipRestrict=false。其它额外权限及 BNB 折扣尚未完成实际下单前核对，不视为实盘准入已通过。
- 临时服务始终关闭模拟执行、实盘执行和自动执行；结束后恢复默认无凭据 OKX 服务、健康 UP、三个开关 false。用户已开启该 Key 的现货交易权限且复核通过，下一步补齐同版本模拟及准入证据；不需重新输入未变化的密钥。

2026-10-04 更新：Binance 同版本短时模拟完成（3 条快照、2 次成功对账，无错误告警，已停止）。准入报告可通过单体 QUANT_LIVE_IP_WHITELIST_REQUIRED 配置如实记录边界，默认 true。用户明确选择不绑定 IP 时，本次临时只读部署设 false，报告明确密钥可从任意出口使用；该配置仅改变新报告证据，不开启交易、不修改旧报告、旧双确认不能转移。新报告 0a9b0b9b-d998-4216-8a65-45f93e106ccf 技术检查全通过，仍需报告双确认；下单前实际核对其它权限、币对白名单、BNB 折扣和余额。
