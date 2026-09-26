# 上游同步规则

`origin` = `https://github.com/Jack-SafeShell/quant-platform.git`，是唯一可推送远程。
`yudao-cloud` = `https://gitee.com/zhijiantianya/yudao-cloud.git`，只用于所有者未来人工安排的官方版本同步，永不推送。

没有明确安排，不自动对上游 fetch、pull、merge 或 rebase。本轮没有网络 Git 同步、commit 或 push。

未来获授权后的流程：

1. 汇总当前分支、HEAD、用户改动、远程及跟踪关系；保护未提交内容，不清理或重写历史。
2. 明确要同步的官方分支/版本，并获取用户授权后才 fetch 指定上游 ref。不得猜测默认分支。
3. 在独立评估分支/隔离工作区比较差异，重点检查 Java/Spring、数据库迁移、权限、前端依赖及本地扩展。
4. 先输出影响评估、冲突方案、验证和回退安排，再实施已批准的合并。保留本项目领域边界，不以覆盖目录方式同步。
5. 构建、必要测试和迁移验证通过后审查 diff；合入目标分支按届时授权执行，不 rebase/强推改写既有共享历史。
6. 需要推送时显式 `git push origin <branch>`；绝不使用省略远程的 push，更不能推送 yudao-cloud。

初始化时本地 `main` 跟踪 `origin/main`，但本地远程跟踪 ref 显示 gone。没有访问远程确认服务器状态；作为待处理事项保留，不创建或强推分支。
