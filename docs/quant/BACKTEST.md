# 首个历史回测闭环

固定路线：Freqtrade + Spring Boot 模块化单体。无需重复技术选型评审。

## 已实现

`yudao-module-quant-api` 定义请求与状态；`yudao-module-quant-server` 提供控制器、服务、JDBC 持久化、单线程持久队列及 Freqtrade CLI 适配器。根 Maven reactor 和 `yudao-server/pom.xml` 均已接入。不是独立微服务，无新 Nacos 配置。

前端 `/quant/backtest` 提供提交、列表、详情和成交结果。API 使用芋道既有认证及 `quant:backtest:create` / `quant:backtest:query` 权限；操作只可读取当前租户、当前用户的任务。`sql/quant/002_backtest_menu.sql` 以幂等方式注册“量化研究 / 历史回测”动态菜单和权限按钮；普通角色仍需由管理员分配菜单权限，不提供匿名或免鉴权入口。

范围限定：OKX/Binance、BTC/USDT 现货、1h、固定 QuantEmaBaseline 策略。当前自动数据准备脚本接 OKX；Binance 数据集可按相同清单格式准备，但本轮没有 Binance 实测。策略 EMA20/EMA60、止损 2%、ROI 4%、只做多，240 根预热。只能调整区间、初始资金、单笔投入、单边手续费。不是盈利策略推荐。

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

当前 RDS 已由用户执行 `ruoyi-vue-pro.sql` 和 `quartz.sql`，并已应用五张 quant 表及三条量化菜单记录。2026-09-27 已验证管理员登录、动态菜单加载、历史回测页面以及成功任务列表；认证和租户校验保持启用。当前 mock-enable 是用户原本的 local 配置，本轮未改变；正式环境须关闭。

## 数据与迁移

```powershell
python script/quant/migrate.py
python script/quant/prepare_dataset.py --id okx-btc-202608 --start 2026-08-01 --end 2026-09-01
```

迁移脚本执行量化建表、幂等菜单插入及 `003_parameter_set_scope.sql` 的参数集归属与请求快照增量迁移，不会导入基础库或删除数据。使用本机已有 PyYAML/PyMySQL；凭据从原本 local 的 master 读取，不通过命令参数或日志输出。

数据脚本仅需 Python 标准库，下载公开且已确认的小时 K 线；包含 240 根预热，校验时间连续性、OHLCV 数值及价格关系。存在的数据集 ID 拒绝覆盖，重新下载需新 ID。本轮已存在 `okx-btc-202608`，不要重复执行同一 ID。

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

请求示例（不含认证信息）：

```json
{"requestKey":"research-202608-001","strategyVersionId":"<UUID>","parameterSetId":"<UUID>","datasetId":"okx-btc-202608","startDate":"2026-08-01","endDate":"2026-09-01","startingBalance":1000,"stakeAmount":100,"fee":0.001}
```

UTC 开始日包含、结束日不包含，最长 366 天。同一租户/用户/requestKey 的相同请求返回原 ID，不重跑；不同参数使用同一键会拒绝。失败后使用新键，不覆盖旧实验。每用户提交前检查最多 10 个待处理任务；该检查不作为严格的全局并发配额。

数据库独立记录策略、策略版本（源码/摘要）、参数集、任务、结果。内置策略按源码摘要注册并复用不可变版本；参数集按租户、用户和内容摘要复用。页面必须显式选择策略、参数集和质量校验通过的数据集，服务端仍在执行前重新校验文件摘要、OHLCV、小时连续性、240 根预热和目标区间覆盖。当前不开放任意 Python 源码编辑。结果和 SUCCEEDED 状态在同一事务提交。

状态：QUEUED → RUNNING → SUCCEEDED/FAILED。单线程 worker 从 DB 领取，CAS 防止重复领取；应用重启时先停止已知专属容器，将中断任务标失败，不自动重试。当前只支持单个应用实例及固定共享工作目录，文件锁防止同目录并行启动；不支持多个主机/多个工作目录同时消费同一数据库。取消、分布式租约、自动重试和归档清理不在首版范围。

每任务独立 `quant-platform-bt-<UUID>` 一次性容器；仅开放 backtesting CLI，无 trade/webserver 操作，无交易所凭据、端口发布、Docker socket 挂载或 PoC 挂载。dry_run=true、spot 固定，API/Telegram 关闭；限制 CPU/内存、只读容器根文件系统，只写任务挂载目录。超时/关闭只清理精确任务名，绝不 remove-orphans。

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
