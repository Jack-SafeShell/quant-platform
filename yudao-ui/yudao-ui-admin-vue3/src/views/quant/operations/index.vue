<template>
  <div class="operations-page">
    <ContentWrap>
      <div class="page-head">
        <div>
          <div class="eyebrow">QUANT OPERATIONS</div>
          <h1>策略与运行面板</h1>
          <p>集中查看策略版本、参数、模拟盘、实盘会话、订单和风险状态。</p>
        </div>
        <div class="head-actions">
          <span class="updated">更新于 {{ formatTime(lastUpdated) }}</span>
          <el-switch v-model="autoRefresh" active-text="自动刷新" />
          <el-button :loading="loading" @click="refresh">刷新</el-button>
          <el-button type="primary" @click="openResearch">研究与回测</el-button>
        </div>
      </div>
      <el-alert
        :title="switchSummary"
        :type="capabilities?.liveExecutionEnabled && capabilities?.liveAutomationEnabled ? 'warning' : 'info'"
        :closable="false"
        show-icon
      />
      <el-alert v-if="refreshError" class="mt-3" :title="refreshError" type="error" :closable="false" show-icon />
      <p class="evidence-note">当前执行账户：{{ tradingAccount?.exchange || '-' }} · {{ tradingAccount?.id || '-' }}。下方账户选择仅筛选历史证据，不切换密钥或执行账户。</p>
      <div class="session-picker mt-3">
        <span>查看账户</span>
        <el-select v-model="viewAccountId" :disabled="loading || !!repairingOrderId" @change="changeAccount">
          <el-option v-for="account in accountOptions" :key="account.id" :value="account.id" :label="`${account.exchange} · ${account.id}`" />
        </el-select>
        <span>查看会话</span>
        <el-select v-model="selectedSessionId" :disabled="loading || !!repairingOrderId" placeholder="暂无实盘会话" @change="refresh">
          <el-option v-for="session in sessions" :key="session.id" :value="session.id"
            :label="`${formatTime(session.startedAt)} · ${session.status} · ${session.id.slice(0, 8)}`" />
        </el-select>
        <el-tag v-if="activeSession" :type="sessionTag(activeSession.status)">{{ activeSession.status }}</el-tag>
      </div>
      <div class="metric-grid">
        <div class="metric-card"><span>策略版本</span><strong>{{ strategyVersions.length }}</strong><small>{{ strategyCount }} 个策略</small></div>
        <div class="metric-card"><span>运行环境</span><strong>{{ runningCount }}</strong><small>实盘与模拟盘</small></div>
        <div class="metric-card"><span>账户权益</span><strong>{{ money(latestReconciliation?.totalEquity) }}</strong><small>USDT</small></div>
        <div class="metric-card"><span>BTC 敞口</span><strong>{{ money(latestReconciliation?.btcExposure) }}</strong><small>USDT</small></div>
        <div class="metric-card" :class="{ danger: openAlerts > 0 }"><span>未处理告警</span><strong>{{ openAlerts }}</strong><small>{{ refreshError ? '数据待更新' : activeSession ? (openAlerts ? '需要处理' : '所选会话无 OPEN 告警') : '暂无会话数据' }}</small></div>
      </div>
    </ContentWrap>

    <el-row :gutter="16">
      <el-col :xs="24" :xl="16">
        <ContentWrap title="所选自动实盘会话">
          <template #header>
            <div class="section-head">
              <el-tag :type="sessionTag(activeSession?.status)">{{ statusLabel(activeSession?.status) }}</el-tag>
            </div>
          </template>
          <el-empty v-if="!activeSession" description="暂无自动实盘会话" />
          <template v-else>
            <el-descriptions :column="3" border>
              <el-descriptions-item label="会话 ID" :span="3">{{ activeSession.id }}</el-descriptions-item>
              <el-descriptions-item label="账户">{{ activeSession.accountId }}</el-descriptions-item>
              <el-descriptions-item label="交易所">{{ activeSession.exchangeName }}</el-descriptions-item>
              <el-descriptions-item label="当前可操作">{{ activeSession.selectedAccount ? '当前执行账户' : '历史账户，仅查看' }}</el-descriptions-item>
              <el-descriptions-item label="策略">{{ activeSession.strategyName }}</el-descriptions-item>
              <el-descriptions-item label="周期">{{ activeSession.timeframe }}</el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ formatTime(activeSession.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="最后心跳">{{ formatTime(activeSession.lastHeartbeatAt) }}</el-descriptions-item>
              <el-descriptions-item label="最近收盘 K 线">{{ formatTime(activeSession.lastCandleAt) }}</el-descriptions-item>
              <el-descriptions-item label="权益变化" :class-name="equityChange >= 0 ? 'positive' : 'negative'">{{ signedMoney(equityChange) }} USDT</el-descriptions-item>
            </el-descriptions>
            <div class="session-strip">
              <div><span>对账</span><b>{{ latestReconciliation?.reconciliationStatus || '-' }}</b></div>
              <div><span>会话亏损</span><b>{{ money(latestReconciliation?.sessionLoss) }} USDT</b></div>
              <div><span>挂单</span><b>{{ latestReconciliation?.exchangeOpenOrders ?? 0 }} / {{ latestReconciliation?.platformOpenOrders ?? 0 }}</b></div>
              <div><span>最新信号</span><b>{{ latestSignal?.signalType || '-' }} · {{ latestSignal?.status || '-' }}</b></div>
            </div>
            <el-alert v-if="activeSession.stopReason" class="mt-3" :title="activeSession.stopReason" type="warning" :closable="false" />
            <div class="mt-3 text-right">
              <el-button
                v-if="activeSession.status === 'RUNNING'"
                v-hasPermi="['quant:backtest:create']"
                type="danger"
                :disabled="!activeSession.selectedAccount || loading || !!refreshError"
                plain
                @click="stopCurrentSession"
              >停止当前会话</el-button>
            </div>
          </template>
        </ContentWrap>
      </el-col>
      <el-col :xs="24" :xl="8">
        <ContentWrap title="风险门禁">
          <el-empty v-if="!activePolicy" description="暂无实盘门禁" />
          <template v-else>
            <div class="gate-state">
              <el-tag :type="activePolicy.status === 'ARMED_OFFLINE' ? 'warning' : 'info'" size="large">{{ activePolicy.status }}</el-tag>
              <span>{{ activePolicy.exchangeName }} · {{ activePolicy.pairSymbol }} · {{ activePolicy.tradingMode }}</span>
            </div>
            <el-descriptions :column="1" border class="mt-3">
              <el-descriptions-item label="固定账户">{{ activePolicy.accountId }}</el-descriptions-item>
              <el-descriptions-item label="单笔上限">{{ money(activePolicy.maxOrderNotional) }} USDT</el-descriptions-item>
              <el-descriptions-item label="单日上限">{{ money(activePolicy.maxDailyNotional) }} USDT</el-descriptions-item>
              <el-descriptions-item label="总敞口上限">{{ money(activePolicy.maxTotalExposure) }} USDT</el-descriptions-item>
              <el-descriptions-item label="最大挂单">{{ activePolicy.maxOpenOrders }}</el-descriptions-item>
              <el-descriptions-item label="私有 API">{{ activePolicy.privateApiConnected ? '已验证' : '未验证' }}</el-descriptions-item>
            </el-descriptions>
          </template>
        </ContentWrap>
      </el-col>
    </el-row>

    <ContentWrap v-if="activeSession" title="会话运行证据">
      <el-tabs v-model="evidenceTab">
        <el-tab-pane label="交易执行与收益" name="trace">
          <el-alert v-if="performanceError" :title="performanceError" type="warning" :closable="false" />
          <template v-if="performance">
            <el-descriptions :column="3" border>
              <el-descriptions-item label="会话净收益贡献">{{ performanceMoney(performance.netContribution) }} USDT</el-descriptions-item>
              <el-descriptions-item label="已知费用与返佣折算">{{ performanceMoney(performance.knownSignedCostsUsdt) }} USDT（扣费负）</el-descriptions-item>
              <el-descriptions-item label="剩余持仓估值">{{ performanceMoney(performance.markedPositionValue) }} USDT</el-descriptions-item>
              <el-descriptions-item label="绑定版本">{{ performance.strategy?.strategyVersionId || performance.legacyStrategyVersionId || '未记录' }}</el-descriptions-item>
              <el-descriptions-item label="策略配置">{{ strategyConfigurationLabel(performance.strategy?.configuration) }}</el-descriptions-item>
              <el-descriptions-item label="准入证据">{{ shortHash(performance.admissionReportHash) }}</el-descriptions-item>
            </el-descriptions>
            <el-alert v-if="!performance.valuationComplete" class="mt-3" title="费用或估值证据不完整，净收益暂不可计算。" type="warning" :closable="false" />
            <el-alert v-if="performance.filledOrderCount === 0" class="mt-3" title="该会话尚无成交，零收益不构成策略有效证据。" type="info" :closable="false" />
            <p class="evidence-note">净收益基于完整会话账本；下方显示最近 {{ performance.executionTrace?.length || 0 }} / 共 {{ performance.signalCount }} 条信号，最多 {{ performance.traceLimit }} 条。历史触发原因缺失时不作推断；停止会话的估值不是当前实时价格。单笔买卖成交额不等同于该笔利润。</p>
            <div class="head-actions mb-3">
              <el-switch v-model="traceOrdersOnly" active-text="仅显示有关联订单" />
              <el-button :disabled="!!refreshError || !!performanceError || loading || !!repairingOrderId" @click="exportExecutionReview">导出执行与收益快照</el-button>
            </div>
            <el-table :data="traceRows" max-height="520" row-key="id">
              <el-table-column type="expand"><template #default="s">
                <el-descriptions :column="2" border>
                  <el-descriptions-item label="信号证据">{{ s.row.signalHash }}</el-descriptions-item>
                  <el-descriptions-item label="门禁决定">{{ s.row.decisionId || '-' }} / {{ s.row.gateReason || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="交易所订单">{{ s.row.order?.exchangeOrderId || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="账本关联">{{ s.row.linkState }}</el-descriptions-item>
                  <el-descriptions-item label="快 EMA / 入场上沿">{{ s.row.fastEma }}</el-descriptions-item>
                  <el-descriptions-item label="慢 EMA / 退出下沿">{{ s.row.slowEma }}</el-descriptions-item>
                  <el-descriptions-item label="收盘价格">{{ s.row.closePrice }}</el-descriptions-item>
                  <el-descriptions-item label="执行说明" :span="2">{{ s.row.message || '-' }}</el-descriptions-item>
                </el-descriptions>
              </template></el-table-column>
              <el-table-column label="收盘 K 线" width="180"><template #default="s">{{ formatTime(s.row.candleAt) }}</template></el-table-column>
              <el-table-column prop="signalType" label="信号" width="80" />
              <el-table-column label="触发原因" width="140"><template #default="s">{{ reasonLabel(s.row.signalReason) }}</template></el-table-column>
              <el-table-column prop="status" label="信号结果" min-width="140" />
              <el-table-column prop="gateReason" label="门禁结果" min-width="140" />
              <el-table-column label="订单状态" width="135"><template #default="s">{{ s.row.order?.status || '-' }}</template></el-table-column>
              <el-table-column label="累计成交 / 均价" min-width="190"><template #default="s">{{ s.row.order?.filledAmount ?? '-' }} / {{ s.row.order?.averagePrice ?? '-' }}</template></el-table-column>
              <el-table-column label="实际费用 / 返佣" min-width="190"><template #default="s">{{ s.row.order ? `${s.row.order.feeAmount ?? '缺失'} ${s.row.order.feeCurrency || ''} / ${s.row.order.rebateAmount ?? '缺失'} ${s.row.order.rebateCurrency || ''}` : '-' }}</template></el-table-column>
              <el-table-column prop="clientOrderId" label="客户端订单" min-width="220" show-overflow-tooltip />
            </el-table>
          </template>
        </el-tab-pane>
        <el-tab-pane label="策略收益与成本" name="performance">
          <el-alert v-if="performanceError" :title="performanceError" type="warning" :closable="false" />
          <template v-if="performance">
            <p class="evidence-note">持仓仅归属本会话，不继承旧会话或其他策略的资产。可预约卖出数量扣除 BTC 费用及活动卖单；费用缺失时为零，实际下单仍需核对交易所可用余额。收益贡献 = 卖出收入 − 买入支出 + USDT 费用/返佣 + 扣除 BTC 费用后的持仓估值。当前订单累计成交记录不提供逐笔已实现收益拆分。</p>
            <el-descriptions :column="3" border>
              <el-descriptions-item label="净收益贡献">{{ performanceMoney(performance.netContribution) }} USDT</el-descriptions-item>
              <el-descriptions-item label="退出与结算状态">{{ settlementLabel(performance.settlementState) }}</el-descriptions-item>
              <el-descriptions-item label="全部退出后净收益">{{ performanceMoney(performance.closedPositionNetPnl) }} USDT</el-descriptions-item>
              <el-descriptions-item label="净交易现金流">{{ performanceMoney(performance.netCashFlow) }} USDT</el-descriptions-item>
              <el-descriptions-item label="剩余持仓估值">{{ performanceMoney(performance.markedPositionValue) }} USDT</el-descriptions-item>
              <el-descriptions-item label="会话净持仓 BTC">{{ performance.netPositionBtc ?? '数据不完整' }}</el-descriptions-item>
              <el-descriptions-item label="可预约卖出 BTC">{{ performance.sellableBtc ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="买入 / 卖出成交额">{{ money(performance.buyTurnover) }} / {{ money(performance.sellTurnover) }} USDT</el-descriptions-item>
              <el-descriptions-item label="已知费用与返佣折算">{{ performanceMoney(performance.knownSignedCostsUsdt) }} USDT（扣费负，返佣正）</el-descriptions-item>
              <el-descriptions-item label="估值价格">{{ performance.markPrice ?? '-' }} USDT</el-descriptions-item>
              <el-descriptions-item label="估值 K 线">{{ formatTime(performance.markCandleAt) }}</el-descriptions-item>
              <el-descriptions-item label="账本覆盖">{{ performance.orderCount }} 单 / {{ performance.filledOrderCount }} 单有成交 / {{ performance.activeOrderCount }} 单活动</el-descriptions-item>
            </el-descriptions>
            <p class="evidence-note">估值使用该会话最近已收盘 K 线价格，停止后的历史会话不刷新实时价格。费用按成交均价折算，估值未计入未来平仓费用；活动订单后续成交会改变结果。缺少实际费用的 {{ performance.missingCostOrderCount }} 个成交订单不会按零费用计算。</p>
            <el-alert v-if="!performance.valuationComplete" title="数据不完整，净收益暂不可计算；请核对成交、手续费及估值证据。" type="warning" :closable="false" />
            <p class="evidence-note">全部退出后净收益仅在有成交、实际费用完整、无活动订单且会话净持仓为零时计算，不包含未退出持仓估值。费用补查仅查询交易所并更新该订单账本；不会下单、撤单或启动策略。当前凭据未配置时不可补查。</p>
            <el-button v-hasPermi="['quant:backtest:create']" type="warning" :disabled="loading || !!repairingOrderId || !!refreshError || !!performanceError || activeSession?.status === 'RUNNING' || !activePolicy?.selectedAccount || !capabilities?.liveExecutionEnabled || !performance.costsComplete || !(Number(performance.sellableBtc)>0)" @click="exitCurrentInventory">退出此会话持仓</el-button>
            <p class="evidence-note">退出使用原会话净库存及实时买价限价，保留原账本归属；可能部分成交或留下精度尾差。提交成功不代表已清仓，需刷新核对成交和费用。</p>
            <el-table :data="performance.orders" max-height="420" row-key="id">
              <el-table-column prop="clientOrderId" label="客户端订单 ID" min-width="230" />
              <el-table-column prop="side" label="方向" width="80" />
              <el-table-column prop="status" label="状态" width="130" />
              <el-table-column prop="filledAmount" label="累计成交 BTC" width="150" />
              <el-table-column prop="averagePrice" label="成交均价" width="130" />
              <el-table-column label="实际费用" min-width="150"><template #default="s">{{ s.row.feeAmount ?? '缺失' }} {{ s.row.feeCurrency }}</template></el-table-column>
              <el-table-column label="返佣" min-width="150"><template #default="s">{{ s.row.rebateAmount ?? '缺失' }} {{ s.row.rebateCurrency }}</template></el-table-column>
              <el-table-column label="费用证据" width="145"><template #default="s">
                <el-button v-if="needsCostRepair(s.row)" v-hasPermi="['quant:backtest:create']" link type="primary"
                  :loading="repairingOrderId === s.row.id" :disabled="!costRepairAvailable || loading || !!repairingOrderId || !!refreshError || !!performanceError"
                  @click="repairOrderCosts(s.row)">补查实际费用</el-button>
                <span v-else>{{ s.row.costEvidenceComplete === false ? '证据待核对' : '无需补查' }}</span>
              </template></el-table-column>
            </el-table>
          </template>
        </el-tab-pane>
        <el-tab-pane label="权益与敞口" name="equity">
          <p class="evidence-note">最近 {{ reconciliations.length }} 条对账快照：{{ formatTime(reconciliations[reconciliations.length - 1]?.reconciledAt) }} 至 {{ formatTime(reconciliations[0]?.reconciledAt) }}。账户权益包含已有资产及价格变化，不等同于策略收益。</p>
          <Echart v-if="reconciliations.length" :key="activeSession.id" :options="equityOptions" height="300px" :not-merge="true" />
          <el-empty v-else description="暂无对账快照" />
        </el-tab-pane>
        <el-tab-pane :label="`信号 (${signals.length})`" name="signals">
          <el-table :data="signals" max-height="420" row-key="id">
            <el-table-column label="收盘 K 线" width="180"><template #default="s">{{ formatTime(s.row.candleAt) }}</template></el-table-column>
            <el-table-column prop="signalType" label="信号" width="80" />
            <el-table-column prop="status" label="执行结果" width="150" />
            <el-table-column prop="closePrice" label="收盘价" width="120" />
            <el-table-column prop="fastEma" label="快 EMA / 入场上沿" width="165" />
            <el-table-column prop="slowEma" label="慢 EMA / 退出下沿" width="165" />
            <el-table-column prop="message" label="执行说明" min-width="220" show-overflow-tooltip />
            <el-table-column prop="clientOrderId" label="客户端订单 ID" min-width="230" show-overflow-tooltip />
            <el-table-column prop="signalHash" label="证据摘要" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`对账 (${reconciliations.length})`" name="reconciliations">
          <el-table :data="reconciliations" max-height="420" row-key="id">
            <el-table-column label="时间" width="180"><template #default="s">{{ formatTime(s.row.reconciledAt) }}</template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="s"><el-tag :type="s.row.reconciliationStatus === 'PASSED' ? 'success' : 'danger'">{{ s.row.reconciliationStatus }}</el-tag></template></el-table-column>
            <el-table-column prop="totalEquity" label="权益 USDT" width="130" />
            <el-table-column prop="btcExposure" label="BTC 敞口 USDT" width="150" />
            <el-table-column prop="sessionLoss" label="会话亏损 USDT" width="150" />
            <el-table-column label="交易所 / 平台挂单" width="165"><template #default="s">{{ s.row.exchangeOpenOrders }} / {{ s.row.platformOpenOrders }}</template></el-table-column>
            <el-table-column prop="errorMessage" label="异常说明" min-width="180" show-overflow-tooltip />
            <el-table-column prop="evidenceHash" label="证据摘要" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`告警 (${alerts.length})`" name="alerts">
          <p class="evidence-note">自动执行故障可在所属账户、执行开关关闭且会话停止后复核。记录解决会保留故障与审计，不重启会话；复核证据两分钟有效。</p>
          <el-table :data="alerts" max-height="420" row-key="id">
            <el-table-column prop="alertType" label="类型" min-width="180" />
            <el-table-column label="状态" width="120"><template #default="s"><el-tag :type="s.row.status === 'OPEN' ? 'danger' : 'success'">{{ s.row.status }}</el-tag></template></el-table-column>
            <el-table-column prop="message" label="告警说明" min-width="260" />
            <el-table-column label="首次发生" width="180"><template #default="s">{{ formatTime(s.row.firstSeenAt) }}</template></el-table-column>
            <el-table-column label="最后发生" width="180"><template #default="s">{{ formatTime(s.row.lastSeenAt) }}</template></el-table-column>
            <el-table-column label="恢复时间" width="180"><template #default="s">{{ formatTime(s.row.resolvedAt) }}</template></el-table-column>
            <el-table-column label="处置" width="140"><template #default="s"><el-button v-if="s.row.status === 'OPEN' && s.row.alertType === 'AUTOMATION_FAILURE'" link type="primary" :disabled="loading || !!repairingOrderId || !activeSession?.selectedAccount || activeSession?.status === 'RUNNING' || capabilities?.executionEnabled || capabilities?.liveExecutionEnabled || capabilities?.liveAutomationEnabled" @click="reviewLiveAlert(s.row.id)">只读复核</el-button></template></el-table-column>
          </el-table>
          <h3>处置审计（最近100条）</h3>
          <el-table :data="alertActions" max-height="300"><el-table-column label="动作" width="110"><template #default="s">{{ s.row.actionType === 'CHECK' ? '只读复核' : '记录解决' }}</template></el-table-column><el-table-column prop="actorId" label="操作人" width="90" /><el-table-column prop="comment" label="处置说明" min-width="260" /><el-table-column prop="evidenceHash" label="证据摘要" min-width="180" show-overflow-tooltip /><el-table-column label="时间" width="180"><template #default="s">{{ formatTime(s.row.createdAt) }}</template></el-table-column></el-table>
        </el-tab-pane>
      </el-tabs>
      <el-dialog v-model="recoveryVisible" title="自动执行故障处置" width="760px">
        <template v-if="recoveryProof"><el-alert title="记录解决仅表示已完成当前安全复核，不保证历史根因已消除，也不授权或重启交易。" type="info" :closable="false" /><p>账户 {{ recoveryProof.accountId }} · 有效至 {{ formatTime(recoveryProof.expiresAt) }}</p><el-table :data="recoveryProof.checks"><el-table-column prop="id" label="检查项" min-width="190" /><el-table-column label="结果" width="70"><template #default="s">{{ s.row.passed ? '通过' : '未通过' }}</template></el-table-column><el-table-column prop="evidence" label="证据" min-width="280" /></el-table><el-input v-model="recoveryComment" class="mt-3" type="textarea" :maxlength="500" show-word-limit placeholder="填写排查结果、已采取措施及仍存在的限制" /><el-button class="mt-3" type="primary" :loading="!!repairingOrderId" :disabled="!recoveryProof.readyForResolution || !recoveryComment.trim() || loading" @click="recordLiveAlertResolution">记录解决</el-button></template>
      </el-dialog>
      <p class="evidence-note">信号及对账各显示最近 100 条，完整验收以数据库留存证据为准。</p>
    </ContentWrap>

    <ContentWrap title="策略版本（最近 100 条回测统计）">
      <el-table :data="strategyRows" v-loading="loading">
        <el-table-column prop="strategyName" label="策略" min-width="180" />
        <el-table-column label="配置" min-width="230"><template #default="s">{{ strategyConfigurationLabel(s.row.configuration) }}</template></el-table-column>
        <el-table-column prop="id" label="版本 ID" min-width="260" show-overflow-tooltip />
        <el-table-column label="源码摘要" min-width="180"><template #default="s"><code>{{ shortHash(s.row.sourceHash) }}</code></template></el-table-column>
        <el-table-column prop="backtests" label="回测数" width="90" />
        <el-table-column prop="successfulBacktests" label="成功" width="80" />
        <el-table-column label="最近回测" min-width="170"><template #default="s">{{ formatTime(s.row.latestBacktestAt) }}</template></el-table-column>
      </el-table>
    </ContentWrap>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="12">
        <ContentWrap title="参数集">
          <el-table :data="parameterRows" max-height="360">
            <el-table-column prop="id" label="参数集" min-width="220" show-overflow-tooltip />
            <el-table-column prop="startingBalance" label="初始资金" width="100" />
            <el-table-column prop="stakeAmount" label="单笔投入" width="100" />
            <el-table-column prop="fee" label="费率" width="90" />
            <el-table-column label="摘要" min-width="130"><template #default="s"><code>{{ shortHash(s.row.parametersHash) }}</code></template></el-table-column>
          </el-table>
        </ContentWrap>
      </el-col>
      <el-col :xs="24" :lg="12">
        <ContentWrap title="模拟盘执行">
          <el-table :data="paperExecutions" max-height="360">
            <el-table-column prop="id" label="执行 ID" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="120"><template #default="s"><el-tag :type="sessionTag(s.row.status)">{{ statusLabel(s.row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="创建时间" min-width="170"><template #default="s">{{ formatTime(s.row.created_at) }}</template></el-table-column>
          </el-table>
        </ContentWrap>
      </el-col>
    </el-row>

    <ContentWrap title="真实订单账本">
      <template #header>
        <el-switch v-model="sessionOrdersOnly" active-text="仅所选会话" />
      </template>
      <p class="evidence-note">{{ sessionOrdersOnly ? '显示完整会话账本，不受最近信号窗口限制；收益数据读取失败时不显示旧订单。' : '显示所选会话所属风险策略的最近订单，可能包含其他会话及人工测试订单。' }}</p>
      <el-table :data="displayedOrders" max-height="360">
        <el-table-column label="更新时间" width="180"><template #default="s">{{ formatTime(s.row.updatedAt) }}</template></el-table-column>
        <el-table-column prop="instrumentId" label="交易对" width="120" />
        <el-table-column label="方向" width="80"><template #default="s"><el-tag :type="s.row.side === 'BUY' ? 'success' : 'warning'">{{ s.row.side }}</el-tag></template></el-table-column>
        <el-table-column prop="status" label="状态" width="130" />
        <el-table-column prop="price" label="价格" />
        <el-table-column prop="amount" label="数量" />
        <el-table-column prop="filledAmount" label="已成交" />
        <el-table-column prop="notional" label="名义金额" />
        <el-table-column prop="clientOrderId" label="客户端订单" min-width="220" show-overflow-tooltip />
      </el-table>
    </ContentWrap>
  </div>
</template>

<script setup lang="ts">
import { exitOwnedSession } from '@/api/quant/portfolio'
import { getTradingAccount, type TradingAccountSummary } from '@/api/quant/exchanges'
import { strategyConfigurationLabel } from '@/api/quant/backtest'
import { getLivePerformance, refreshPerformanceOrder, type LivePerformance, type PerformanceOrder } from '@/api/quant/performance'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { Echart } from '@/components/Echart'
import type { EChartsOption } from 'echarts'
import {
  getCapabilities,
  getLiveAutomation,
  checkLiveAlert,
  resolveLiveAlert,
  listLiveAlertActions,
  type LiveRecoveryCheck,
  type LiveAlertAction,
  listBacktests,
  listLiveAutomations,
  listLiveControls,
  listLiveOrders,
  listPaperExecutions,
  listParameterSets,
  listStrategyVersions,
  stopLiveAutomation,
  type BacktestTask,
  type LiveAutomationSession,
  type LiveControlPolicy,
  type LiveExchangeOrder,
  type PaperExecution,
  type ParameterSet,
  type StrategyVersion
} from '@/api/quant/backtest'

type Capabilities = Awaited<ReturnType<typeof getCapabilities>>
type TagType = 'success' | 'warning' | 'info' | 'danger' | 'primary'

const router = useRouter()
const loading = ref(false)
const autoRefresh = ref(true)
const lastUpdated = ref<number>()
const capabilities = ref<Capabilities>()
const tradingAccount = ref<TradingAccountSummary>()
const viewAccountId = ref<string>()
const accountOptions = computed(() => [...new Map<string, { id: string; exchange: string }>([
  ...(tradingAccount.value ? [[tradingAccount.value.id, tradingAccount.value] as const] : []),
  ...policies.value.map(p => [p.accountId, { id: p.accountId, exchange: p.exchangeName }] as const)
]).values()])
const strategyVersions = ref<StrategyVersion[]>([])
const parameterSets = ref<ParameterSet[]>([])
const backtests = ref<BacktestTask[]>([])
const paperExecutions = ref<PaperExecution[]>([])
const policies = ref<LiveControlPolicy[]>([])
const sessions = ref<LiveAutomationSession[]>([])
const activeSession = ref<LiveAutomationSession>()
const alertActions = ref<LiveAlertAction[]>([])
const recoveryProof = ref<LiveRecoveryCheck>()
const recoveryVisible = ref(false)
const recoveryComment = ref('')
const liveOrders = ref<LiveExchangeOrder[]>([])
const performance = ref<LivePerformance>()
const traceOrdersOnly = ref(false)
const traceRows = computed(() => (performance.value?.executionTrace || []).filter(row => !traceOrdersOnly.value || !!row.order))
const performanceError = ref('')
const repairingOrderId = ref('')
const selectedSessionId = ref<string>()
const evidenceTab = ref('trace')
const sessionOrdersOnly = ref(true)
const refreshError = ref('')
let timer: number | undefined
let disposed = false

const signals = computed(() => activeSession.value?.signals || [])
const reconciliations = computed(() => activeSession.value?.reconciliations || [])
const alerts = computed(() => activeSession.value?.alerts || [])
const displayedOrders = computed(() => {
  if (!sessionOrdersOnly.value) return liveOrders.value
  return performance.value?.orders || []
})
const equityOptions = computed<EChartsOption>(() => {
  const points = [...reconciliations.value].sort((a, b) => a.reconciledAt - b.reconciledAt)
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['账户权益', 'BTC 敞口'] },
    grid: { left: 65, right: 65, top: 45, bottom: 70 },
    xAxis: { type: 'time' },
    yAxis: [
      { type: 'value', name: '权益 USDT', scale: true },
      { type: 'value', name: '敞口 USDT', scale: true }
    ],
    dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 5 }],
    series: [
      { name: '账户权益', type: 'line', showSymbol: false, data: points.map((p) => [p.reconciledAt, Number(p.totalEquity)]) },
      { name: 'BTC 敞口', type: 'line', yAxisIndex: 1, showSymbol: false, data: points.map((p) => [p.reconciledAt, Number(p.btcExposure)]) }
    ]
  }
})

const activePolicy = computed(() => policies.value.find((item) => item.id === activeSession.value?.policyId) || policies.value.find(item => item.accountId === viewAccountId.value))
const costRepairAvailable = computed(() => !!activePolicy.value?.selectedAccount && !!activePolicy.value.credentialProvider && activePolicy.value.credentialProvider !== 'UNCONFIGURED')
const latestReconciliation = computed(() => activeSession.value?.reconciliations?.[0])
const latestSignal = computed(() => activeSession.value?.signals?.[0])
const openAlerts = computed(() => activeSession.value?.alerts?.filter((item) => item.status === 'OPEN').length || 0)
const equityChange = computed(() => Number(activeSession.value?.lastEquity || 0) - Number(activeSession.value?.startEquity || 0))
const runningCount = computed(() => sessions.value.filter((item) => item.status === 'RUNNING').length + paperExecutions.value.filter((item) => item.status === 'RUNNING').length)
const strategyCount = computed(() => new Set(strategyVersions.value.map((item) => item.strategyId)).size)
const switchSummary = computed(() => {
  if (!capabilities.value?.enabled) return '量化模块未启用。'
  if (capabilities.value.liveExecutionEnabled && capabilities.value.liveAutomationEnabled) return '真实执行与自动策略开关已开启；请持续关注会话、挂单和告警。'
  return '量化模块已启用，真实执行或自动策略开关当前关闭。'
})
const strategyRows = computed(() => strategyVersions.value.map((version) => {
  const related = backtests.value.filter((task) => task.strategyVersionId === version.id)
  return {
    ...version,
    backtests: related.length,
    successfulBacktests: related.filter((task) => task.status === 'SUCCEEDED').length,
    latestBacktestAt: related.sort((a, b) => b.createdAt - a.createdAt)[0]?.createdAt
  }
}))
const parameterRows = computed(() => parameterSets.value.map((item) => {
  try { return { ...item, ...JSON.parse(item.parametersJson) } }
  catch { return { ...item, startingBalance: '-', stakeAmount: '-', fee: '-' } }
}))

function reasonLabel(reason:string) {
  return ({ STOP_LOSS:'止损阈值', TAKE_PROFIT:'止盈阈值', EMA_CROSS:'EMA 交叉', NO_CROSS:'无交叉', CHANNEL_ENTRY:'通道突破买入', CHANNEL_EXIT:'通道跌破退出', NO_BREAKOUT:'未突破通道', HOLDING_POSITION:'持仓中不追加买入', UNRECORDED:'历史未记录' } as Record<string,string>)[reason] || reason
}
function exportExecutionReview() {
  if (!performance.value || refreshError.value || performanceError.value || repairingOrderId.value) return
  const blob = new Blob([JSON.stringify(performance.value, null, 2)], { type:'application/json' })
  const url=URL.createObjectURL(blob), link=document.createElement('a')
  link.href=url; link.download=`execution-review-${performance.value.sessionId}.json`; link.click(); URL.revokeObjectURL(url)
}
function performanceMoney(value?: number | null) { return value == null ? '-' : Number(value).toFixed(6) }
function settlementLabel(state?: string) {
  return ({ INCOMPLETE_EVIDENCE:'成交或费用证据不完整', ACTIVE_ORDERS:'仍有活动订单', NO_FILLS:'无成交', OPEN_POSITION:'尚有持仓，收益含估值', CLOSED_POSITION:'净持仓为零，账本已结算' } as Record<string,string>)[state || ''] || '旧服务未提供结算状态'
}
function needsCostRepair(order: PerformanceOrder) {
  return ['FILLED','CANCELED'].includes(order.status) && Number(order.filledAmount) > 0 &&
    (order.costEvidenceComplete === false || order.feeAmount == null || order.rebateAmount == null)
}
const exitRequests=new Map<string,string>()
async function exitCurrentInventory(){const sessionId=performance.value?.sessionId;if(!sessionId||!activePolicy.value?.selectedAccount||loading.value||repairingOrderId.value)return;
  try{await ElMessageBox.confirm('将以真实卖单退出此会话可用持仓，并保留原归属。提交后请核对成交状态及尾差。','退出原会话持仓',{type:'warning'})}catch{return}
  const requestId=exitRequests.get(sessionId)||crypto.randomUUID();exitRequests.set(sessionId,requestId);repairingOrderId.value='exit';
  try{const order=await exitOwnedSession(sessionId,requestId);ElMessage.success(`退出订单状态：${order.status}，请核对成交与费用`);if(['FILLED','CANCELED','FAILED'].includes(order.status))exitRequests.delete(sessionId)}catch{ElMessage.error('退出未确认，请核对挂单和账本；本次请求编号已保留，重试不会重复发单')}finally{repairingOrderId.value='';await refresh()}
}
async function repairOrderCosts(order: PerformanceOrder) {
  if (!needsCostRepair(order) || !costRepairAvailable.value || loading.value || repairingOrderId.value || refreshError.value || performanceError.value) return
  const sessionId = performance.value?.sessionId
  repairingOrderId.value = order.id
  try {
    await refreshPerformanceOrder(order.id)
    const reviewed = sessionId ? await getLivePerformance(sessionId) : undefined
    if (!disposed && performance.value?.sessionId === sessionId && reviewed) {
      performance.value = reviewed
      const repaired = reviewed.orders.find(item => item.id === order.id)
      if (repaired?.costEvidenceComplete === true) ElMessage.success('实际费用已补齐，收益复盘已更新')
      else ElMessage.warning('订单已查询，费用证据仍不完整，请核对交易所记录')
    }
  } catch {
    ElMessage.error('补查或收益复盘更新失败，请刷新核对；未重新下单')
  } finally { repairingOrderId.value = '' }
}
async function refresh() {
  if (loading.value || repairingOrderId.value) return
  loading.value = true
  try {
    const [caps, versions, params, tasks, paper, controls, account] = await Promise.all([
      getCapabilities(), listStrategyVersions(), listParameterSets(), listBacktests(), listPaperExecutions(), listLiveControls(), getTradingAccount()
    ])
    const groups = await Promise.all(controls.map((policy) => listLiveAutomations(policy.id)))
    const accountId = viewAccountId.value || account.id
    const availableSessions = groups.flat().filter(session => session.accountId === accountId).sort((a, b) => b.startedAt - a.startedAt)
    const selected = availableSessions.find((item) => item.id === selectedSessionId.value)
      || availableSessions.find((item) => item.status === 'RUNNING') || availableSessions[0]
    const detail = selected ? await getLiveAutomation(selected.id) : undefined
    const actions = selected ? await listLiveAlertActions(selected.id) : []
    let attribution: LivePerformance | undefined
    let attributionError = ''
    if (selected) {
      try { attribution = await getLivePerformance(selected.id) }
      catch { attributionError = '收益数据读取失败；请确认新增接口已部署，其他运行证据仍可查看。' }
    }
    const policyId = detail?.policyId || controls.find(policy => policy.accountId === accountId)?.id
    const orders = policyId ? await listLiveOrders(policyId) : []
    if (disposed) return
    capabilities.value = caps; strategyVersions.value = versions; parameterSets.value = params
    tradingAccount.value = account; viewAccountId.value = accountId
    backtests.value = tasks; paperExecutions.value = paper; policies.value = controls
    sessions.value = availableSessions
    selectedSessionId.value = selected?.id
    activeSession.value = detail
    alertActions.value = actions
    if (recoveryProof.value && recoveryProof.value.sessionId !== detail?.id) { recoveryVisible.value = false; recoveryProof.value = undefined }
    performance.value = attribution
    performanceError.value = attributionError
    liveOrders.value = orders
    refreshError.value = ''
    lastUpdated.value = Date.now()
  } catch (error) {
    console.error(error)
    selectedSessionId.value = activeSession.value?.id
    refreshError.value = '刷新失败，当前显示上次成功获取的数据，请核对更新时间后重试。'
  } finally { loading.value = false }
}

function changeAccount() { selectedSessionId.value = undefined; refresh() }

async function reviewLiveAlert(alertId:string) {
  if (!activeSession.value?.selectedAccount || loading.value || repairingOrderId.value) return
  repairingOrderId.value = `alert:${alertId}`
  try { recoveryProof.value = await checkLiveAlert(alertId); recoveryComment.value = ''; recoveryVisible.value = true }
  finally { repairingOrderId.value = ''; await refresh() }
}
async function recordLiveAlertResolution() {
  const proof = recoveryProof.value
  if (!proof || !recoveryComment.value.trim() || repairingOrderId.value || proof.sessionId !== activeSession.value?.id) return
  if (Date.now() > proof.expiresAt) { ElMessage.warning('复核已过期，请重新复核'); return }
  repairingOrderId.value = `alert:${proof.alertId}`
  try { await resolveLiveAlert(proof.alertId,{checkId:proof.checkId,evidenceHash:proof.evidenceHash,comment:recoveryComment.value.trim()}); recoveryVisible.value = false; recoveryProof.value = undefined; ElMessage.success('解决记录及审计已保存，会话保持停止') }
  finally { repairingOrderId.value = ''; await refresh() }
}

async function stopCurrentSession() {
  const session = activeSession.value
  if (!session || !session.selectedAccount || session.status !== 'RUNNING') return
  try {
    const { value } = await ElMessageBox.prompt(`会话 ${session.id}：请输入停止原因`, '停止自动实盘会话', {
      confirmButtonText: '确认停止', cancelButtonText: '取消', type: 'warning', inputPattern: /\S+/, inputErrorMessage: '停止原因不能为空'
    })
    await stopLiveAutomation(session.id, value.trim())
    ElMessage.success('自动实盘会话已停止')
    await refresh()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('停止失败，请刷新核对会话状态')
  }
}

function openResearch() { router.push('/quant/backtest') }
function shortHash(value?: string) { return value ? `${value.slice(0, 12)}…${value.slice(-6)}` : '-' }
function money(value?: number) { return value === undefined || value === null ? '-' : Number(value).toFixed(4) }
function signedMoney(value: number) { return `${value >= 0 ? '+' : ''}${value.toFixed(4)}` }
function formatTime(value?: number) { return value ? new Date(value).toLocaleString() : '-' }
function statusLabel(value?: string) { return value || '暂无' }
function sessionTag(value?: string): TagType {
  if (value === 'RUNNING' || value === 'SUCCEEDED') return 'success'
  if (value === 'RISK_STOPPED' || value === 'FAILED') return 'danger'
  if (value === 'STARTING' || value === 'STOP_REQUESTED') return 'warning'
  return 'info'
}

watch(autoRefresh, (enabled) => {
  if (timer) window.clearInterval(timer)
  timer = enabled ? window.setInterval(refresh, 15000) : undefined
})
onMounted(() => { refresh(); timer = window.setInterval(refresh, 15000) })
onBeforeUnmount(() => { disposed = true; if (timer) window.clearInterval(timer) })
</script>

<style scoped>
.session-picker { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.session-picker .el-select { width: 440px; max-width: 100%; }
.evidence-note { color: var(--muted); font-size: 13px; margin: 8px 0 14px; }
.operations-page { --ink: #172033; --muted: #64748b; }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 18px; }
.page-head h1 { margin: 3px 0 6px; color: var(--ink); font-size: 28px; }
.page-head p, .updated { margin: 0; color: var(--muted); }
.eyebrow { color: #2563eb; font-size: 12px; font-weight: 700; letter-spacing: .16em; }
.head-actions, .section-head, .gate-state { display: flex; align-items: center; gap: 12px; }
.section-head { justify-content: space-between; width: 100%; }
.head-actions { flex-wrap: wrap; justify-content: flex-end; }
.metric-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
.metric-card { min-height: 104px; padding: 18px; border: 1px solid #e7ebf2; border-radius: 12px; background: linear-gradient(145deg, #fff, #f7f9fc); }
.metric-card span, .metric-card small { display: block; color: var(--muted); }
.metric-card strong { display: block; margin: 8px 0 4px; color: var(--ink); font-size: 26px; line-height: 1; }
.metric-card.danger { border-color: #fecaca; background: #fff7f7; }
.session-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
.session-strip div { padding: 12px; border-radius: 9px; background: #f6f8fb; }
.session-strip span { display: block; margin-bottom: 5px; color: var(--muted); font-size: 12px; }
.session-strip b { color: var(--ink); font-size: 14px; }
.gate-state { justify-content: space-between; color: var(--muted); }
:deep(.positive) { color: #15803d; font-weight: 600; }
:deep(.negative) { color: #dc2626; font-weight: 600; }
code { color: #334155; font-size: 12px; }
@media (max-width: 1200px) { .metric-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) {
  .page-head { align-items: flex-start; flex-direction: column; }
  .head-actions { justify-content: flex-start; }
  .metric-grid, .session-strip { grid-template-columns: 1fr 1fr; }
}
</style>
