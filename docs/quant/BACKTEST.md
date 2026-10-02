# 首个历史回测闭环

固定路线：Freqtrade + Spring Boot 模块化单体。无需重复技术选型评审。

## 已实现

`yudao-module-quant-api` 定义请求与状态；`yudao-module-quant-server` 提供控制器、服务、JDBC 持久化、单线程持久队列及 Freqtrade CLI 适配器。根 Maven reactor 和 `yudao-server/pom.xml` 均已接入。不是独立微服务，无新 Nacos 配置。

前端 `/quant/backtest` 提供提交、列表、详情和成交结果。API 使用芋道既有认证及 `quant:backtest:create` / `quant:backtest:query` 权限；操作只可读取当前租户、当前用户的任务。`sql/quant/002_backtest_menu.sql` 以幂等方式注册“量化研究 / 历史回测”动态菜单和权限按钮；普通角色仍需由管理员分配菜单权限，不提供匿名或免鉴权入口。

范围限定：OKX/Binance、BTC/USDT 现货、1h、固定 QuantEmaBaseline 策略。当前自动数据准备脚本接 OKX；Binance 数据集可按相同清单格式准备，但本轮没有 Binance 实测。策略 EMA20/EMA60、止损 2%、ROI 4%、只做多，240 根预热。只能调整区间、初始资金、单笔投入、单边手续费。不是盈利策略推荐。

成功任务详情可导出 Markdown 实验报告或 JSON 可复现清单。导出内容固定关联策略和行情 SHA-256、请求参数、引擎镜像/版本、产物摘要及核心指标；JSON 清单另含自身内容摘要，不包含策略源码或凭据。

参数优化批次只接受 2 至 5 个当前用户已有参数集，并为每组参数分别建立训练和验证回测。切分日是训练结束和验证开始，两段不重叠且各至少 7 天；批次本身不自动选择或应用参数。

## 单体配置

实际配置位于 `yudao-server/src/main/resources/application-quant.yaml`，由主 `application.yaml` 显式导入。默认关闭执行开关，完成迁移和数据准备后通过当前启动进程环境启用：

```powershell
$env:JAVA_HOME='C:\Users\Jack\.jdks\corretto-25.0.4'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:QUANT_BACKTEST_ENABLED='true'
$env:QUANT_WORKSPACE='D:\0000\quant-platform\.runtime\quant'
# 本机公共交易所请求需要代理时使用；不是交易所凭据：
$env:QUANT_EXCHANGE_PROXY='http://host.docker.internal:3066'
$env:QUANT_DATASET_HTTP_PROXY='http://127.0.0.1:3066' # 主机 Python 下载公开行情；与容器代理独立
# 基础库与依赖具备后，从根目录启动：
java -jar yudao-server/target/yudao-server.jar
```

支持 QUANT_DOCKER_EXECUTABLE、QUANT_FREQTRADE_IMAGE、QUANT_BACKTEST_TIMEOUT。镜像要求官方仓库 sha256 摘要固定，当前为已实测 Freqtrade 2026.8；超时默认 600 秒，范围 30..3600 秒。运行账户需要本机 Docker 权限。Docker Engine 与该应用必须在同一台主机，工作目录使用绝对路径。

模拟盘执行总开关 `yudao.quant.paper-execution-enabled` 在单体 `application-quant.yaml` 中固定为 false，当前不提供环境变量覆盖。一次性启动令牌有效期 `paper-start-token-ttl-seconds` 固定为 300 秒，分钟快照保留期固定为 30 天。`paper-risk-v1` 固定单笔/总暴露 100 USDT、最多 1 个持仓、日内亏损 20 USDT、回撤 5% 且禁止实盘；策略版本和限额进入就绪清单摘要。就绪快照只运行 `docker --version`、检查工作目录并生成 dry-run 配置清单，不启动容器。

首个真实 dry-run 只允许在人工验收窗口内通过单次进程参数临时覆盖，仓库配置始终保持 false。开始前先在页面确认“状态与日志”的启动前检查全部通过，再签发一次性令牌；随后停止当前单体进程，并仅对本次启动追加：

```powershell
java -jar yudao-server/target/yudao-server.jar --yudao.quant.paper-execution-enabled=true
```

验收完成后先在页面精确停止执行计划，再停止该单体进程；按原启动命令重新启动（不携带上述参数），并在页面确认总开关显示“关闭”、任务已进入 STOPPED 或 FAILED。若页面停止失败，使用执行计划显示的唯一 `quant-platform-paper-<UUID>` 容器名执行 `docker stop --time 20 <容器名>`，绝不使用批量删除或 `remove-orphans`。启动窗口不得加入 API key、secret、password 或 token，不得改成实盘模式。

当前 RDS 已由用户执行 `ruoyi-vue-pro.sql` 和 `quartz.sql`，并已应用二十四张 quant 表及三条量化菜单记录，覆盖行情下载审计、优化研究评审、模拟盘准入、会话、启动审批、就绪快照、执行任务、命令预览、一次性启动令牌、周期观测、告警及人工处置审计。2026-09-27 已验证管理员登录、动态菜单加载、历史回测页面以及成功任务列表；认证和租户校验保持启用。

## 数据与迁移

```powershell
python script/quant/migrate.py
python script/quant/prepare_dataset.py --id okx-btc-202608 --start 2026-08-01 --end 2026-09-01
```

迁移脚本执行量化建表、幂等菜单插入、`003_parameter_set_scope.sql` 的参数集归属迁移及 `004_dataset_download.sql` 的下载审计建表，不会导入基础库或删除数据。使用本机已有 PyYAML/PyMySQL；凭据从原本 local 的 master 读取，不通过命令参数或日志输出。

数据脚本仅需 Python 标准库，下载公开且已确认的小时 K 线；包含 240 根预热，校验时间连续性、OHLCV 数值及价格关系。公开接口的连接重置、不完整响应和超时会有限重试。存在的数据集 ID 拒绝覆盖，重新下载需新 ID。本轮已存在 `okx-btc-202608` 和覆盖一年目标区间、共 9000 根 K 线的 `okx-btc-202509-202609-v4`，不要重复执行同一 ID。

每个数据集目录含 BTC_USDT-1h.json（Freqtrade JSON OHLCV）和 manifest.json：exchange、pair、timeframe、tradingMode、sha256、source。Java 提交和执行时都检查数据摘要及完整覆盖，拒绝路径穿越、缺口、重复时间、不完整预热、未来区间。每个任务复制独立数据快照并再次验证摘要。

`.runtime/quant` 被忽略，不提交行情、结果、容器生成数据或日志。不读取、不复制、不操作 D:\quant-poc 的运行目录或服务。

## API 与任务语义

路径有芋道统一 `/admin-api` 前缀：

- POST `/quant/backtest/create`：提交或幂等返回任务 ID。
- GET `/quant/backtest/list`：当前用户最近 100 条，不含大结果正文。
- GET `/quant/backtest/get?id=...`：参数、策略/行情摘要、状态与结果。
- GET `/quant/backtest/capabilities`：当前开关与固定能力。
- GET `/quant/backtest/strategy-versions`：当前租户、当前用户可用的不可变策略版本。
- GET `/quant/backtest/parameter-sets`、POST `/quant/backtest/parameter-set/create`：列出或按内容摘要复用当前用户参数集。
- POST `/quant/backtest/compare`：对比当前用户 2 至 5 个已完成实验的成交、收益和回撤。
- GET `/quant/backtest/datasets`：扫描本地受控数据目录，返回格式、摘要、连续性、覆盖时间和 K 线数量质量报告。
- POST/GET `/quant/backtest/paper-session/*`：创建、列出和查看只读模拟盘会话，并对启动准备执行 APPROVED/REJECTED 人工审批；不会启动引擎。
- POST/GET `/quant/backtest/paper-session/readiness/*`：为已批准且证据仍有效的会话生成、查询凭据无关配置及环境校验快照；执行能力固定关闭。
- POST/GET `/quant/backtest/paper-execution/*`：创建、列出和查看 WAITING_ENABLE 执行计划，或按任务精确停止并审计；当前不启动容器。
- POST/GET `/quant/backtest/paper-execution/preview/*`：从执行计划生成或读取带摘要的安全命令预览；隔离目录只含 dry-run 配置与已固化策略源码，命令按参数数组保存且不会执行。
- POST `/quant/backtest/paper-execution/start-token/issue`：提交固定确认语、意见和预览摘要后签发 5 分钟一次性令牌；明文仅在本次响应显示，重签会吊销旧令牌。
- GET `/quant/backtest/paper-execution/start-token/latest`：查询最近令牌的摘要、状态和到期时间，不返回令牌明文。令牌只能由受总开关保护的执行器原子消费。
- POST `/quant/backtest/paper-execution/start`：仅在单体总开关开启时接受预览摘要和一次性令牌；启动事务复核配置、策略文件及命令清单，原子消费令牌后流转 STARTING/RUNNING。当前配置固定关闭，因此部署环境会拒绝该请求。
- GET `/quant/backtest/paper-execution/observation`：按当前租户和用户只读返回执行状态、启动前结构检查、运行健康、模拟资产摘要及专属 `runtime.log` 尾部；日志最多 200 行、64 KiB，不能指定任意路径。检查覆盖预览自身摘要、隔离目录、配置和策略摘要、`dry_run=true`、固定镜像及受限命令。资产数据直接以只读模式查询任务隔离 SQLite，不启动 API Server、不发布端口。
- GET `/quant/backtest/paper-execution/observation-snapshots`、`/alerts`：查询当前用户最近 100 条分钟级快照及该任务告警。POST `/paper-execution/alert/action` 支持人工确认或解决，GET `/alert-actions` 返回不可变处置记录；所有操作按租户和用户隔离。终态快照超过 30 天后由单体定期清理，活动执行不清理。终态执行完成后，同一有效会话可顺序创建新执行；准入证据变化时保留旧会话并按新证据重新留痕，禁止并行创建多个活动执行。
- GET `/quant/backtest/paper-execution/orders`、`/reconciliations`：按执行任务查询独立模拟订单账本与最近 100 次对账结果。

请求示例（不含认证信息）：

```json
{"requestKey":"research-202608-001","strategyVersionId":"<UUID>","parameterSetId":"<UUID>","datasetId":"okx-btc-202608","startDate":"2026-08-01","endDate":"2026-09-01","startingBalance":1000,"stakeAmount":100,"fee":0.001}
```

UTC 开始日包含、结束日不包含，最长 366 天。同一租户/用户/requestKey 的相同请求返回原 ID，不重跑；不同参数使用同一键会拒绝。失败后使用新键，不覆盖旧实验。每用户提交前检查最多 10 个待处理任务；该检查不作为严格的全局并发配额。

数据库独立记录策略、策略版本（源码/摘要）、参数集、任务、结果。内置策略按源码摘要注册并复用不可变版本；参数集按租户、用户和内容摘要复用。页面必须显式选择策略、参数集和质量校验通过的数据集，服务端仍在执行前重新校验文件摘要、OHLCV、小时连续性、240 根预热和目标区间覆盖。当前不开放任意 Python 源码编辑。结果和 SUCCEEDED 状态在同一事务提交。

状态：QUEUED → RUNNING → SUCCEEDED/FAILED。单线程 worker 从 DB 领取，CAS 防止重复领取；应用重启时先停止已知专属容器，将中断任务标失败，不自动重试。当前只支持单个应用实例及固定共享工作目录，文件锁防止同目录并行启动；不支持多个主机/多个工作目录同时消费同一数据库。取消、分布式租约、自动重试和归档清理不在首版范围。

每任务独立 `quant-platform-bt-<UUID>` 一次性容器；仅开放 backtesting CLI，无 trade/webserver 操作，无交易所凭据、端口发布、Docker socket 挂载或 PoC 挂载。dry_run=true、spot 固定，API/Telegram 关闭；限制 CPU/内存、只读容器根文件系统，只写任务挂载目录。超时/关闭只清理精确任务名，绝不 remove-orphans。

模拟盘运行同样使用唯一 `quant-platform-paper-<UUID>` 容器名。监控器记录 STARTING/RUNNING/FAILED/STOPPED 和追加审计；进程意外退出标记失败且不自动重试，应用重启会请求停止遗留容器并标记失败。配置把 dry-run SQLite 固定到隔离可写目录，显式提供新版 Freqtrade 所需的定价、静态交易对及 RUNNING 初始状态；API Server 和 Telegram 对象省略并保持默认关闭。交易所 WebSocket 固定关闭，公开行情沿用 `QUANT_EXCHANGE_PROXY` 的 HTTP 代理。

告警状态为 OPEN → ACKNOWLEDGED → RESOLVED。自动观测会在异常恢复时解决告警；人工确认和解决必须提交说明并写入处置审计。持续异常会保留 ACKNOWLEDGED 状态，已解决后再次出现会重新打开。监控当前对心跳、网络、致命错误、进程退出、订单对账以及仓位/总暴露/已实现亏损越界告警；风险越界会先保留告警证据，再按执行 ID 绑定的唯一容器名精确停止并追加执行审计。

每次分钟观测以只读方式扫描该执行的 Freqtrade SQLite `orders` 表。平台幂等键为 SHA-256(`执行 ID + 换行 + 来源订单 ID`)，订单状态收敛为 OPEN、FILLED、CANCELED、UNKNOWN，状态变化追加不可变审计。SQLite 读取设置 2 秒忙等待；数据库缺失、锁等待超时或读取异常会记录 FAILED 对账并产生高等级告警。只有完整读取成功后，来源中消失的既有订单才转为 UNKNOWN；平台不会自行推断为成交或撤销。当前对账事实源仍是 Freqtrade dry-run SQLite，不连接交易所私有 API。

订单对账故障演练通过 `python script/quant/verify_paper_order_reconciliation.py` 显式执行。脚本使用项目真实 MySQL、隔离的 Freqtrade 兼容 SQLite 和固定摘要镜像的无网络一次性容器，验证 OPEN → FILLED、CANCELED、重复观测幂等、SQLite 排他锁超时、恢复、来源消失转 UNKNOWN 及风险精确停机；演练执行、订单、对账和审计记录保留，工作文件位于忽略目录。该演练不会启动 Freqtrade 交易命令，不连接私有交易接口，也不构成真实交易所订单证据。

2026-09-28 已完成首个真实启停验收：批次 `4b5633e9-4540-4b05-8234-8b853e8a098a`、会话 `74c8af46-5b1a-4078-8a17-ce4eebba72c6`、执行 `eb013430-84c8-47dc-9f3d-260b673ae4dd`。日志确认 Freqtrade 2026.8、`dry_run`、OKX、QuantEmaBaseline、BTC/USDT、内部 RUNNING 和心跳；随后平台接口精确停止为 STOPPED，容器清除，默认开关恢复 false。此前因工作目录和新版配置条件校验失败的尝试均保留为失败审计，未改写结果。该次发现 HTTP 代理不承载 OKX WebSocket，后续已通过固定禁用 WebSocket 消除该错误。

同日已完成禁用 WebSocket 后的稳定性与遥测验收：批次 `1db57c0a-2513-4188-ae33-c47fb3e9cb1c`、执行 `8de8ab4e-1dea-4945-815f-5e899c4493f5`。RUNNING 心跳跨度 65 秒，网络/致命错误均为 0；SQLite 只读遥测返回 1000 USDT 模拟余额，持仓、订单和成交均为 0。停止及恢复默认启动后复核 STOPPED、总开关 false、检查通过且无专属容器残留。60 秒通过只证明短时技术稳定性，不代表长期运行或策略收益。

2026-09-29 完成 4 小时无人值守 dry-run 验收：批次 `1db57c0a-2513-4188-ae33-c47fb3e9cb1c`、会话 `bca35512-df65-4eb9-b0ad-4748f48c1697`、执行 `1ce84e47-292e-4d8f-b020-de89047b20e2`。共固化 246 条分钟级快照，观测跨度约 4 小时 6 分 23 秒，心跳跨度约 4 小时 6 分 49 秒；行情时间持续更新，网络/致命错误和未处理告警均为 0。余额始终为 1000 USDT，持仓、订单、成交和已实现收益均为 0。平台接口精确停止后任务为 STOPPED、专属容器消失；随后以默认参数恢复单体并复核 `executionEnabled=false`。该结果证明当前固定配置可连续稳定运行 4 小时，不构成策略收益验证。

2026-09-29 完成真实环境订单对账与故障演练：执行 `00af85b4-a5ec-4500-b212-7191c123d92a` 在项目 MySQL 留存 5 次 PASSED、1 次 SQLite 锁超时 FAILED 及恢复记录；两条演练订单覆盖 OPEN → FILLED、CANCELED → UNKNOWN，共 4 条订单审计。风险演练通过真实 Docker 停止路径精确停止无网络一次性容器，任务为 STOPPED，执行审计包含 REHEARSAL_CREATED 和 RISK_STOPPED，容器无残留。该证据验证平台控制与对账语义，不声称产生了真实 Freqtrade 或交易所订单。

页面“状态与日志”是人工验收的只读证据入口。总开关关闭是准备阶段的通过项；运行窗口开启后该项会显示当前状态，其他文件、摘要和命令约束仍须保持通过。日志不存在表示容器尚未启动，不视为伪造的运行证据。

页面“实盘前只读准入评估”可生成不可变报告。报告自动选择当前用户最近的成功回测、满足 240 条且跨度至少 4 小时的无错误 dry-run、含失败与恢复的订单对账演练、风险停机审计，并确认不存在未解决告警。相同证据生成相同摘要并复用原报告。证据确认语为 `CONFIRM_EVIDENCE_REVIEWED`，密钥边界确认语为 `CONFIRM_KEY_BOUNDARY_ACCEPTED`；两类确认各只能追加一次。报告导出为 JSON，始终包含 `liveTradingAllowed=false` 和 `activationAllowed=false`。

2026-09-29 已用真实项目数据库生成首份只读准入报告 `fb027b52-8ec3-4f37-9adc-26e4d610db27`，SHA-256 为 `c4f359d7e85424d27c1a6e0f6683807449be1b6a6a69eec47decab66c0afef99`。历史回测、4 小时 dry-run、订单对账演练、风险停机和零未解决告警五项均通过。用户已完成证据复核与密钥边界复核，状态为 DOUBLE_CONFIRMED；两条追加审计均绑定原摘要，报告正文及摘要复核未变化，`liveTradingAllowed=false`、`activationAllowed=false`。

基于该报告创建离线策略 `46267ec1-9963-44a4-a330-d25fc0467938`。真实项目接口验收覆盖人工启用、5 USDT 合规请求放行、相同客户端订单号幂等复用、11 USDT 请求按 `MAX_ORDER_NOTIONAL` 拒绝、紧急停机以及停机后按 `GATE_HALTED` 拒绝；所有记录均为 `executed=false`，最终状态 HALTED。OKX 凭据保持未配置，私有接口未连接，真实订单入口未开放。

2026-09-29 已为专用 OKX 子账户配置当前 Windows 用户绑定的 DPAPI 文件并完成私有余额只读验收：readiness 显示 `credentialConfigured=true`、`privateReadAvailable=true`、`realOrderAvailable=false`；OKX 返回 `code=0` 和 1 组账户数据，平台记录 `ordersSent=0` 审计。验收期间策略保持 HALTED、`liveExecutionEnabled=false`、真实下单入口关闭。密钥禁止提现并限制现货交易；因当前使用条件限制暂未绑定 IP，按 SECURITY 的临时降级约束使用。适配器已修正为 `pwsh` 解密、毫秒级签名时间戳、固定 User-Agent，并复用 `QUANT_EXCHANGE_PROXY`。

2026-09-29 完成首个真实订单闭环：策略 `46267ec1-9963-44a4-a330-d25fc0467938` 临时启用后，以一次性令牌提交 BTC/USDT 现货 BUY 限价单，平台订单 `2768d958-fca6-4926-8940-1e5d6230bbcd`，名义金额约 4.654811 USDT。交易所接受后平台查询并撤单，最终 CANCELED、成交量 0；令牌重放被拒绝。随后策略恢复 HALTED，单体以默认 `liveExecutionEnabled=false` 重启并复核真实订单入口关闭。

2026-09-29 完成固定策略自动实盘短时真实市场验收：会话 `ebf608ab-af15-4741-82e4-ef1f31d9319a` 在双开关临时开启后运行约 35 秒，固化 2 次账户/订单对账，均为 PASSED 且无告警；最新已收盘 1h K 线无 EMA20/60 交叉，唯一信号为 NONE/NO_ACTION，因此未产生订单。会话通过平台接口停止，策略恢复 HALTED，平台活动订单为 0；单体随后以默认 `liveExecutionEnabled=false`、`liveAutomationEnabled=false` 重启并复核真实入口关闭。

管理后台现可直接启动和停止固定策略自动实盘会话，并查看最近策略信号、账户/订单对账及自动停机告警。页面按钮同时受策略状态、`liveExecutionEnabled` 和 `liveAutomationEnabled` 约束；仓库默认双开关仍为关闭。独立“运行面板”汇总策略版本与回测统计、参数集、模拟盘执行、当前自动实盘会话、风险门禁和真实订单账本，默认每 15 秒刷新，并提供带原因的当前会话停止操作。

2026-09-30，24 小时验收会话 `3bde5145-3e45-4cbc-8519-cd6a042af2bf` 在约 2 小时 21 分时提前安全停止。此前 475 次账户/订单对账全部 PASSED，4 根已收盘 K 线信号幂等；自然 BUY 限价单在 60 秒内未成交并成功撤销，随后自然 SELL 信号因没有可用策略持仓而触发“可用 BTC 不足以卖出”。系统按设计产生 AUTOMATION_FAILURE、将会话置为 RISK_STOPPED、策略置为 HALTED，且无活动挂单残留。临时双开关进程已停止并恢复默认关闭单体。该证据暴露出策略执行缺少会话持仓感知：修复前不继续长时实盘验收。

持仓感知 SELL 已修复：会话仓位只汇总该会话关联订单的 BUY/SELL 实际成交量，卖出数量取会话净成交量、账户可用 BTC 和固定单笔金额换算数量的最小值，并按 8 位精度向下取整。没有已成交且可用仓位时，信号记录为 `NO_POSITION` 并继续会话，不创建决策、令牌或订单。23 项量化模块测试通过（2 项真实环境测试按预期跳过），单体聚合构建通过。

首次重跑会话 `d5b0e6bb-2a55-4812-b95c-60daa8435499` 暴露出限价仍取上一根已收盘 1h K 线收盘价：自然 BUY 因价格超过 OKX 当时允许上限而被拒绝，系统立即安全停机，最新对账 PASSED 且无活动挂单。自动订单现保留已收盘 K 线价格作为信号证据，实际 BUY/SELL 限价分别使用 OKX 实时最优卖价/买价；无仓位 SELL 会在请求实时价格前直接记录 `NO_POSITION`。23 项量化测试通过（2 项真实环境测试按预期跳过），单体聚合构建通过。

2026-09-30 23:04（Asia/Shanghai）启动修复后的 24 小时验收会话 `9e1e9832-03f5-4acb-85e5-e6c619de34b9`。启动后应用健康 UP，会话 RUNNING，首轮 2 次对账均 PASSED 且快照持续增长；无活动挂单、无 OPEN 告警，首根已收盘 K 线为唯一 `NONE/NO_ACTION` 信号。

该会话因电脑停电关机在约 3 小时 53 分时中断，共保留 800 次 PASSED 对账、4 根唯一已收盘 K 线信号，且无活动挂单、OPEN 告警或亏损越界；恢复供电后平台按重启保护将会话置为 FAILED、策略置为 HALTED，并以默认双开关关闭状态恢复单体。2026-10-01 10:27（Asia/Shanghai）改用带独立 PID 和落盘日志的隐藏后台 Maven 进程，启动新验收会话 `c70673c5-c58a-4dc2-8646-1d3a5fc36683`；应用健康 UP，会话 RUNNING，首轮 2 次对账均 PASSED 且快照增长，无活动挂单和 OPEN 告警。

会话 `c70673c5-c58a-4dc2-8646-1d3a5fc36683` 连续运行 22 小时 00 分 23 秒后，因一次 OKX 私有 REST 传输请求失败按既定规则安全停机。此前 4639 次对账全部 PASSED，23 根已收盘 K 线各只有一个信号，无挂单、亏损或账目差异；自然 BUY 已成交 0.00005931 BTC，该小额仓位保留在专用子账户。策略已 HALTED，双实盘开关关闭并恢复默认单体。2026-10-02 10:13（Asia/Shanghai）启动新会话 `bf3d1fc1-b91e-48bf-8d1d-4300b31da309` 继续 24 小时验收，首轮 4 次对账均 PASSED、无活动挂单和 OPEN 告警。

为降低瞬时网络波动造成的无谓停机，OKX 私有账户、挂单和订单查询增加最多 3 次、间隔 500 毫秒的有限重试，并在最终错误中保留底层传输异常类型。下单和撤单仍严格单次发送，避免不明确结果下重复提交。当前已启动的验收进程使用启动时版本；该增强在下次单体重启后生效。

不能单看进程退出码：2026.8 配置错误可能未产生非零退出码。必须有唯一结果归档、有效策略结果、完整实际区间与一致成交数。结果归档不直接解压到磁盘，限制大小。2026.8 结果 JSON 没有引擎版本字段，版本取本次启动日志 banner，同时保存固定镜像摘要。零成交允许作为合法回测结果，但 UI 明确提示不代表盈利能力验证。

运行面板支持历史会话选择、近期权益/敞口曲线以及信号、对账和告警明细。曲线和表格只覆盖接口返回的最近 100 条记录，不能用其时间跨度代替完整 24 小时验收。真实订单默认仅展示所选会话信号关联的订单，关闭筛选后展示所属风险策略最近订单；账户权益变化包含原有资产的价格变化，不能直接解释为策略收益。刷新失败会保留上次数据并标示未更新。

## 可配置策略与回测联动（2026-10-02）

历史回测页展开“配置 EMA 策略并保存新版本”，设置 EMA 快慢周期及止损、止盈百分比，保存后自动选中新版本。交易资金与费率仍保存为独立参数集，选择数据集和 UTC 区间后提交回测。任务、详情、运行面板、实验对比及可复现报告均显示版本配置。对比时需自行核对区间、资金与费率是否一致。

进入模拟盘沿用已有研究流程：为所选版本提交训练/验证批次 → 研究评审 → READY 准入确认 → 人工选择资金参数集 → 创建待审批会话 → 审批与执行准备。模拟盘预览保存的 Python 与该版本源码逐字一致。保存其他版本不会更改已提交任务、已审批会话或当前实盘会话。当前实盘自动策略保持固定 EMA20/60，可配置版本仅用于回测和模拟盘。

真实 Freqtrade 2026.8 隔离回测：EMA12/48、止损 1.5%、止盈 3%，使用 `okx-btc-202608`（2026-08-01 至 2026-09-01 UTC）产生 8 笔模拟成交，净收益 2.66760965 USDT、收益率约 0.266761%、最大回撤约 0.189013%。该证据验证参数进入真实引擎，不作为选用参数或盈利结论。测试未写入项目 MySQL；版本、回测、报告与模拟盘参数传递另由 H2 集成测试覆盖。

验收进程未重启，新版本接口在验收结束后的正常部署时生效。本轮无新增环境配置或数据库迁移；`yudao-server` 聚合构建验证新增 API 与服务已接入单体。

## 策略实验工作台（2026-10-02）

菜单“量化交易 → 策略实验工作台”（`/quant/experiments`）：选择 2–5 个不可变 EMA 版本、同一资金参数集与行情，统一设置训练开始、训练/验证切分、验证结束（UTC，结束日不含，两段各至少 7 天）。研究预设为 EMA12/48、20/60、30/90，并分别绑定止损/ROI；这些是实验起点，不是盈利推荐。

接口 `/quant/backtest/strategy-experiment/create|list|get`：requestKey 幂等，同 key 不同条件拒绝，租户和用户隔离；整组事务创建，每个方案生成训练/验证两个任务，仍受最多 10 个待处理任务限制。实验列表为最近 50 组；新表 `quant_strategy_experiment` 与 `quant_strategy_experiment_member`，迁移 023/024 已应用（37 张 quant 表、5 个菜单记录）。

展示任务状态、训练/验证收益、回撤、成交及收益差；全部结束后按验证收益排序，失败方案保留状态但无排名。15 秒刷新。选择方案并填写意见，可接受当前证据、确认 READY、创建待审批模拟会话；审批与启动沿用已有模拟盘页面，绑定相同策略版本和资金参数。本工作台不启动实盘或读取密钥。

验证：32 项后端测试、单体聚合 package、前端全量 ts:check/build:local、git diff --check 通过。覆盖实验幂等/隔离、事务回滚、容量限制、失败保留、完整后排名和版本到模拟会话传递。未新增真实引擎回测或浏览器验收；既有可配置源码真实引擎证据见上一节。

本轮在隔离 worktree 构建，未操作主目录运行进程。新接口需要运行验收流程安排的正常部署，仅刷新前端不足以部署后端。自动实盘目前仍只接受固定基线源码版本，研究配置版本进入受控实盘尚未扩展。

## 自动实盘会话收益与成本（2026-10-02）

运行面板新增“策略收益与成本”，只读接口 `GET /quant/backtest/live-automation/performance?sessionId=...`。接口从所属租户/用户会话的全部 BTC-USDT 订单计算，不受原有最近 100 条信号/订单明细限制；按订单 ID 或客户端 ID 归属并去重，不包含其他会话、人工单和账户原有资产，不发交易所请求。

收益贡献为卖出累计成交额减买入累计成交额，加 USDT 费用/返佣，再加本会话剩余 BTC 持仓估值。BTC 费用/返佣直接调整净持仓；费用折算展示采用该订单成交均价。估值用该会话最近已收盘 K 线，页面显示时间；不是实时清算价值，未计未来平仓费，不给资金收益率或逐笔已实现收益。活动订单后续成交会改变结果，账户权益变化继续单独展示。

迁移 025 为订单增加累计有符号 fee/rebate 金额及币种（表数仍为 37）。常规私有订单查询在保存累计成交量/均价时一起替换累计费用，不重复累加；金额语义依据 [OKX 官方订单文档](https://www.okx.com/docs-v5/#order-book-trading-trade-get-order-details)：扣费为负，返佣为正，手续费与返佣分别保存。BTC、USDT 以外费用币种不推测汇率。

历史费用字段默认 NULL，不做零手续费补齐，也不在本轮自动重查历史终态订单。已成交订单缺少费用/返佣、成交均价异常、净持仓为负或无估值价格时，净收益贡献返回 null，页面显式提示不完整。未成交撤单的净贡献可为 0。

验证：36 项后端测试和单体 package 通过；覆盖基币扣费、报价币扣费、返佣、部分成交撤单、缺失/未知币种、不完整估值、超卖、空成交，以及会话隔离、非会话单排除和累计费用替换幂等。前端 ts:check/build:local、git diff --check 通过。未新增浏览器验收或真实交易所费用核验。开发构建在隔离 worktree，未操作当前运行进程；新查询采集和接口须正常部署后生效。

## 候选模拟复盘与已部署入口（2026-10-02）

实验工作台“候选方案模拟运行与复盘”读取 `GET /quant/backtest/strategy-experiment/paper-review?id=...`。按当前用户实验的版本、批次和同一资金参数集绑定最新模拟会话/执行，返回最新持久观测、引擎报告的已平仓收益/初始资金比例、持仓/挂单、对账和未解决告警，并列明保留快照数及起止；统计不受最近 100 条详情限制。无执行、无观测或最新数据库不可读时，收益为 null；未解决告警同时包含 OPEN 和 ACKNOWLEDGED。

该结果不读取私有 API、运行 SQLite 或启动容器；未包含持仓浮盈亏和未来平仓费用。不同时长/市场区间不会自动排名，零成交不视为有效策略证据。可导出当前 JSON，导出不构成准入决定；原有模拟审批、令牌及执行流程不变。

默认后台服务已部署最新源码：模块启用、模拟执行和两个实盘开关均 false，健康 UP。前端入口 `http://localhost:5173`，后端 48080。启动元数据和日志在 `.runtime/quant`；80 端口为其他既有服务，未改其配置。本轮无新环境配置或迁移。

真实实验 `0e3063d5-7720-4924-be67-a2b6b6b44d4f`，共 3 个版本、6 次回测全部成功；共同训练 2026-08-01～08-16、验证 08-16～09-01 UTC。EMA12/48、20/60、30/90 验证收益分别约 0.454563%、0.145791%、0.441161%，验证成交分别 5/3/3 笔。因为样本不足，未批准或启动候选模拟会话。接口验证包括重复请求复用、三行模拟结果以及无遥测收益为空；本地摘要 `.runtime/quant/candidate-comparison-20261002.json`。36 项后端测试、单体 clean install、前端类型检查/构建及 git diff --check 通过；尚无新增浏览器交互证据。

## 九个月候选样本扩展（2026-10-02）

公开数据集 `okx-btc-202601-202609-v2` 覆盖 2026-01-01～10-01 UTC，6792 根（含 240 根预热），行情 SHA-256 `00bb4745ff168afce58405468dc37eb4d50bad41b8d166932e568436df820ca7`。主机下载走 `QUANT_DATASET_HTTP_PROXY=http://127.0.0.1:3066`，与容器 `QUANT_EXCHANGE_PROXY` 独立；此前直连 DNS 失败的下载记录保留。代理默认空，禁止在 URL 中填写凭据，单体配置和 Python 下载均已接通。

三个已有冻结版本共享训练 01-01～05-01、验证 05-01～09-01 和独立 9 月样本。原单笔 120 USDT 实验保留，因超过模拟上限，再以初始 1000、单笔 100 USDT、单边费率 0.001 完整重跑，总计 18 次真实 Freqtrade 回测全部成功。风险兼容实验 `fe8abdc7-87bb-473c-b97b-66d7b24ab824` 结果：

| EMA | 长验证净收益 USDT | 验证成交 | 独立 9 月净收益 USDT |
| --- | ---: | ---: | ---: |
| 12/48 | -8.925635 | 35 | -2.014299 |
| 20/60 | -0.677054 | 23 | 4.085723 |
| 30/90 | 0.925361 | 20 | 0.378134 |

EMA30/90 验证最大回撤约 1.110089%，独立样本仅 4 笔成交，收益优势很弱。9 月未参与长验证排名，但已查看三个方案的结果，不能继续将其视为未来完全未见样本。历史回测不是未来收益证明。

候选会话 `35a93c04-184f-4c31-aa3a-47c169a18303` 完成模拟评审/准备，执行 `8b68444f-2cc5-4e68-ae47-3d953e1449cf` 为 WAITING_ENABLE；预览 dry_run=true、无凭据、无开放端口。令牌签发必须在模拟总开关关闭时进行。本轮组合启动操作被自动审批拒绝（blocked by policy），尚无实际候选模拟运行证据。恢复默认服务后健康 UP，executionEnabled/liveExecutionEnabled/liveAutomationEnabled 均 false，容器不存在。无令牌文件或真实交易。

36 项后端测试和单体聚合 install 通过；实际下载/回测完成端到端验证，本轮未改前端或数据库结构。非秘密结果摘要保留 `.runtime/quant/candidate-long-sample-20261002.json`，不提交。

## 候选短时模拟闭环（2026-10-02）

用户继续授权后，按关闭开关签发令牌、临时开启模拟进程、消费令牌的原流程完成启动。首次执行 `8b68444f-2cc5-4e68-ae47-3d953e1449cf` 出现启动竞争：分钟监控早于 Freqtrade 的 SQLite 初始化，对账报数据库不存在。已停止该执行并保留失败审计，处置告警。

监控现在只在启动状态更新时间后 60 秒内、SQLite 文件不存在、尚未见引擎 RUNNING、网络/致命错误均零时等待初始化，不将等待计为成功对账。已运行或数据库存在后的异常以及超过等待期限的缺失继续按原规则处理；不提高风险限额或改变令牌门禁。

复跑会话 `9fdf8ff3-5102-43ea-8180-8e04c417eecb`、执行 `71ad39f1-4572-448c-8d11-2d128b0f84c9`：冻结 EMA30/90、单笔 100 USDT，dry_run=true、无凭据。实际观察 3 条分钟快照和 2 次 PASSED 对账；初次数据库未就绪快照保留，随后数据库可读、心跳更新，无告警、网络/致命错误或未知订单。挂单、持仓、成交均为 0，估算可用模拟余额 1000 USDT。复盘接口使用真实持久快照，显示 NO_CLOSED_TRADES、已平仓收益 0；未运行方案收益 null，不能把本次短运行当成策略盈利证据。

通过平台接口停止为 STOPPED，确认专属容器移除，默认单体恢复健康 UP、三个执行开关均 false。令牌仅在内存消费，无令牌文件；非秘密证据归档 `.runtime/quant/candidate-long-sample-20261002.json`。27 项相关后端测试、聚合 install 和 diff 检查通过，无数据库迁移或前端改动。

## 配置版本实盘绑定（2026-10-02）

`POST /quant/backtest/live-admission/create?backtestId=<成功任务>` 创建 v2 不可变报告；省略参数仍取当前用户最近成功任务。报告加入 strategyVersionId/sourceHash/configuration 和对应版本、资金参数集的已停止模拟执行证据。至少两条可读快照、最新观测可读、全程无网络/致命错误、最新对账 PASSED 且未知订单零、无未解决告警；原系统运行/风险证据继续使用，不重复启动定时验收。检查是技术准入，不是盈利准入。

门禁始终绑定报告摘要，创建/启用、自动启动、逐次处理和真实令牌签发/执行重新检查源码完整性。只支持服务端固定 EMA 模板，任意 Python、源码/摘要变化、跨用户证据均拒绝。v1 报告只允许旧基线；修改配置需新报告、新确认和新门禁，旧会话不跟随配置编辑变化。

信号计算使用报告绑定周期，最多读取 300 根行情，支持慢周期 120。止损/止盈从本会话实际成交的平均入场成本出发，在已收盘 1h K 线触发 SELL；部分卖出不改变平均成本，清仓后重置，有持仓不再买入。订单继续使用实时买卖盘限价、原金额限制和每根 K 线一次信号。这与 Freqtrade 盘中止损/ROI 撮合存在差异：触发可延迟至下一小时，阈值用毛收益，不含手续费；不能将回测成交结果等同于此执行器，净收益以费用归因接口为准。

真实只读接口核验报告 `b5338a2f-52d5-4629-b179-040d3e558acf`，绑定回测 `8f7298a5-11c8-40a1-998f-1c8f53dd53f1` 和版本 `713d2019-7a78-4d25-ab64-31bd906d70ce`（EMA30/90），技术检查通过，保持 PENDING_CONFIRMATION。默认服务已部署，执行开关全 false、门禁全 HALTED；未启用新候选或发送真实订单。36 项后端测试、单体 install、前端 ts:check/build:local 和 diff 检查通过，无新增数据库迁移或浏览器交互证据。

## 执行与成本追溯（2026-10-02）

`GET /quant/backtest/live-automation/performance?sessionId=...` 继续返回完整会话收益账本，新增报告绑定版本/配置、报告摘要、generatedAt、signalCount、traceLimit=200 和 executionTrace。追溯按明确门禁决定 ID 和会话所属订单 ID/客户端订单 ID 关联，包含触发原因、执行结果、门禁结果、交易所订单、累计成交及费用；缺失关联显式标记，不猜测或混入其他策略的订单。最近 200 条明细与全量收益/订单统计独立。

新信号在原消息字段保存固定原因前缀，历史未保存原因返回 UNRECORDED。无需迁移，不回填或重新解释历史信号。运行面板默认展示执行与收益页签，可展开证据和导出当前 JSON；默认订单账本使用全量收益接口，避免最近 100 条信号过滤导致旧订单被遗漏。刷新失败提示旧数据，禁用当前证据导出。

净收益是会话现金流加剩余持仓估值，不是单笔成交金额，也不等于账户权益变化；停止会话估值沿用其最近收盘 K 线，费用或估值缺失时净收益 null。真实历史会话 `c70673c5-c58a-4dc2-8646-1d3a5fc36683` 核验：1 个成交订单正确关联，实际费用证据缺失，净收益保持 null。另一历史会话 5 条信号、零订单不视为盈利证据。本轮不调用私有交易接口，不新增订单。

33 项后端测试、聚合 install、前端 ts:check/build:local、diff 检查通过；覆盖 203 条信号窗口截断后仍有完整订单/收益统计、门禁/订单关联及历史原因不推断。默认服务部署后健康 UP，三个执行开关 false、门禁 HALTED，无新增数据库配置或浏览器交互验收。

## 验证

```powershell
mvn -pl yudao-server -am -Dtest=QuantBacktestTest,FreqtradeEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
# 显式执行真实引擎 + 本项目 RDS，并保留该次实验记录：
$env:QUANT_EXCHANGE_PROXY='http://host.docker.internal:3066'
python script/quant/verify_smoke.py
# 显式执行真实 MySQL + 隔离 SQLite + 一次性容器的订单对账故障演练：
python script/quant/verify_paper_order_reconciliation.py
```

真实烟测默认不随普通测试运行；脚本将现有数据库凭据仅注入子进程环境。测试记录 requestKey 前缀为 smoke-，保留审计，不自动清理。测试工作区独立于 PoC；尚未验证的登录/权限 UI 链路不能用内部服务测试代替。

日志 `%TEMP%\quant-platform-backtest`；任务日志 `.runtime/quant/jobs/<id>/engine.log`，最近真实测试摘要 `.runtime/quant/smoke-result.json`。这些只保留本机，不提交。

接口行为依据 [Freqtrade 回测官方文档](https://www.freqtrade.io/en/stable/backtesting/) 和 [数据下载官方文档](https://www.freqtrade.io/en/stable/data-download/)，具体导出格式通过本机固定 2026.8 镜像实测确认。

## 通道突破模板（2026-10-02）

历史回测页面“配置策略并保存新版本”可选 EMA 交叉或通道突破。新增 POST /quant/backtest/strategy-version/breakout，参数 entryPeriod、exitPeriod（整数 2～120）、stopLossRatio（0.001～0.2）、takeProfitRatio（0.001～0.5），沿用创建回测权限及租户/用户隔离。配置带 template=CHANNEL_BREAKOUT，固定完整源码校验及版本摘要去重；现有 EMA 版本不变。

已收盘 1h K 线收盘价高于此前 entryPeriod 根最高价时入场，低于此前 exitPeriod 根最低价时退出；rolling 后 shift(1) 排除当前 K 线，不允许空头。沿用 240 根预热及原止损/ROI。满足条件可以连续给出入场信号，持仓数仍受原引擎及风险限制。内部 Freqtrade 类名仍为 QuantEmaBaseline 以兼容既有适配器，平台通过版本及配置区分模板。

实验工作台可混合选择两模板，共用资金、日期及费率。模拟预览复制完整绑定源码，评审和启动规则继续生效。本轮没有实际突破模拟容器运行，自动实盘信号未实现，实盘准入拒绝突破模板；保存版本不改变已有 EMA 实盘会话。

真实实验 9e452246-ee90-48dd-8db6-4a41394e6ca9，数据集 okx-btc-202601-202609-v2，训练 2026-01-01～04-01、验证 04-01～10-01（UTC，结束日不含）；初始 1000 USDT、单笔 100、单边费率 0.001、止损 3%/ROI 6%。四次 Freqtrade 2026.8 回测均成功。

| 方案 | 验证成交 | 验证净收益 USDT | 验证最大回撤 |
|---|---:|---:|---:|
| EMA30/90 | 27 | 13.109769 | 1.096418% |
| 突破20/10 | 65 | 9.997476 | 0.897854% |

收益扣上述手续费，未加入额外滑点；已观察样本不能再次作为未来独立检验。没有自动选中、增资或实际突破运行证据。完整本机摘要 .runtime/quant/breakout-comparison-20261002.json 忽略不提交。

Java 测试覆盖模板边界、数值规范化、源码篡改、隔离、报告及突破实盘准入拒绝；原模拟端到端测试同时运行 EMA/突破。Python 行为测试：在固定 Freqtrade 镜像中用 python 执行 script/quant/test_breakout_strategy.py，挂载项目到 /workspace（只读），使用 --network none；无凭据或交易所访问。覆盖当前 K 线排除、等值/零成交量、预热不足及未来数据变化不影响过去。
