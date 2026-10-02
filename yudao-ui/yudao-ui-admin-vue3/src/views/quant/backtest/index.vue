<template>
  <ContentWrap title="历史回测">
    <el-alert title="EMA 交叉策略 · BTC/USDT 现货 · 1 小时 · 可配置版本" type="info" :closable="false" />
    <p class="text-gray-500">配置保存为不可变策略版本，回测和模拟盘使用所选版本；包含 240 根预热，日期按 UTC，结束日不包含。</p>
    <el-alert v-if="!enabled" title="回测尚未启用。请先完成数据准备，并在 yudao-server 中启用量化回测配置。" type="warning" :closable="false" />
    <StrategyConfigEditor :versions="strategyVersions" :selected-version-id="form.strategyVersionId" @created="selectCreatedStrategy" />
    <el-form :model="form" label-width="110px" class="mt-4" @submit.prevent="submit">
      <el-form-item label="策略版本"><el-select v-model="form.strategyVersionId" class="w-100%" placeholder="请选择不可变策略版本"><el-option v-for="item in strategyVersions" :key="item.id" :label="`${strategyLabel(item.configuration)} · ${item.sourceHash.slice(0, 12)}`" :value="item.id" /></el-select></el-form-item>
      <p class="text-gray-500">所选策略：{{ strategyLabel(strategyVersions.find(v => v.id === form.strategyVersionId)?.configuration) }}</p>
      <el-form-item label="参数集"><el-select v-model="form.parameterSetId" class="w-70%" placeholder="请选择参数集" @change="applyParameterSet"><el-option v-for="item in parameterSets" :key="item.id" :label="parameterLabel(item)" :value="item.id" /></el-select><el-button class="ml-2" @click="saveParameterSet">保存当前参数</el-button></el-form-item>
      <el-form-item label="数据集"><el-select v-model="form.datasetId" class="w-100%" placeholder="请选择质量校验通过的数据集"><el-option v-for="item in datasets.filter(value => value.status === 'VALID')" :key="item.id" :label="`${item.id} · ${item.exchange} · ${item.candles} 根`" :value="item.id" /></el-select></el-form-item>
      <el-form-item label="UTC 日期区间"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日（包含）" end-placeholder="结束日（不含）" /></el-form-item>
      <el-form-item label="初始资金"><el-input-number v-model="form.startingBalance" :min="100" :max="1000000" /><span class="ml-2">USDT</span></el-form-item>
      <el-form-item label="单笔投入"><el-input-number v-model="form.stakeAmount" :min="10" :max="1000000" /><span class="ml-2">USDT</span></el-form-item>
      <el-form-item label="单边费率"><el-input-number v-model="form.fee" :min="0" :max="0.01" :step="0.0001" :precision="4" /><span class="ml-2">0.001 = 0.1%</span></el-form-item>
      <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="submitting" :disabled="!enabled" @click="submit">提交历史回测</el-button></el-form-item>
    </el-form>
  </ContentWrap>
  <ContentWrap title="受控历史行情下载">
    <el-alert title="仅下载 OKX 公开 BTC/USDT 现货 1 小时行情，不使用交易凭据；数据集不可覆盖。" type="info" :closable="false" />
    <el-form :inline="true" class="mt-4"><el-form-item label="数据集编号"><el-input v-model="downloadForm.datasetId" placeholder="例如 okx-btc-202609" /></el-form-item><el-form-item label="UTC 日期"><el-date-picker v-model="downloadDates" type="daterange" value-format="YYYY-MM-DD" /></el-form-item><el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :disabled="!enabled" :loading="downloadSubmitting" @click="submitDownload">提交下载</el-button></el-form-item></el-form>
    <el-table :data="downloadTasks"><el-table-column prop="dataset_id" label="数据集" /><el-table-column label="区间"><template #default="s">{{ s.row.start_date }} ～ {{ s.row.end_date }}</template></el-table-column><el-table-column label="状态"><template #default="s"><el-tag :type="statusType(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column><el-table-column prop="candles" label="K 线数" /><el-table-column prop="error_message" label="结果" min-width="220" /></el-table>
  </ContentWrap>
  <ContentWrap title="行情数据集与质量报告">
    <el-table :data="datasets"><el-table-column prop="id" label="数据集" /><el-table-column prop="exchange" label="交易所" width="90" /><el-table-column label="状态" width="90"><template #default="s"><el-tag :type="s.row.status === 'VALID' ? 'success' : 'danger'">{{ s.row.status === 'VALID' ? '有效' : '无效' }}</el-tag></template></el-table-column><el-table-column prop="candles" label="K 线数" width="90" /><el-table-column label="覆盖区间" min-width="300"><template #default="s">{{ s.row.firstTimestamp ? new Date(s.row.firstTimestamp).toISOString() : '-' }} ～ {{ s.row.lastTimestamp ? new Date(s.row.lastTimestamp).toISOString() : '-' }}</template></el-table-column><el-table-column prop="gaps" label="缺口" width="70" /><el-table-column prop="error" label="问题" min-width="180" /></el-table>
  </ContentWrap>
  <ContentWrap title="我的回测任务（最近 100 条）">
    <el-button :loading="loading" @click="refresh">刷新</el-button>
    <el-button class="ml-2" :disabled="selectedIds.length < 2 || selectedIds.length > 5" @click="compare">对比所选</el-button>
    <el-table :data="tasks" class="mt-3" v-loading="loading" @selection-change="rows => selectedIds = rows.map((row: BacktestTask) => row.id)">
      <el-table-column type="selection" width="45" :selectable="row => row.status === 'SUCCEEDED'" />
      <el-table-column label="策略配置" min-width="240"><template #default="s">{{ strategyLabel(s.row.strategyConfiguration) }}</template></el-table-column>
      <el-table-column prop="datasetId" label="数据集" min-width="160" />
      <el-table-column prop="exchangeName" label="交易所" width="100" />
      <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="提交时间" min-width="180"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString() }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="scope"><el-button link type="primary" @click="showDetail(scope.row.id)">详情</el-button></template></el-table-column>
    </el-table>
  </ContentWrap>
  <ContentWrap title="受控参数优化批次">
    <el-alert title="选择 2 至 5 个参数集；训练区间与验证区间完全分离，各至少 7 天。" type="info" :closable="false" />
    <el-form :inline="true" class="mt-4"><el-form-item label="参数集"><el-select v-model="optimization.parameterSetIds" multiple class="w-300px"><el-option v-for="item in parameterSets" :key="item.id" :label="parameterLabel(item)" :value="item.id" /></el-select></el-form-item><el-form-item label="训练/切分/验证"><el-date-picker v-model="optimizationDates" type="dates" value-format="YYYY-MM-DD" /></el-form-item><el-button type="primary" :disabled="!enabled" @click="submitOptimization">提交批次</el-button></el-form>
  <el-table :data="optimizationBatches"><el-table-column prop="dataset_id" label="数据集" /><el-table-column prop="train_start" label="训练开始" /><el-table-column prop="split_date" label="切分日" /><el-table-column prop="validation_end" label="验证结束" /><el-table-column label="操作"><template #default="s"><el-button link type="primary" @click="showOptimization(s.row.id)">结果</el-button></template></el-table-column></el-table>
  <h3>模拟盘会话审批</h3><el-alert title="会话和审批均为只读准备记录，执行始终关闭。" type="info" :closable="false" /><el-table :data="paperSessions" class="mt-3"><el-table-column prop="parameter_set_id" label="参数集" min-width="260" /><el-table-column prop="status" label="状态" /><el-table-column prop="nextAction" label="下一动作" /><el-table-column label="操作"><template #default="s"><el-button link type="primary" @click="showPaperSession(s.row.id)">审批详情</el-button></template></el-table-column></el-table>
  <h3>模拟盘执行计划</h3><el-input v-model="executionStopComment" maxlength="500" placeholder="停止原因（停止前填写）" /><el-table :data="paperExecutions" class="mt-3"><el-table-column prop="session_id" label="会话" min-width="260" /><el-table-column prop="status" label="状态" /><el-table-column prop="container_name" label="精确容器标识" min-width="280" /><el-table-column label="操作" min-width="240"><template #default="s"><el-button link type="primary" :disabled="s.row.status !== 'WAITING_ENABLE'" @click="previewExecution(s.row.id)">安全命令预览</el-button><el-button link type="primary" @click="showExecutionObservation(s.row.id)">状态与日志</el-button><el-button link type="danger" :disabled="['STOPPED','FAILED'].includes(s.row.status)" @click="stopExecution(s.row.id)">停止</el-button></template></el-table-column></el-table>
  <el-dialog v-model="commandPreviewVisible" title="dry-run 安全命令预览" width="70%" @closed="clearStartToken"><template v-if="commandPreview"><el-alert title="该命令清单尚未执行，不包含交易所凭据。" type="warning" :closable="false" /><p><b>清单摘要：</b>{{ commandPreview.previewHash }}</p><p><b>隔离目录：</b>{{ commandPreview.workDirectory }}</p><el-input :model-value="commandPreview.previewJson" type="textarea" :rows="12" readonly /><h3>启动前二次确认</h3><el-input v-model="startTokenComment" maxlength="500" placeholder="填写确认意见" /><el-input v-model="startTokenConfirmation" class="mt-2" placeholder="输入 CONFIRM_DRY_RUN_START" /><el-button class="mt-2" type="warning" @click="issueStartToken">签发一次性启动令牌（不启动）</el-button><el-alert v-if="issuedStartToken" class="mt-3" title="令牌仅显示一次，请在到期前妥善保存。" type="warning" :closable="false" /><template v-if="issuedStartToken"><p><b>到期时间：</b>{{ new Date(issuedStartToken.expiresAt).toLocaleString() }}</p><el-input :model-value="issuedStartToken.token" readonly /><el-button class="mt-2" type="danger" :disabled="!previewExecutionEnabled" @click="startDryRun">启动 dry-run</el-button><span v-if="!previewExecutionEnabled" class="ml-2 text-gray-500">单体执行总开关关闭</span></template></template></el-dialog>
  <el-dialog v-model="observationVisible" title="模拟盘状态、运行遥测与日志" width="75%"><template v-if="executionObservation"><el-alert :title="executionObservation.runtime.currentHealthy ? 'dry-run 正在健康运行。' : executionObservation.runtime.soakPassed ? '稳定性验收已通过。' : executionObservation.preflightPassed ? '启动前结构检查通过；执行总开关保持关闭。' : '检查未通过，请核对失败项。'" :type="executionObservation.runtime.currentHealthy || executionObservation.runtime.soakPassed || executionObservation.preflightPassed ? 'success' : 'warning'" :closable="false" /><el-descriptions class="mt-3" :column="2" border><el-descriptions-item label="运行状态">{{ executionObservation.status }}</el-descriptions-item><el-descriptions-item label="执行总开关">{{ executionObservation.executionEnabled ? '已开启' : '关闭' }}</el-descriptions-item><el-descriptions-item label="容器标识">{{ executionObservation.containerName }}</el-descriptions-item><el-descriptions-item label="观测时间">{{ new Date(executionObservation.observedAt).toLocaleString() }}</el-descriptions-item></el-descriptions><el-table :data="executionObservation.checks" class="mt-3"><el-table-column prop="id" label="检查项" min-width="220" /><el-table-column label="结果" width="90"><template #default="s"><el-tag :type="s.row.passed ? 'success' : 'danger'">{{ s.row.passed ? '通过' : '失败' }}</el-tag></template></el-table-column><el-table-column prop="evidence" label="证据" min-width="300" /></el-table><h3>运行与模拟资产遥测</h3><el-descriptions :column="3" border><el-descriptions-item label="稳定运行">{{ executionObservation.runtime.soakPassed ? '通过' : '未通过' }}</el-descriptions-item><el-descriptions-item label="运行时长">{{ executionObservation.runtime.soakSeconds }} 秒</el-descriptions-item><el-descriptions-item label="错误计数">网络 {{ executionObservation.runtime.networkErrorCount }} / 致命 {{ executionObservation.runtime.fatalErrorCount }}</el-descriptions-item><el-descriptions-item label="最后心跳">{{ executionObservation.runtime.lastHeartbeatAt ? new Date(executionObservation.runtime.lastHeartbeatAt).toLocaleString() : '-' }}</el-descriptions-item><el-descriptions-item label="最后行情活动">{{ executionObservation.runtime.lastMarketDataAt ? new Date(executionObservation.runtime.lastMarketDataAt).toLocaleString() : '-' }}</el-descriptions-item><el-descriptions-item label="数据库">{{ executionObservation.portfolio.available ? '可读' : '尚未生成' }}</el-descriptions-item><el-descriptions-item label="估算可用余额">{{ executionObservation.portfolio.estimatedAvailableBalance }} USDT</el-descriptions-item><el-descriptions-item label="持仓 / 已结交易">{{ executionObservation.portfolio.openPositions }} / {{ executionObservation.portfolio.closedTrades }}</el-descriptions-item><el-descriptions-item label="挂单 / 全部订单">{{ executionObservation.portfolio.openOrders }} / {{ executionObservation.portfolio.totalOrders }}</el-descriptions-item><el-descriptions-item label="已实现收益">{{ executionObservation.portfolio.realizedProfit }} USDT</el-descriptions-item><el-descriptions-item label="持仓占用">{{ executionObservation.portfolio.investedStake }} USDT</el-descriptions-item><el-descriptions-item label="最近钱包快照">{{ executionObservation.portfolio.latestWalletAt || '-' }}</el-descriptions-item></el-descriptions><h3>周期快照与告警</h3><el-table :data="paperAlerts" max-height="240"><el-table-column prop="alertType" label="类型" min-width="150" /><el-table-column prop="severity" label="级别" width="80" /><el-table-column prop="status" label="状态" width="120" /><el-table-column prop="evidence" label="证据" min-width="240" /><el-table-column label="处置" width="180"><template #default="s"><el-button v-if="s.row.status==='OPEN'" link type="primary" @click="handleAlert(s.row,'ACKNOWLEDGE')">确认</el-button><el-button v-if="s.row.status!=='RESOLVED'" link type="success" @click="handleAlert(s.row,'RESOLVE')">解决</el-button></template></el-table-column></el-table><el-table :data="paperAlertActions" max-height="180" class="mt-2"><el-table-column prop="actionType" label="处置动作" width="110" /><el-table-column prop="comment" label="处置说明" /><el-table-column label="处置时间" width="180"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><h3>分钟级快照</h3><el-table :data="observationSnapshots" max-height="240"><el-table-column label="观测时间" width="180"><template #default="s">{{ new Date(s.row.observedAt).toLocaleString() }}</template></el-table-column><el-table-column prop="executionStatus" label="状态" width="110" /><el-table-column prop="heartbeatAgeSeconds" label="心跳龄(秒)" width="110" /><el-table-column prop="estimatedAvailableBalance" label="可用余额" /><el-table-column prop="openPositions" label="持仓" width="80" /></el-table><h3>独立模拟订单账本</h3><el-table :data="paperOrders" max-height="240"><el-table-column prop="sourceOrderId" label="来源订单" min-width="150" /><el-table-column prop="pairSymbol" label="交易对" width="110" /><el-table-column prop="orderSide" label="方向" width="80" /><el-table-column prop="orderStatus" label="状态" width="100" /><el-table-column prop="filled" label="已成交" width="100" /><el-table-column prop="cost" label="金额" width="100" /><el-table-column prop="reconciliationStatus" label="对账" width="100" /></el-table><h3>最近对账</h3><el-table :data="paperOrderReconciliations" max-height="180"><el-table-column label="时间" width="180"><template #default="s">{{ new Date(s.row.reconciledAt).toLocaleString() }}</template></el-table-column><el-table-column prop="reconciliationStatus" label="结果" width="100" /><el-table-column prop="sourceOrderCount" label="来源" width="80" /><el-table-column prop="ledgerOrderCount" label="账本" width="80" /><el-table-column prop="unknownOrderCount" label="未知" width="80" /><el-table-column prop="errorMessage" label="异常" /></el-table><h3>运行日志（只读，最多 200 行 / 64 KiB）</h3><el-alert v-if="!executionObservation.logExists" title="运行日志尚未生成。" type="info" :closable="false" /><el-input v-else :model-value="executionObservation.logTail" type="textarea" :rows="14" readonly /><el-button class="mt-2" @click="refreshExecutionObservation">刷新状态与日志</el-button></template></el-dialog>
  </ContentWrap>
  <ContentWrap title="实盘前只读准入评估">
    <el-alert title="这里只固化证据与密钥边界，不读取私有凭据，不开放实盘下单。双确认也不会改变 liveTradingAllowed=false。" type="warning" :closable="false" />
    <el-select v-model="liveBacktestId" clearable filterable placeholder="选择成功回测；留空使用最近成功任务" class="mt-3" style="width: 520px"><el-option v-for="task in tasks.filter(t=>t.status==='SUCCEEDED')" :key="task.id" :label="task.id" :value="task.id" /></el-select>
    <el-button v-hasPermi="['quant:backtest:create']" class="mt-3" type="primary" @click="generateLiveAdmission">生成不可变准入报告</el-button>
    <el-table :data="liveAdmissions" class="mt-3"><el-table-column prop="reportHash" label="报告 SHA-256" min-width="360" /><el-table-column prop="confirmationState" label="确认状态" width="180" /><el-table-column label="生成时间" width="180"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column><el-table-column label="操作" width="180"><template #default="s"><el-button link type="primary" @click="showLiveAdmission(s.row.id)">查看与确认</el-button><el-button link @click="downloadLiveAdmission(s.row.id)">导出</el-button></template></el-table-column></el-table>
  </ContentWrap>
  <ContentWrap title="受控实盘与固定策略自动执行">
    <el-alert title="真实执行由后端双开关控制；默认关闭。自动会话使用准入报告绑定的 EMA 配置版本，限定 OKX BTC/USDT 现货；止损止盈按已收盘 1h K 线毛收益判断，不等同于回测盘中成交。" type="warning" :closable="false" />
    <el-table :data="liveAdmissions.filter(x=>x.confirmationState==='DOUBLE_CONFIRMED')" class="mt-3"><el-table-column prop="reportHash" label="已双确认报告" min-width="360" /><el-table-column label="操作" width="180"><template #default="s"><el-button type="primary" link @click="generateLiveControl(s.row.id)">创建/查看门禁</el-button></template></el-table-column></el-table>
    <el-table :data="liveControls" class="mt-3"><el-table-column prop="policyVersion" label="策略" /><el-table-column prop="exchangeName" label="交易所" /><el-table-column prop="pairSymbol" label="交易对" /><el-table-column prop="status" label="状态" /><el-table-column label="单笔/单日/总仓位" min-width="220"><template #default="s">{{ s.row.maxOrderNotional }} / {{ s.row.maxDailyNotional }} / {{ s.row.maxTotalExposure }} USDT</template></el-table-column><el-table-column label="操作"><template #default="s"><el-button link type="primary" @click="showLiveControl(s.row.id)">管理与查看证据</el-button></template></el-table-column></el-table>
  </ContentWrap>
  <el-dialog v-model="liveControlVisible" title="受控实盘与自动执行" width="85%">
    <template v-if="selectedLiveControl">
      <el-alert :title="selectedLiveControl.liveExecutionEnabled ? '真实执行总开关已启用；操作会产生真实订单。' : '真实执行总开关关闭；订单检查只记录门禁决定。'" :type="selectedLiveControl.liveExecutionEnabled ? 'error' : 'warning'" :closable="false" />
      <el-descriptions :column="3" border class="mt-3">
        <el-descriptions-item label="状态">{{ selectedLiveControl.status }}</el-descriptions-item>
        <el-descriptions-item label="绑定策略版本">{{ selectedLiveControl.strategy?.strategyVersionId }}</el-descriptions-item>
        <el-descriptions-item label="EMA / 止损 / 止盈">{{ selectedLiveControl.strategy?.configuration.fastPeriod }}/{{ selectedLiveControl.strategy?.configuration.slowPeriod }} / {{ selectedLiveControl.strategy?.configuration.stopLossRatio }} / {{ selectedLiveControl.strategy?.configuration.takeProfitRatio }}</el-descriptions-item>
        <el-descriptions-item label="交易范围">{{ selectedLiveControl.exchangeName }} / {{ selectedLiveControl.pairSymbol }} / 现货</el-descriptions-item>
        <el-descriptions-item label="凭据">{{ selectedLiveControl.credentialProvider==='UNCONFIGURED'?'未配置':'Windows DPAPI' }}</el-descriptions-item>
        <el-descriptions-item label="单笔上限">{{ selectedLiveControl.maxOrderNotional }} USDT</el-descriptions-item>
        <el-descriptions-item label="单日上限">{{ selectedLiveControl.maxDailyNotional }} USDT</el-descriptions-item>
        <el-descriptions-item label="总仓位上限">{{ selectedLiveControl.maxTotalExposure }} USDT</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="120px" class="mt-3">
        <el-divider content-position="left">门禁启停</el-divider>
        <el-form-item label="启用意见"><el-input v-model="liveControlArmComment" /></el-form-item>
        <el-form-item label="启用确认语"><el-input v-model="liveControlArmPhrase" placeholder="输入 CONFIRM_OFFLINE_GATE_ARM" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="selectedLiveControl.status!=='HALTED'" @click="armControl">启用门禁</el-button></el-form-item>
        <el-form-item label="停机原因"><el-input v-model="liveControlStopComment" /></el-form-item>
        <el-form-item><el-button type="danger" @click="stopControl">紧急停机</el-button></el-form-item>
        <el-divider content-position="left">固定策略自动会话</el-divider>
        <el-alert :title="liveAutomationEnabled ? '自动执行开关已启用；订单金额由后端固定配置。' : '自动执行开关关闭；需用临时启用配置启动单体。'" :type="liveAutomationEnabled ? 'error' : 'info'" :closable="false" />
        <el-form-item label="启动意见" class="mt-3"><el-input v-model="liveAutomationComment" maxlength="500" /></el-form-item>
        <el-form-item label="启动确认语"><el-input v-model="liveAutomationPhrase" placeholder="输入 CONFIRM_AUTO_LIVE_START" /></el-form-item>
        <el-form-item><el-button type="danger" :disabled="selectedLiveControl.status!=='ARMED_OFFLINE'||!selectedLiveControl.liveExecutionEnabled||!liveAutomationEnabled" @click="startAutomation">启动自动实盘</el-button></el-form-item>
      </el-form>
      <el-table :data="liveAutomationSessions" class="mt-3">
        <el-table-column prop="id" label="会话" min-width="280" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="orderNotional" label="单笔 USDT" width="110" />
        <el-table-column label="权益" width="120"><template #default="s">{{ s.row.lastEquity }}</template></el-table-column>
        <el-table-column label="最近心跳" width="180"><template #default="s">{{ s.row.lastHeartbeatAt ? new Date(s.row.lastHeartbeatAt).toLocaleString() : '-' }}</template></el-table-column>
        <el-table-column label="操作" width="190"><template #default="s"><el-button link type="primary" @click="showAutomation(s.row.id)">证据</el-button><el-button v-if="s.row.status==='RUNNING'" link type="danger" @click="stopAutomation(s.row.id)">停止</el-button></template></el-table-column>
      </el-table>
      <template v-if="selectedLiveAutomation">
        <el-descriptions :column="4" border class="mt-3">
          <el-descriptions-item label="策略">{{ selectedLiveAutomation.strategyName }}</el-descriptions-item>
          <el-descriptions-item label="周期">{{ selectedLiveAutomation.timeframe }}</el-descriptions-item>
          <el-descriptions-item label="最大会话亏损">{{ selectedLiveAutomation.maxSessionLoss }} USDT</el-descriptions-item>
          <el-descriptions-item label="停止原因">{{ selectedLiveAutomation.stopReason || '-' }}</el-descriptions-item>
        </el-descriptions>
        <h3>策略信号</h3>
        <el-table :data="selectedLiveAutomation.signals || []" max-height="260"><el-table-column label="K 线"><template #default="s">{{ new Date(s.row.candleAt).toLocaleString() }}</template></el-table-column><el-table-column prop="signalType" label="信号" /><el-table-column prop="status" label="处理状态" /><el-table-column prop="closePrice" label="收盘价" /><el-table-column prop="message" label="结果" min-width="240" /></el-table>
        <h3>账户与订单对账</h3>
        <el-table :data="selectedLiveAutomation.reconciliations || []" max-height="260"><el-table-column label="时间"><template #default="s">{{ new Date(s.row.reconciledAt).toLocaleString() }}</template></el-table-column><el-table-column prop="reconciliationStatus" label="状态" /><el-table-column prop="totalEquity" label="总权益" /><el-table-column prop="btcExposure" label="BTC 敞口" /><el-table-column label="挂单（交易所/平台）"><template #default="s">{{ s.row.exchangeOpenOrders }} / {{ s.row.platformOpenOrders }}</template></el-table-column><el-table-column prop="sessionLoss" label="会话亏损" /></el-table>
        <h3>自动停机告警</h3>
        <el-table :data="selectedLiveAutomation.alerts || []"><el-table-column prop="alertType" label="类型" /><el-table-column prop="status" label="状态" /><el-table-column prop="message" label="说明" min-width="360" /></el-table>
      </template>
      <el-form label-width="120px">
        <el-divider content-position="left">OKX 私有只读连接</el-divider>
        <el-form-item label="确认意见"><el-input v-model="okxReadComment" /></el-form-item>
        <el-form-item label="确认语"><el-input v-model="okxReadPhrase" placeholder="输入 CONFIRM_OKX_PRIVATE_READ" /></el-form-item>
        <el-form-item><el-button type="primary" :disabled="selectedLiveControl.credentialProvider==='UNCONFIGURED'" @click="verifyOkxRead">验证账户只读连接</el-button></el-form-item>
        <el-divider content-position="left">订单门禁检查</el-divider>
        <el-form-item label="客户端订单号"><el-input v-model="liveOrderForm.clientOrderId" /></el-form-item>
        <el-form-item label="方向"><el-select v-model="liveOrderForm.side"><el-option label="买入" value="BUY" /><el-option label="卖出" value="SELL" /></el-select></el-form-item>
        <el-form-item label="限价/数量"><el-input-number v-model="liveOrderForm.price" :min="0.00000001" /><el-input-number v-model="liveOrderForm.amount" :min="0.00000001" class="ml-2" /></el-form-item>
        <el-form-item label="当前风险"><span>仓位</span><el-input-number v-model="liveOrderForm.currentExposure" :min="0" class="ml-2" /><span class="ml-2">今日累计</span><el-input-number v-model="liveOrderForm.dailyExecutedNotional" :min="0" class="ml-2" /><span class="ml-2">挂单</span><el-input-number v-model="liveOrderForm.openOrders" :min="0" class="ml-2" /></el-form-item>
        <el-form-item><el-button type="primary" @click="runLiveOrderCheck">执行门禁检查</el-button></el-form-item>
      </el-form>
      <el-table :data="selectedLiveControl.decisions || []"><el-table-column prop="clientOrderId" label="客户端订单号" /><el-table-column prop="decision" label="决定" /><el-table-column prop="reasonCode" label="原因" /><el-table-column prop="notional" label="金额" /><el-table-column label="真实执行"><template #default="s">{{ s.row.executed ? '是' : '否' }}</template></el-table-column></el-table>
    </template>
  </el-dialog>
  <el-dialog v-model="liveAdmissionVisible" title="实盘前只读准入报告" width="75%"><template v-if="selectedLiveAdmission"><el-alert title="activationAllowed=false；该报告及双确认均不构成实盘授权。" type="warning" :closable="false" /><p v-if="liveAdmissionManifest?.strategy"><b>绑定策略：</b>{{ liveAdmissionManifest.strategy.strategyVersionId }} / {{ liveAdmissionManifest.strategy.configuration }}</p><p><b>报告摘要：</b>{{ selectedLiveAdmission.reportHash }}</p><el-table :data="liveAdmissionManifest?.checks || []"><el-table-column prop="id" label="检查项" min-width="220" /><el-table-column label="结果" width="90"><template #default="s"><el-tag :type="s.row.passed?'success':'danger'">{{ s.row.passed?'通过':'未通过' }}</el-tag></template></el-table-column><el-table-column prop="evidence" label="证据" min-width="360" /></el-table><h3>密钥边界</h3><el-descriptions :column="2" border><el-descriptions-item label="当前已配置">否</el-descriptions-item><el-descriptions-item label="权限">只读 + 交易，禁止提现</el-descriptions-item><el-descriptions-item label="IP 白名单">必须</el-descriptions-item><el-descriptions-item label="轮换周期">90 天；疑似泄露立即吊销</el-descriptions-item><el-descriptions-item label="托管" :span="2">{{ liveAdmissionManifest?.keyBoundary.storage }}</el-descriptions-item></el-descriptions><h3>人工双确认</h3><el-form label-width="110px"><el-divider content-position="left">证据复核</el-divider><el-form-item label="复核意见"><el-input v-model="liveEvidenceComment" maxlength="500" placeholder="例如：已核对五项证据，内容与验收记录一致" /></el-form-item><el-form-item label="证据确认语"><el-input v-model="liveEvidencePhrase" placeholder="输入 CONFIRM_EVIDENCE_REVIEWED" /></el-form-item><el-form-item><el-button type="primary" @click="confirmAdmission('EVIDENCE_REVIEW')">确认已复核证据</el-button></el-form-item><el-divider content-position="left">密钥边界复核</el-divider><el-form-item label="复核意见"><el-input v-model="liveKeyComment" maxlength="500" placeholder="例如：接受只读和交易权限、禁止提现及 IP 白名单要求" /></el-form-item><el-form-item label="边界确认语"><el-input v-model="liveKeyPhrase" placeholder="输入 CONFIRM_KEY_BOUNDARY_ACCEPTED" /></el-form-item><el-form-item><el-button type="primary" @click="confirmAdmission('KEY_BOUNDARY_REVIEW')">确认接受密钥边界</el-button></el-form-item></el-form><el-table :data="selectedLiveAdmission.confirmations" class="mt-3"><el-table-column prop="confirmationType" label="确认类型" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table></template></el-dialog>
  <el-dialog v-model="optimizationVisible" title="训练/验证结果" width="80%"><el-alert :title="optimizationResult?.terminal ? '结果按验证集收益率排序；系统不会自动采纳参数。' : '任务尚未全部结束，暂不生成排序。'" type="warning" :closable="false" /><el-table :data="optimizationResult?.ranking || []" class="mt-3"><el-table-column prop="rank" label="排名" /><el-table-column prop="parameterSetId" label="参数集" min-width="260" /><el-table-column prop="trainStatus" label="训练状态" /><el-table-column prop="validationStatus" label="验证状态" /><el-table-column prop="trainReturn" label="训练收益率" /><el-table-column prop="validationReturn" label="验证收益率" /><el-table-column prop="overfitGap" label="训练-验证差异" /><el-table-column prop="validationDrawdown" label="验证回撤" /><el-table-column prop="validationTrades" label="验证成交" /></el-table><template v-if="optimizationResult"><h3>研究结论草稿</h3><p>{{ optimizationResult.researchDraft.conclusion }}</p><p><b>证据摘要：</b>{{ optimizationResult.researchDraft.evidenceSha256 }}</p><el-alert v-for="risk in optimizationResult.researchDraft.risks" :key="risk" :title="risk" type="warning" :closable="false" class="mt-2" /><p><b>人工复核：</b>{{ optimizationResult.researchDraft.manualChecks.join('；') }}</p><el-button @click="downloadResearch">导出草稿</el-button><el-input v-model="reviewComment" class="mt-2" maxlength="500" placeholder="填写评审意见" /><el-button class="mt-2" type="success" @click="submitReview('ACCEPTED')">接受研究结论</el-button><el-button class="mt-2" type="danger" @click="submitReview('REJECTED')">拒绝</el-button><el-table :data="optimizationResult.reviews" class="mt-3"><el-table-column prop="decision" label="评审" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><h3>模拟盘准入（只读）</h3><el-alert title="该清单不会启动 dry-run、读取交易凭据或创建订单。" type="info" :closable="false" /><el-table :data="optimizationResult.paperAdmission.checks" class="mt-3"><el-table-column prop="label" label="检查项" /><el-table-column label="结果"><template #default="s"><el-tag :type="s.row.passed ? 'success' : 'danger'">{{ s.row.passed ? '通过' : '未通过' }}</el-tag></template></el-table-column><el-table-column prop="evidence" label="证据" min-width="300" /></el-table><el-input v-model="admissionComment" class="mt-2" maxlength="500" placeholder="填写准入确认意见" /><el-button class="mt-2" type="success" :disabled="!optimizationResult.paperAdmission.eligible" @click="submitAdmission('READY')">确认可进入模拟盘准备</el-button><el-button class="mt-2" type="danger" @click="submitAdmission('NOT_READY')">确认暂不准入</el-button><el-table :data="optimizationResult.paperAdmission.reviews" class="mt-3"><el-table-column prop="decision" label="准入决定" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><el-select v-model="paperParameterSetId" class="mt-3 w-300px" placeholder="人工选择参数集"><el-option v-for="row in optimizationResult.ranking.filter(x => x.validationReturn !== undefined)" :key="row.parameterSetId" :label="row.parameterSetId" :value="row.parameterSetId" /></el-select><el-button class="mt-3" type="primary" :disabled="!optimizationResult.paperAdmission.eligible || !paperParameterSetId" @click="submitPaperSession">创建待审批会话</el-button></template></el-dialog>
  <el-dialog v-model="paperSessionVisible" title="模拟盘会话启动审批" width="65%"><template v-if="selectedPaperSession"><el-descriptions :column="1" border><el-descriptions-item label="状态">{{ selectedPaperSession.status }}</el-descriptions-item><el-descriptions-item label="参数集">{{ selectedPaperSession.parameter_set_id }}</el-descriptions-item><el-descriptions-item label="准入证据">{{ selectedPaperSession.admission_evidence_hash }}</el-descriptions-item><el-descriptions-item label="执行能力">关闭</el-descriptions-item></el-descriptions><el-input v-model="sessionReviewComment" class="mt-3" maxlength="500" placeholder="填写启动审批意见" /><el-button class="mt-2" type="success" :disabled="selectedPaperSession.status !== 'PENDING_APPROVAL'" @click="submitPaperSessionReview('APPROVED')">批准启动准备</el-button><el-button class="mt-2" type="danger" :disabled="selectedPaperSession.status !== 'PENDING_APPROVAL'" @click="submitPaperSessionReview('REJECTED')">拒绝</el-button><el-table :data="selectedPaperSession.reviews || []" class="mt-3"><el-table-column prop="decision" label="决定" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><h3>启动前环境校验</h3><el-button type="primary" :disabled="selectedPaperSession.status !== 'APPROVED'" @click="generateReadiness">生成配置与校验快照</el-button><el-button type="warning" :disabled="!readinessSnapshots[0]?.ready" @click="planExecution">创建执行计划（不启动）</el-button><el-table :data="readinessSnapshots" class="mt-3"><el-table-column label="结果"><template #default="s"><el-tag :type="s.row.ready ? 'success' : 'danger'">{{ s.row.ready ? '通过' : '未通过' }}</el-tag></template></el-table-column><el-table-column prop="manifestHash" label="清单摘要" min-width="300" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><el-alert v-if="readinessSnapshots.length" class="mt-3" :title="readinessSummary" type="info" :closable="false" /></template></el-dialog>
  <el-dialog v-model="detailVisible" title="回测结果与实验记录" width="80%">
    <template v-if="selected">
      <el-alert v-if="selected.errorMessage" :title="selected.errorMessage" type="error" :closable="false" />
      <el-descriptions :column="1" border>
        <el-descriptions-item label="任务编号">{{ selected.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(selected.status) }}</el-descriptions-item>
        <el-descriptions-item label="策略 SHA-256">{{ selected.strategyHash }}</el-descriptions-item>
        <el-descriptions-item label="策略配置">{{ strategyLabel(selected.strategyConfiguration) }}</el-descriptions-item>
        <el-descriptions-item label="策略版本">{{ selected.strategyName }} / {{ selected.strategyVersionId }}</el-descriptions-item>
        <el-descriptions-item label="行情 SHA-256">{{ selected.datasetHash }}</el-descriptions-item>
        <el-descriptions-item label="数据来源">{{ selected.datasetSource }}</el-descriptions-item>
        <el-descriptions-item label="引擎版本">{{ selected.engineVersion || '尚未完成' }}</el-descriptions-item>
        <el-descriptions-item label="参数">{{ selected.parametersJson }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="selected.status === 'SUCCEEDED'" class="mt-3"><el-button @click="downloadReport('md')">导出实验报告</el-button><el-button @click="downloadReport('json')">导出可复现清单</el-button></div>
      <template v-if="result">
        <el-descriptions class="mt-4" :column="3" border>
          <el-descriptions-item label="成交数">{{ result.totalTrades }}</el-descriptions-item>
          <el-descriptions-item label="净收益 USDT">{{ result.netProfit.toFixed(4) }}</el-descriptions-item>
          <el-descriptions-item label="收益率">{{ (result.returnRatio * 100).toFixed(3) }}%</el-descriptions-item>
          <el-descriptions-item label="最大回撤">{{ (result.maxDrawdownRatio * 100).toFixed(3) }}%</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="result.totalTrades === 0" class="mt-3" title="本区间没有成交；这不代表策略盈利能力已验证。" type="warning" :closable="false" />
        <el-table :data="result.trades" max-height="360" class="mt-4">
          <el-table-column prop="instrument" label="交易对" />
          <el-table-column prop="openedAt" label="开仓 UTC" min-width="180" />
          <el-table-column prop="closedAt" label="平仓 UTC" min-width="180" />
          <el-table-column prop="netProfit" label="收益 USDT" />
          <el-table-column prop="exitReason" label="退出原因" />
        </el-table>
      </template>
    </template>
  </el-dialog>
  <el-dialog v-model="compareVisible" title="回测实验对比" width="75%">
    <el-alert title="对比时请核对数据区间、初始资金和费率是否相同；以下为历史回测结果。" type="info" :closable="false" />
    <el-table :data="comparisons"><el-table-column label="策略配置" min-width="250"><template #default="s">{{ strategyLabel(strategyVersions.find(v => v.id === s.row.strategyVersionId)?.configuration) }}</template></el-table-column><el-table-column prop="strategyVersionId" label="版本 ID" min-width="260" show-overflow-tooltip /><el-table-column prop="datasetId" label="数据集" /><el-table-column prop="totalTrades" label="成交数" /><el-table-column label="净收益"><template #default="s">{{ Number(s.row.netProfit).toFixed(4) }}</template></el-table-column><el-table-column label="收益率"><template #default="s">{{ (Number(s.row.returnRatio) * 100).toFixed(3) }}%</template></el-table-column><el-table-column label="最大回撤"><template #default="s">{{ (Number(s.row.maxDrawdownRatio) * 100).toFixed(3) }}%</template></el-table-column></el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import StrategyConfigEditor from './StrategyConfigEditor.vue'
import { strategyConfigurationLabel } from '@/api/quant/backtest'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createBacktest, listBacktests, getBacktest, getCapabilities, listStrategyVersions, listParameterSets, createParameterSet, compareBacktests, listDatasets, createDatasetDownload, listDatasetDownloads, exportBacktestReport, createOptimization, listOptimizations, getOptimization, reviewOptimization, reviewPaperAdmission, exportResearchReport, createPaperSession, listPaperSessions, getPaperSession, reviewPaperSession, createPaperReadiness, listPaperReadiness, createPaperExecution, listPaperExecutions, stopPaperExecution, createPaperCommandPreview, getPaperCommandPreview, issuePaperStartToken, startPaperExecution, observePaperExecution, listPaperObservationSnapshots, listPaperAlerts, actPaperAlert, listPaperAlertActions, listPaperOrders, listPaperOrderReconciliations, createLiveAdmission, listLiveAdmissions, getLiveAdmission, confirmLiveAdmission, exportLiveAdmission, createLiveControl, listLiveControls, getLiveControl, armLiveControl, emergencyStopLiveControl, checkLiveOrder, verifyOkxPrivateRead, startLiveAutomation, stopLiveAutomation, listLiveAutomations, getLiveAutomation } from '@/api/quant/backtest'
import type { BacktestTask, StrategyVersion, ParameterSet, BacktestComparison, DatasetQuality, DatasetDownloadTask, OptimizationBatch, OptimizationResult, PaperSession, PaperReadinessSnapshot, PaperExecution, PaperCommandPreview, PaperStartToken, PaperExecutionObservation, PaperObservationSnapshot, PaperAlert, PaperAlertAction, PaperOrder, PaperOrderReconciliation, LiveAdmissionSummary, LiveAdmissionReport, LiveControlPolicy, LiveAutomationSession } from '@/api/quant/backtest'

defineOptions({ name: 'QuantBacktest' })
interface Result {
  totalTrades: number
  netProfit: number
  returnRatio: number
  maxDrawdownRatio: number
  trades: Array<{ instrument: string; openedAt: string; closedAt: string; netProfit: number; exitReason: string }>
}
const form = reactive({ strategyVersionId: '', parameterSetId: '', datasetId: '', startingBalance: 1000, stakeAmount: 100, fee: 0.001 })
const strategyVersions = ref<StrategyVersion[]>([])
const parameterSets = ref<ParameterSet[]>([])
const selectedIds = ref<string[]>([])
const comparisons = ref<BacktestComparison[]>([])
const compareVisible = ref(false)
const datasets = ref<DatasetQuality[]>([])
const downloadTasks = ref<DatasetDownloadTask[]>([])
const downloadForm = reactive({ datasetId: '' })
const downloadDates = ref<string[]>([])
const downloadSubmitting = ref(false)
const optimization=reactive({parameterSetIds: [] as string[]})
const optimizationDates=ref<string[]>([])
const optimizationBatches=ref<OptimizationBatch[]>([])
const optimizationResult=ref<OptimizationResult>()
const optimizationVisible=ref(false)
const reviewComment=ref('')
const admissionComment=ref('')
const paperParameterSetId=ref('')
const paperSessions=ref<PaperSession[]>([])
const selectedPaperSession=ref<PaperSession>()
const paperSessionVisible=ref(false)
const sessionReviewComment=ref('')
const readinessSnapshots=ref<PaperReadinessSnapshot[]>([])
const readinessSummary=computed(()=>{if(!readinessSnapshots.value.length)return '';const m=JSON.parse(readinessSnapshots.value[0].manifestJson);return m.checks.map((x:{id:string;passed:boolean})=>`${x.id}:${x.passed?'通过':'未通过'}`).join('；')+'；执行保持关闭'})
const paperExecutions=ref<PaperExecution[]>([])
const executionStopComment=ref('')
const commandPreview=ref<PaperCommandPreview>()
const commandPreviewVisible=ref(false)
const previewExecutionId=ref('')
const previewExecutionEnabled=ref(false)
const startTokenComment=ref('')
const startTokenConfirmation=ref('')
const issuedStartToken=ref<PaperStartToken>()
const executionObservation=ref<PaperExecutionObservation>()
const observationSnapshots=ref<PaperObservationSnapshot[]>([])
const paperAlerts=ref<PaperAlert[]>([])
const paperAlertActions=ref<PaperAlertAction[]>([])
const paperOrders=ref<PaperOrder[]>([])
const paperOrderReconciliations=ref<PaperOrderReconciliation[]>([])
const observationExecutionId=ref('')
const observationVisible=ref(false)
const liveBacktestId=ref<string>()
const liveAdmissions=ref<LiveAdmissionSummary[]>([])
const selectedLiveAdmission=ref<LiveAdmissionReport>()
const liveAdmissionVisible=ref(false)
const liveEvidenceComment=ref('')
const liveKeyComment=ref('')
const liveEvidencePhrase=ref('')
const liveKeyPhrase=ref('')
const liveAdmissionManifest=computed(()=>selectedLiveAdmission.value?JSON.parse(selectedLiveAdmission.value.reportJson):undefined)
const liveControls=ref<LiveControlPolicy[]>([])
const selectedLiveControl=ref<LiveControlPolicy>()
const liveControlVisible=ref(false)
const liveControlArmComment=ref('')
const liveControlArmPhrase=ref('')
const liveControlStopComment=ref('')
const liveAutomationEnabled=ref(false)
const liveAutomationSessions=ref<LiveAutomationSession[]>([])
const selectedLiveAutomation=ref<LiveAutomationSession>()
const liveAutomationComment=ref('')
const liveAutomationPhrase=ref('')
const okxReadComment=ref('')
const okxReadPhrase=ref('')
const liveOrderForm=reactive({clientOrderId:`offline-${Date.now()}`,side:'BUY' as 'BUY'|'SELL',orderType:'LIMIT' as const,price:100,amount:0.05,currentExposure:0,dailyExecutedNotional:0,openOrders:0})
const dates = ref<string[]>([])
const tasks = ref<BacktestTask[]>([])
const selected = ref<BacktestTask>()
const detailVisible = ref(false)
const enabled = ref(false)
const loading = ref(false)
const submitting = ref(false)
const result = computed<Result | null>(() => selected.value?.resultJson ? JSON.parse(selected.value.resultJson) : null)
const statusLabel = (status: string) => ({ QUEUED: '排队中', RUNNING: '回测中', SUCCEEDED: '已完成', FAILED: '失败' })[status] || status
const statusType = (status: string): 'success' | 'danger' | 'warning' | 'info' => status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
// Keep the same key for uncertain network failures; changed parameters get a new key.
let lastPayload = ''
let requestKey = ''
async function submit() {
  if (submitting.value) return
  if (!form.strategyVersionId || !form.parameterSetId || !/^[A-Za-z0-9_-]{1,64}$/.test(form.datasetId) || dates.value?.length !== 2 || !dates.value[0] || !dates.value[1]) {
    ElMessage.warning('请选择策略版本、参数集并填写有效数据集编号和日期区间')
    return
  }
  const params = { ...form, startDate: dates.value[0], endDate: dates.value[1] }
  const payload = JSON.stringify(params)
  if (payload !== lastPayload) { requestKey = crypto.randomUUID(); lastPayload = payload }
  submitting.value = true
  try {
    await createBacktest({ ...params, requestKey })
    lastPayload = ''
    ElMessage.success('任务已提交')
    await refresh()
  } finally { submitting.value = false }
}
async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    tasks.value = await listBacktests()
    downloadTasks.value = await listDatasetDownloads()
    datasets.value = await listDatasets()
    optimizationBatches.value = await listOptimizations()
    paperSessions.value = await listPaperSessions()
    paperExecutions.value = await listPaperExecutions()
    liveAdmissions.value = await listLiveAdmissions()
    liveControls.value = await listLiveControls()
    if (detailVisible.value && selected.value) selected.value = await getBacktest(selected.value.id)
  } finally { loading.value = false }
}
async function submitOptimization(){
  const d=[...optimizationDates.value].sort(); if(optimization.parameterSetIds.length<2||optimization.parameterSetIds.length>5||d.length!==3||!form.datasetId||!form.strategyVersionId){ElMessage.warning('请选择 2 至 5 个参数集和三个日期');return}
  await createOptimization({strategyVersionId:form.strategyVersionId,datasetId:form.datasetId,trainStart:d[0],splitDate:d[1],validationEnd:d[2],parameterSetIds:optimization.parameterSetIds});ElMessage.success('优化批次已提交');await refresh()
}
async function showOptimization(id:string){optimizationResult.value=await getOptimization(id);optimizationVisible.value=true}
async function submitReview(decision:'ACCEPTED'|'REJECTED'){if(!optimizationResult.value||!reviewComment.value.trim()){ElMessage.warning('请填写评审意见');return}await reviewOptimization(optimizationResult.value.id,{decision,comment:reviewComment.value.trim(),evidenceHash:optimizationResult.value.researchDraft.evidenceSha256});reviewComment.value='';optimizationResult.value=await getOptimization(optimizationResult.value.id);ElMessage.success('评审已留痕')}
async function submitAdmission(decision:'READY'|'NOT_READY'){if(!optimizationResult.value||!admissionComment.value.trim()){ElMessage.warning('请填写准入确认意见');return}await reviewPaperAdmission(optimizationResult.value.id,{decision,comment:admissionComment.value.trim(),evidenceHash:optimizationResult.value.paperAdmission.evidenceSha256});admissionComment.value='';optimizationResult.value=await getOptimization(optimizationResult.value.id);ElMessage.success('准入确认已留痕；执行仍保持关闭')}
async function submitPaperSession(){if(!optimizationResult.value||!paperParameterSetId.value)return;await createPaperSession({batchId:optimizationResult.value.id,parameterSetId:paperParameterSetId.value});paperParameterSetId.value='';await refresh();ElMessage.success('待审批会话已创建；执行仍保持关闭')}
async function showPaperSession(id:string){selectedPaperSession.value=await getPaperSession(id);readinessSnapshots.value=await listPaperReadiness(id);paperSessionVisible.value=true}
async function submitPaperSessionReview(decision:'APPROVED'|'REJECTED'){if(!selectedPaperSession.value||!sessionReviewComment.value.trim()){ElMessage.warning('请填写启动审批意见');return}await reviewPaperSession(selectedPaperSession.value.id,{decision,comment:sessionReviewComment.value.trim(),admissionEvidenceHash:selectedPaperSession.value.admission_evidence_hash});sessionReviewComment.value='';selectedPaperSession.value=await getPaperSession(selectedPaperSession.value.id);await refresh();ElMessage.success('启动审批已留痕；执行仍保持关闭')}
async function generateReadiness(){if(!selectedPaperSession.value)return;await createPaperReadiness(selectedPaperSession.value.id);readinessSnapshots.value=await listPaperReadiness(selectedPaperSession.value.id);ElMessage.success('配置与环境校验快照已生成；未启动容器')}
async function planExecution(){if(!selectedPaperSession.value)return;await createPaperExecution(selectedPaperSession.value.id);await refresh();ElMessage.success('执行计划已创建并等待总开关；未启动容器')}
async function stopExecution(id:string){if(!executionStopComment.value.trim()){ElMessage.warning('请填写停止原因');return}await stopPaperExecution(id,executionStopComment.value.trim());executionStopComment.value='';await refresh();ElMessage.success('任务已按精确标识停止并留痕')}
async function previewExecution(id:string){await createPaperCommandPreview(id);commandPreview.value=await getPaperCommandPreview(id);previewExecutionId.value=id;previewExecutionEnabled.value=paperExecutions.value.find(item=>item.id===id)?.executionEnabled===true;commandPreviewVisible.value=true;ElMessage.success('安全命令预览已生成；未执行 Docker')}
async function issueStartToken(){if(!commandPreview.value||!startTokenComment.value.trim()||startTokenConfirmation.value!=='CONFIRM_DRY_RUN_START'){ElMessage.warning('请填写确认意见并输入完整确认语');return}issuedStartToken.value=await issuePaperStartToken(previewExecutionId.value,{previewHash:commandPreview.value.previewHash,confirmation:'CONFIRM_DRY_RUN_START',comment:startTokenComment.value.trim()});ElMessage.success('一次性令牌已签发；未启动容器')}
async function startDryRun(){if(!commandPreview.value||!issuedStartToken.value||!previewExecutionEnabled.value)return;await startPaperExecution(previewExecutionId.value,{previewHash:commandPreview.value.previewHash,token:issuedStartToken.value.token});commandPreviewVisible.value=false;await refresh();ElMessage.success('dry-run 已启动并进入状态监控')}
function clearStartToken(){issuedStartToken.value=undefined;startTokenComment.value='';startTokenConfirmation.value='';previewExecutionId.value='';previewExecutionEnabled.value=false}
async function showExecutionObservation(id:string){observationExecutionId.value=id;await refreshExecutionObservation();observationVisible.value=true}
async function refreshExecutionObservation(){if(observationExecutionId.value)[executionObservation.value,observationSnapshots.value,paperAlerts.value,paperAlertActions.value,paperOrders.value,paperOrderReconciliations.value]=await Promise.all([observePaperExecution(observationExecutionId.value),listPaperObservationSnapshots(observationExecutionId.value),listPaperAlerts(observationExecutionId.value),listPaperAlertActions(observationExecutionId.value),listPaperOrders(observationExecutionId.value),listPaperOrderReconciliations(observationExecutionId.value)])}
async function handleAlert(item:PaperAlert,action:'ACKNOWLEDGE'|'RESOLVE'){const {value}=await ElMessageBox.prompt(action==='ACKNOWLEDGE'?'请输入确认备注':'请输入解决说明',action==='ACKNOWLEDGE'?'确认告警':'解决告警',{inputPattern:/\S+/,inputErrorMessage:'备注不能为空'});await actPaperAlert(item.id,{action,comment:value.trim()});await refreshExecutionObservation();ElMessage.success('告警状态和处置审计已更新')}
async function generateLiveAdmission(){const id=await createLiveAdmission(liveBacktestId.value);await refresh();await showLiveAdmission(id);ElMessage.success('不可变准入报告已生成；实盘仍关闭')}
async function showLiveAdmission(id:string){selectedLiveAdmission.value=await getLiveAdmission(id);liveAdmissionVisible.value=true}
async function confirmAdmission(type:'EVIDENCE_REVIEW'|'KEY_BOUNDARY_REVIEW'){if(!selectedLiveAdmission.value)return;const evidence=type==='EVIDENCE_REVIEW',comment=(evidence?liveEvidenceComment.value:liveKeyComment.value).trim(),phrase=(evidence?liveEvidencePhrase.value:liveKeyPhrase.value).trim(),expected=evidence?'CONFIRM_EVIDENCE_REVIEWED':'CONFIRM_KEY_BOUNDARY_ACCEPTED';if(!comment){ElMessage.warning(`请填写${evidence?'证据':'密钥边界'}复核意见`);return}if(phrase!==expected){ElMessage.warning(`请在确认语输入框完整输入 ${expected}`);return}await confirmLiveAdmission(selectedLiveAdmission.value.id,{confirmationType:type,confirmationPhrase:phrase,comment,reportHash:selectedLiveAdmission.value.reportHash});selectedLiveAdmission.value=await getLiveAdmission(selectedLiveAdmission.value.id);await refresh();if(evidence){liveEvidenceComment.value='';liveEvidencePhrase.value=''}else{liveKeyComment.value='';liveKeyPhrase.value=''}ElMessage.success('确认已追加留痕；实盘仍关闭')}
async function downloadLiveAdmission(id:string){const blob=await exportLiveAdmission(id);const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`live-admission-${id}.json`;link.click();URL.revokeObjectURL(url)}
async function generateLiveControl(reportId:string){const id=await createLiveControl(reportId);await refresh();await showLiveControl(id);ElMessage.success('离线安全门禁已创建；真实执行仍关闭')}
async function showLiveControl(id:string){selectedLiveControl.value=await getLiveControl(id);liveAutomationSessions.value=await listLiveAutomations(id);liveControlVisible.value=true}
async function armControl(){if(!selectedLiveControl.value)return;if(!liveControlArmComment.value.trim()){ElMessage.warning('请填写启用意见');return}if(liveControlArmPhrase.value.trim()!=='CONFIRM_OFFLINE_GATE_ARM'){ElMessage.warning('请输入 CONFIRM_OFFLINE_GATE_ARM');return}await armLiveControl(selectedLiveControl.value.id,{confirmation:'CONFIRM_OFFLINE_GATE_ARM',comment:liveControlArmComment.value.trim()});await showLiveControl(selectedLiveControl.value.id);await refresh();ElMessage.success('离线门禁已启用，真实执行仍关闭')}
async function stopControl(){if(!selectedLiveControl.value||!liveControlStopComment.value.trim()){ElMessage.warning('请填写停机原因');return}await emergencyStopLiveControl(selectedLiveControl.value.id,liveControlStopComment.value.trim());await showLiveControl(selectedLiveControl.value.id);await refresh();ElMessage.success('紧急停机已生效')}
async function runLiveOrderCheck(){if(!selectedLiveControl.value)return;await checkLiveOrder(selectedLiveControl.value.id,{...liveOrderForm});await showLiveControl(selectedLiveControl.value.id);liveOrderForm.clientOrderId=`offline-${Date.now()}`;ElMessage.success('离线订单门禁检查完成，未发送真实订单')}
async function verifyOkxRead(){if(!selectedLiveControl.value||!okxReadComment.value.trim()){ElMessage.warning('请填写确认意见');return}if(okxReadPhrase.value.trim()!=='CONFIRM_OKX_PRIVATE_READ'){ElMessage.warning('请输入 CONFIRM_OKX_PRIVATE_READ');return}const result=await verifyOkxPrivateRead(selectedLiveControl.value.id,{confirmation:'CONFIRM_OKX_PRIVATE_READ',comment:okxReadComment.value.trim()});await showLiveControl(selectedLiveControl.value.id);ElMessage.success(`OKX 私有只读连接通过，返回 ${result.dataCount} 组账户数据，未发送订单`)}
async function startAutomation(){if(!selectedLiveControl.value||!liveAutomationComment.value.trim()){ElMessage.warning('请填写启动意见');return}if(liveAutomationPhrase.value.trim()!=='CONFIRM_AUTO_LIVE_START'){ElMessage.warning('请输入 CONFIRM_AUTO_LIVE_START');return}const id=await startLiveAutomation(selectedLiveControl.value.id,{confirmation:'CONFIRM_AUTO_LIVE_START',comment:liveAutomationComment.value.trim()});liveAutomationComment.value='';liveAutomationPhrase.value='';await showLiveControl(selectedLiveControl.value.id);await showAutomation(id);ElMessage.success('自动实盘会话已启动')}
async function showAutomation(id:string){selectedLiveAutomation.value=await getLiveAutomation(id)}
async function stopAutomation(id:string){const {value}=await ElMessageBox.prompt('请输入停止原因','停止自动实盘',{inputPattern:/\S+/,inputErrorMessage:'停止原因不能为空'});await stopLiveAutomation(id,value.trim());if(selectedLiveControl.value)await showLiveControl(selectedLiveControl.value.id);selectedLiveAutomation.value=await getLiveAutomation(id);ElMessage.success('自动实盘会话已停止，门禁已转为 HALTED')}
async function downloadResearch(){if(!optimizationResult.value)return;const blob=await exportResearchReport(optimizationResult.value.id);const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=`research-${optimizationResult.value.id}.md`;link.click();URL.revokeObjectURL(url)}
async function submitDownload() {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(downloadForm.datasetId) || downloadDates.value.length !== 2) { ElMessage.warning('请填写有效的数据集编号和日期区间'); return }
  downloadSubmitting.value = true
  try { await createDatasetDownload({ requestKey: crypto.randomUUID(), datasetId: downloadForm.datasetId, startDate: downloadDates.value[0], endDate: downloadDates.value[1] }); ElMessage.success('下载任务已提交'); await refresh() }
  finally { downloadSubmitting.value = false }
}
async function showDetail(id: string) { selected.value = await getBacktest(id); detailVisible.value = true }
async function downloadReport(format: 'md' | 'json') {
  if (!selected.value) return
  const blob = await exportBacktestReport(selected.value.id, format)
  const url = URL.createObjectURL(blob); const link = document.createElement('a')
  link.href = url; link.download = `backtest-${selected.value.id}.${format}`; link.click(); URL.revokeObjectURL(url)
}
const strategyLabel = strategyConfigurationLabel

async function selectCreatedStrategy(id: string) {
  strategyVersions.value = await listStrategyVersions()
  form.strategyVersionId = id
  ElMessage.success('策略版本已保存并选中，可提交回测')
}
const parseParameters = (item: ParameterSet) => JSON.parse(item.parametersJson)
const parameterLabel = (item: ParameterSet) => { const p = parseParameters(item); return `${p.startingBalance} / ${p.stakeAmount} / ${p.fee}` }
function applyParameterSet(id: string) { const item = parameterSets.value.find(value => value.id === id); if (item) Object.assign(form, parseParameters(item)) }
async function saveParameterSet() { form.parameterSetId = await createParameterSet({ startingBalance: form.startingBalance, stakeAmount: form.stakeAmount, fee: form.fee }); parameterSets.value = await listParameterSets(); ElMessage.success('参数集已保存') }
async function compare() { comparisons.value = await compareBacktests(selectedIds.value); compareVisible.value = true }
let timer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  const capabilities = await getCapabilities()
  enabled.value = capabilities.enabled
  liveAutomationEnabled.value = capabilities.liveAutomationEnabled
  strategyVersions.value = await listStrategyVersions()
  if (strategyVersions.value.length) form.strategyVersionId = strategyVersions.value[0].id
  parameterSets.value = await listParameterSets()
  if (parameterSets.value.length) { form.parameterSetId = parameterSets.value[0].id; applyParameterSet(form.parameterSetId) }
  datasets.value = await listDatasets()
  if (datasets.value.some(item => item.status === 'VALID')) form.datasetId = datasets.value.find(item => item.status === 'VALID')!.id
  await refresh()
  timer = setInterval(() => { if (tasks.value.some(t => ['QUEUED', 'RUNNING'].includes(t.status)) || downloadTasks.value.some(t => ['QUEUED', 'RUNNING'].includes(t.status))) void refresh().catch(() => {}) }, 5000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>
