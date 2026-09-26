# 项目交接

更新：2026-09-27（Asia/Shanghai）。路线已确定：Freqtrade + Spring Boot 单体；不再单独做选型或设计评审。本轮已实现并验证首个固定策略历史回测闭环。

## 已落地

- 新增 yudao-module-quant / yudao-module-quant-api / yudao-module-quant-server，根 reactor 与 yudao-server 依赖接入。
- 单体配置 application-quant.yaml 已由 yudao-server/application.yaml 导入；配置加载有独立测试。默认关闭执行，环境开关和路径见 BACKTEST。
- 任务提交、最近 100 条列表、详情、能力查询 API；既有认证权限接入，每次读取限定当前租户及用户。
- 五张独立表：quant_strategy、quant_strategy_version、quant_parameter_set、quant_backtest_task、quant_backtest_result。已在项目 RDS 实际执行增量建表，无 DROP、删库或其他库操作。
- 持久队列、同键幂等、原子领取、事务结果提交、数据覆盖/摘要验证、重启中断处理与专属 Docker 容器回测。
- 前端 /quant/backtest：提交、刷新/轮询、实验摘要、统一结果和模拟成交查看。
- 新增数据准备、迁移与实际烟测脚本，详见 [BACKTEST](BACKTEST.md)。未导入 PoC 文件或操作其服务。

## 验证结果

- Java 25.0.4 / Maven 3.9.9；新增模块后单体 package 成功。
- 11 项量化单元/集成测试（H2、真实服务与仓储、引擎结果解析）通过：并发幂等、租户/用户隔离、摘要篡改拒绝、覆盖/预热拒绝、原子结果提交、重启中断、只回测命令与真实版本证据。
- 1 项单体配置导入测试通过；另有 1 项实际 MySQL + Docker Freqtrade 回测测试通过。
- 管理后台已完成登录、动态菜单、页面和成功任务列表的浏览器验收；前端 `build:local` 与全量 `ts:check` 均通过。
- RDS 仅本项目 quant-platform；Redis 既有基线 healthy/PONG，本轮没有更改 Redis 配置。
- 真实任务与结果记录保留在本项目数据库，失败的开发烟测也保留，没有伪造 SUCCEEDED。早期一个 smoke 样本结果已按精确任务 ID 转为最终统一结果协议，没有改其他数据。

## 真实回测证据

最近规范化结果任务：229860d6-9b7f-49e7-9e81-84cfa53fcfb9，SUCCEEDED，Freqtrade 2026.8。
数据集 okx-btc-202608：公开 OKX BTC/USDT 现货 1h，2026-08-01 至 2026-09-01（UTC，结束日不含），另含 240 根预热，共 984 根。
行情 SHA-256：a7a6e59f4231dcdf464a2d4377b54edadf5e4f0be2adfd37c2c8827676e8bbd8。
策略 SHA-256：9b1bb31b965c2cbb3a180facad4fea8ce54b33a3cdf203659b722b53ad123cd6。

参数：初始 1000 USDT，单笔 100 USDT，单边手续费 0.001，EMA20/60、止损 2%、ROI 4%。实测 4 笔模拟成交，净收益约 1.61717716 USDT，收益率约 0.1617%，最大回撤约 0.2777%。只证明技术链路，不作为投资或盈利结论。

本机产物：.runtime/quant/jobs/<任务 ID>/engine.log 与 results；.runtime/quant/smoke-result.json 是最近摘要。完整构建日志在 %TEMP%/quant-platform-backtest。数据与日志均忽略，不提交。

## 当前限制

1. RDS 已执行芋道基础脚本和 Quartz 脚本；当前包含 system、infra、Quartz 与 quant 表。量化动态菜单迁移已应用，管理员登录后可见“量化研究 / 历史回测”。
2. 2026-09-27 浏览器验收已覆盖登录、动态菜单、历史回测页面、配置启用状态和已有成功任务列表。浏览器自动化未新增任务：Element Plus 日期范围控件的自动化文本输入未提交 Vue 范围模型，因此没有把该尝试计入成功证据。
3. 首版仅单体单实例、固定工作目录；不支持多机或不同目录的多个 worker 同时消费同一数据库。默认执行开关 false，部署步骤见 BACKTEST。
4. 固定策略、BTC/USDT 现货 1h，无策略编辑、参数优化、模拟交易、用户取消、自动重试或实盘。Binance 适配允许同格式数据，但本轮只有 OKX 实测。
5. 根 Spring Boot 4.1.0 / BOM 4.1.1 原样保留。聚合 package 后各 server 模块会生成可执行 JAR；本地启动需先以 `-Dspring-boot.repackage.skip=true clean install` 安装普通模块 JAR，再从 `yudao-server` 执行 `spring-boot:run`。
6. application-local.yaml 与前端 .env.local 为既有跟踪配置；现有测试 RDS useSSL=false、local mock-enable=true 未修改。正式环境前需另行治理。

## Git 和操作边界

仍在 main；本阶段完成后提交并显式推送 `origin main`。origin 是唯一推送目标，yudao-cloud 未访问。
原有用户内容保留。前端量化入口已改为数据库动态菜单，不再依赖 remaining.ts 静态路由。
Northstar=SIM_TRADE、Freqtrade=dry_run=true 边界继续有效；PoC 未改，回测容器无凭据、无端口、无真实交易命令。

## 下一步唯一推荐任务

**实现策略与不可变策略版本管理，并让历史回测任务显式引用策略版本。** 继续保持固定策略、历史回测和 `dry_run=true` 边界，暂不扩展模拟盘或实盘。
