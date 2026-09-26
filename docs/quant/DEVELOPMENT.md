# 本地开发与验证

后续已接入历史回测模块。量化依赖和配置均在 `yudao-server` 生效，详见 [BACKTEST](BACKTEST.md)；最新验证与阻塞以 [HANDOFF](HANDOFF.md) 为准。下文保留初始化基线证据。

根目录 `D:\0000\quant-platform`；前端 `yudao-ui\yudao-ui-admin-vue3`。验证日期 2026-09-27（Asia/Shanghai）。仅修改当前终端环境，不写系统环境变量。

## Java 与后端

```powershell
$env:JAVA_HOME='C:\Users\Jack\.jdks\corretto-25.0.4'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
mvn -pl yudao-server -am -DskipTests package
```

实测 Java/Maven 均使用 Corretto 25.0.4，Maven 3.9.9。上述构建 BUILD SUCCESS（约 68 秒）；跳过测试执行，不能据此声称测试或应用启动通过。产物 `yudao-server/target/yudao-server.jar`。存在 Jansi/Unsafe、注解处理和 MapStruct 等警告，未修改框架代码。

单体入口 `cn.iocoder.yudao.server.YudaoServerApplication`，默认 profile=local、端口 48080，Nacos discovery/config 禁用。当前未启动后端，未验证登录、业务 schema 或端到端 API；启动前核查定时任务、外部服务和本地配置。本地 slave 示例仍指向另一数据库，不要对其执行测试。

## 前端

实测 Node v22.23.2、pnpm 12.6.0。package.json 要求 Node >=20.19.0 / pnpm >=8.6.0，优先于 README 的旧要求。pnpm-lock.yaml 格式 9.0，现有 pnpm-workspace.yaml 的 allowBuilds 策略保持原样。

```powershell
Set-Location 'D:\0000\quant-platform\yudao-ui\yudao-ui-admin-vue3'
pnpm install --frozen-lockfile
pnpm run build:local
pnpm run ts:check
# 需要开发服务时：
pnpm run dev --host 127.0.0.1
```

安装及 build:local 成功（9208 模块，约 47 秒），产物 dist。旧 CSS `*zoom` 语法产生压缩警告，不阻止构建。类型检查结果见 HANDOFF，构建成功不代表类型检查成功。

`build/vite/index.ts` 的自动导入插件仅在开发模式生成 `src/types/auto-imports.d.ts` 和 `auto-components.d.ts`；这些是忽略的生成文件。新工作区先运行开发模式生成声明再查类型，生产 build 不生成声明。本轮用临时 Vite 开发模式 API 生成后停止了本轮进程，未保留开发服务器；依赖 source map 有非阻塞警告。

## 专属 Redis

Docker Engine 26.1.4、Compose v2.27.1-desktop.1。根目录独立配置 `compose.quant-local.yaml` 使用 Redis 7.4 Alpine、AOF、专属命名卷、健康检查及 127.0.0.1 绑定。

```powershell
docker compose -f compose.quant-local.yaml -p quant-platform config --quiet
docker compose -f compose.quant-local.yaml -p quant-platform up -d --wait redis
docker exec quant-platform-redis redis-cli ping
docker inspect quant-platform-redis --format '{{.State.Health.Status}}'
```

本轮返回 healthy / PONG，6379 可用。后端原配置 127.0.0.1:6379，无需修改。如冲突先检查 `Get-NetTCPConnection -State Listen -LocalPort 6379` 和 `docker ps`；选择空闲备用端口设置 `$env:QUANT_REDIS_PORT='16379'`，同时通过当前进程 `SPRING_DATA_REDIS_PORT` 覆盖后端端口。不要停止未知服务、使用 --remove-orphans 或删除持久卷。镜像采用版本线标签，后续更新需验证，不假定标签永远对应相同镜像。

## MySQL 只读验证

RDS `rm-wz92g1um13bcrw3muro.mysql.rds.aliyuncs.com:3306`，仅 `quant-platform`。凭据从本机 application-local.yaml 的 master 读取，不输出、不放命令参数。

```powershell
Test-NetConnection rm-wz92g1um13bcrw3muro.mysql.rds.aliyuncs.com -Port 3306
```

使用可信 SQL 客户端连接指定数据库，执行：

```sql
START TRANSACTION READ ONLY;
SELECT 1, DATABASE(), VERSION();
ROLLBACK;
```

本轮用本机 Python/PyYAML/PyMySQL 内存读取配置并执行上述查询，返回 `(1, quant-platform, 8.0.36)`。配置是多文档 YAML，需 safe_load_all；未安装新 Python 依赖。没有建表、迁移或写入测试数据。数据库失败时先区分 DNS/TCP、白名单、认证和库权限；不要通过操作其他库排障。

## 日志与 Git

本轮完整日志保存在 `%TEMP%\quant-platform-baseline`（backend.log、frontend-install.log、frontend-build.log、frontend-types.log、frontend-types-after.log、frontend-generate.log），不提交。该目录另存用户文件初始哈希和暂存区快照，仅用于本机核对，不应上传。

根 .gitignore 补充依赖、Python 缓存、运行数据、回测结果、运行时数据库及本地覆盖配置；现有 target/dist/log 规则保留。已跟踪 application-local.yaml 和已暂存 .env.local 不受 ignore 保护，提交前需要单独审阅；本轮没有取消跟踪、改索引或清理用户文件。
