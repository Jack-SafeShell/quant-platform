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
# 基础库与依赖具备后，从根目录启动：
java -jar yudao-server/target/yudao-server.jar
```

支持 QUANT_DOCKER_EXECUTABLE、QUANT_FREQTRADE_IMAGE、QUANT_BACKTEST_TIMEOUT。镜像要求官方仓库 sha256 摘要固定，当前为已实测 Freqtrade 2026.8；超时默认 600 秒，范围 30..3600 秒。运行账户需要本机 Docker 权限。Docker Engine 与该应用必须在同一台主机，工作目录使用绝对路径。

模拟盘执行总开关 `yudao.quant.paper-execution-enabled` 在单体 `application-quant.yaml` 中固定为 false，当前不提供环境变量覆盖。一次性启动令牌有效期 `paper-start-token-ttl-seconds` 也在该文件固定为 300 秒。就绪快照只运行 `docker --version`、检查工作目录并生成 dry-run 配置清单，不启动容器。

首个真实 dry-run 只允许在人工验收窗口内通过单次进程参数临时覆盖，仓库配置始终保持 false。开始前先在页面确认“状态与日志”的启动前检查全部通过，再签发一次性令牌；随后停止当前单体进程，并仅对本次启动追加：

```powershell
java -jar yudao-server/target/yudao-server.jar --yudao.quant.paper-execution-enabled=true
```

验收完成后先在页面精确停止执行计划，再停止该单体进程；按原启动命令重新启动（不携带上述参数），并在页面确认总开关显示“关闭”、任务已进入 STOPPED 或 FAILED。若页面停止失败，使用执行计划显示的唯一 `quant-platform-paper-<UUID>` 容器名执行 `docker stop --time 20 <容器名>`，绝不使用批量删除或 `remove-orphans`。启动窗口不得加入 API key、secret、password 或 token，不得改成实盘模式。

当前 RDS 已由用户执行 `ruoyi-vue-pro.sql` 和 `quartz.sql`，并已应用十八张 quant 表及三条量化菜单记录，覆盖行情下载审计、优化研究评审、模拟盘准入、会话、启动审批、就绪快照、执行任务、命令预览、一次性启动令牌及审计。2026-09-27 已验证管理员登录、动态菜单加载、历史回测页面以及成功任务列表；认证和租户校验保持启用。

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
- GET `/quant/backtest/paper-execution/observation`：按当前租户和用户只读返回执行状态、启动前结构检查及专属 `runtime.log` 尾部；日志最多 200 行、64 KiB，不能指定任意路径。检查覆盖预览自身摘要、隔离目录、配置和策略摘要、`dry_run=true`、固定镜像及受限命令。

请求示例（不含认证信息）：

```json
{"requestKey":"research-202608-001","strategyVersionId":"<UUID>","parameterSetId":"<UUID>","datasetId":"okx-btc-202608","startDate":"2026-08-01","endDate":"2026-09-01","startingBalance":1000,"stakeAmount":100,"fee":0.001}
```

UTC 开始日包含、结束日不包含，最长 366 天。同一租户/用户/requestKey 的相同请求返回原 ID，不重跑；不同参数使用同一键会拒绝。失败后使用新键，不覆盖旧实验。每用户提交前检查最多 10 个待处理任务；该检查不作为严格的全局并发配额。

数据库独立记录策略、策略版本（源码/摘要）、参数集、任务、结果。内置策略按源码摘要注册并复用不可变版本；参数集按租户、用户和内容摘要复用。页面必须显式选择策略、参数集和质量校验通过的数据集，服务端仍在执行前重新校验文件摘要、OHLCV、小时连续性、240 根预热和目标区间覆盖。当前不开放任意 Python 源码编辑。结果和 SUCCEEDED 状态在同一事务提交。

状态：QUEUED → RUNNING → SUCCEEDED/FAILED。单线程 worker 从 DB 领取，CAS 防止重复领取；应用重启时先停止已知专属容器，将中断任务标失败，不自动重试。当前只支持单个应用实例及固定共享工作目录，文件锁防止同目录并行启动；不支持多个主机/多个工作目录同时消费同一数据库。取消、分布式租约、自动重试和归档清理不在首版范围。

每任务独立 `quant-platform-bt-<UUID>` 一次性容器；仅开放 backtesting CLI，无 trade/webserver 操作，无交易所凭据、端口发布、Docker socket 挂载或 PoC 挂载。dry_run=true、spot 固定，API/Telegram 关闭；限制 CPU/内存、只读容器根文件系统，只写任务挂载目录。超时/关闭只清理精确任务名，绝不 remove-orphans。

模拟盘运行同样使用唯一 `quant-platform-paper-<UUID>` 容器名。监控器记录 STARTING/RUNNING/FAILED/STOPPED 和追加审计；进程意外退出标记失败且不自动重试，应用重启会请求停止遗留容器并标记失败。配置把 dry-run SQLite 固定到隔离可写目录，显式提供新版 Freqtrade 所需的定价、静态交易对及 RUNNING 初始状态；API Server 和 Telegram 对象省略并保持默认关闭。

2026-09-28 已完成首个真实启停验收：批次 `4b5633e9-4540-4b05-8234-8b853e8a098a`、会话 `74c8af46-5b1a-4078-8a17-ce4eebba72c6`、执行 `eb013430-84c8-47dc-9f3d-260b673ae4dd`。日志确认 Freqtrade 2026.8、`dry_run`、OKX、QuantEmaBaseline、BTC/USDT、内部 RUNNING 和心跳；随后平台接口精确停止为 STOPPED，容器清除，默认开关恢复 false。此前因工作目录和新版配置条件校验失败的尝试均保留为失败审计，未改写结果。当前 HTTP 代理不承载 OKX WebSocket，日志有连接错误但主循环保持 RUNNING；下一阶段需完成行情稳定性和只读交易遥测验收。

页面“状态与日志”是人工验收的只读证据入口。总开关关闭是准备阶段的通过项；运行窗口开启后该项会显示当前状态，其他文件、摘要和命令约束仍须保持通过。日志不存在表示容器尚未启动，不视为伪造的运行证据。

不能单看进程退出码：2026.8 配置错误可能未产生非零退出码。必须有唯一结果归档、有效策略结果、完整实际区间与一致成交数。结果归档不直接解压到磁盘，限制大小。2026.8 结果 JSON 没有引擎版本字段，版本取本次启动日志 banner，同时保存固定镜像摘要。零成交允许作为合法回测结果，但 UI 明确提示不代表盈利能力验证。

## 验证

```powershell
mvn -pl yudao-server -am -Dtest=QuantBacktestTest,FreqtradeEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
# 显式执行真实引擎 + 本项目 RDS，并保留该次实验记录：
$env:QUANT_EXCHANGE_PROXY='http://host.docker.internal:3066'
python script/quant/verify_smoke.py
```

真实烟测默认不随普通测试运行；脚本将现有数据库凭据仅注入子进程环境。测试记录 requestKey 前缀为 smoke-，保留审计，不自动清理。测试工作区独立于 PoC；尚未验证的登录/权限 UI 链路不能用内部服务测试代替。

日志 `%TEMP%\quant-platform-backtest`；任务日志 `.runtime/quant/jobs/<id>/engine.log`，最近真实测试摘要 `.runtime/quant/smoke-result.json`。这些只保留本机，不提交。

接口行为依据 [Freqtrade 回测官方文档](https://www.freqtrade.io/en/stable/backtesting/) 和 [数据下载官方文档](https://www.freqtrade.io/en/stable/data-download/)，具体导出格式通过本机固定 2026.8 镜像实测确认。
