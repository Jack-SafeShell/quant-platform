# 交易所与账户接入

## 已接入范围（2026-10-03）

OKX 保留既有现货私有交易链路。Binance 接入公开 BTC/USDT 买卖报价、已收盘 1h K 线、历史下载和 Freqtrade 历史回测；尚未接入 Binance 余额、签名、下单、撤单或费用账本，不应配置 Binance 密钥来替代 OKX 密钥。

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

当前单体一次只选择一个私有账户，不支持同时调度多个交易所账户。切换需先停止会话/组合、处理挂单、关闭执行开关，再明确选择已登记账户及其独立加密文件并重启；旧账户历史仍保留。Binance 私有客户端尚未实现，切换配置为 Binance 不会使 OKX 客户端读取 Binance 凭据或发送请求。

## 接口

- GET `/quant/backtest/exchange-account/current`：当前固定账户、身份核对状态、私有执行支持范围；不返回密钥、文件路径或 UID 摘要。
- POST `/quant/backtest/exchange-account/register`：id、exchange（okx/binance）、credentialFileName（仅安全文件名，以 .dpapi 结尾）；仅执行开关关闭时登记，不创建密钥、不覆盖已有账户、不自动切换。新账户必须使用自己的加密文件，随后由交易所核对真实身份。
- GET `/quant/backtest/market/snapshot?exchange=binance`：公开行情、交易所时间、最新闭盘时间和规范化 OHLCV；同入口接受 okx。
- POST `/quant/backtest/dataset-download/create`：原请求追加可选 exchange；下载任务、清单和回测保留交易所来源。

## 实际证据与后续

Binance/OKX 公共行情各实际读取 199 根闭盘 K 线。下载任务 2d8029be-2c17-464c-ae4a-7b1a5297434c 成功，数据集 binance-btc-20260929-20261001-v1 包含 288 根 K 线、0 缺口，摘要 d187c965b9025452bb95815047ad5f52b005414c19777a48686035dba01807b1。EMA30/90 的 Freqtrade 回测 e8d8e0e0-2806-45dc-a751-59b4a31f394b 成功，证明 Binance 数据能进入原回测链路；两天样本不作为策略盈利证据。

原 OKX 加密凭据仅在三个执行开关均关闭的临时只读服务核对，okx-primary 身份绑定已完成；结束后恢复无凭据默认服务。78 项 Java 测试、4 项 Python 测试、单体 install、前端 ts:check/build:local 通过；包含事务回滚后身份绑定、跨账户额度及跨交易所准入拒绝。本轮无真实订单或新增浏览器交互验收。

下一项为 Binance 私有账户适配：独立加密凭据、签名/权限、余额/交易规则、订单与成交费用归一化，再沿原风险链路完成受控小额验收。旧 OKX 候选仍待人工研究复核和模拟补证，不视为组合已运行。
