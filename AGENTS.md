# 项目入口

个人长期量化交易平台：数字货币优先（OKX、Binance），A 股第二阶段。主线为 Spring Boot/芋道 + Vue3 + MySQL + Redis，Freqtrade/Python 为主要量化引擎；Northstar 仅研究备选。

- 使用模块化单体 `yudao-server`，入口 `cn.iocoder.yudao.server.YudaoServerApplication`。不自行拆微服务。
- 仅 `origin` 可推送，必须显式 `git push origin <branch>`；永不推送 `yudao-cloud`，无用户安排不得对其 fetch/pull/merge/rebase。当前初始化不 commit/push。
- 开始先汇总分支、远程、跟踪及改动；保留用户文件和暂存状态，不 reset/checkout/restore/clean。`origin/main` 缺失只记录，不自行修复。
- 未经用户明确授权禁止实盘及真实资金订单。Northstar=`SIM_TRADE`，Freqtrade=`dry_run=true`。准入顺序：代码检查 → 历史回测 → 模拟盘 → 风险验证 → 人工确认。AI 只能提出建议。
- 已有模块 `yudao-module-quant`（`-api`、`-server`），包 `cn.iocoder.yudao.module.quant`，表前缀 `quant_`。当前只实现固定策略历史回测，不自行扩展实盘。
- 单体所需依赖和配置必须接入 `yudao-server`；量化配置在其 `application-quant.yaml`，由 `application.yaml` 导入。禁止只配子模块导致单体未生效。
- `D:\quant-poc` 仅定向读取参考，不改文件、不操作其服务，不复制运行数据或凭据。

## 常用验证

PowerShell 当前进程设置 `JAVA_HOME=C:\Users\Jack\.jdks\corretto-25.0.4` 并将其 `bin` 前置 PATH；验证 `java -version`、`mvn -version`。

- 根目录：`mvn -pl yudao-server -am -DskipTests package`
- 前端目录 `yudao-ui/yudao-ui-admin-vue3`：`pnpm install --frozen-lockfile`、`pnpm run build:local`、`pnpm run ts:check`
- Redis：`docker compose -f compose.quant-local.yaml -p quant-platform up -d --wait redis`；`docker exec quant-platform-redis redis-cli ping`

## 按需文档路由

- 接管与当前阻塞：[HANDOFF](docs/quant/HANDOFF.md)
- 历史回测开发、配置与验收：[BACKTEST](docs/quant/BACKTEST.md)
- 范围与优先级：[PRODUCT](docs/quant/PRODUCT.md)、[ROADMAP](docs/quant/ROADMAP.md)
- 模型、接口、引擎接入：[ARCHITECTURE](docs/quant/ARCHITECTURE.md)
- 资金、凭据、数据库、Docker：[SECURITY](docs/quant/SECURITY.md)
- 本地环境与验证：[DEVELOPMENT](docs/quant/DEVELOPMENT.md)
- 上游同步：[UPSTREAM-SYNC](docs/quant/UPSTREAM-SYNC.md)

只读任务相关文档；定向搜索，不输出全仓树或长日志。重要里程碑后更新 HANDOFF 的证据、限制及唯一推荐下一任务。新增文本使用 UTF-8。
