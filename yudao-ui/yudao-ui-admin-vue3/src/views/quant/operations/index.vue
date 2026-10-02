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
      <div class="session-picker mt-3">
        <span>查看会话</span>
        <el-select v-model="selectedSessionId" :disabled="loading" placeholder="暂无实盘会话" @change="refresh">
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
            <el-table-column prop="fastEma" label="EMA20" width="130" />
            <el-table-column prop="slowEma" label="EMA60" width="130" />
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
          <el-table :data="alerts" max-height="420" row-key="id">
            <el-table-column prop="alertType" label="类型" min-width="180" />
            <el-table-column label="状态" width="120"><template #default="s"><el-tag :type="s.row.status === 'OPEN' ? 'danger' : 'success'">{{ s.row.status }}</el-tag></template></el-table-column>
            <el-table-column prop="message" label="告警说明" min-width="260" />
            <el-table-column label="首次发生" width="180"><template #default="s">{{ formatTime(s.row.firstSeenAt) }}</template></el-table-column>
            <el-table-column label="最后发生" width="180"><template #default="s">{{ formatTime(s.row.lastSeenAt) }}</template></el-table-column>
            <el-table-column label="恢复时间" width="180"><template #default="s">{{ formatTime(s.row.resolvedAt) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <p class="evidence-note">信号及对账各显示最近 100 条，完整验收以数据库留存证据为准。</p>
    </ContentWrap>

    <ContentWrap title="策略版本（最近 100 条回测统计）">
      <el-table :data="strategyRows" v-loading="loading">
        <el-table-column prop="strategyName" label="策略" min-width="180" />
        <el-table-column label="配置" min-width="230"><template #default="s">{{ s.row.configuration ? `EMA${s.row.configuration.fastPeriod}/${s.row.configuration.slowPeriod} · 止损 ${Number(s.row.configuration.stopLossRatio * 100).toFixed(2)}% · 止盈 ${Number(s.row.configuration.takeProfitRatio * 100).toFixed(2)}%` : '配置待加载' }}</template></el-table-column>
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
      <p class="evidence-note">{{ sessionOrdersOnly ? '按所选会话信号关联的客户端订单 ID 筛选。' : '显示所选会话所属风险策略的最近订单，可能包含其他会话及人工测试订单。' }}</p>
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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { Echart } from '@/components/Echart'
import type { EChartsOption } from 'echarts'
import {
  getCapabilities,
  getLiveAutomation,
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
const strategyVersions = ref<StrategyVersion[]>([])
const parameterSets = ref<ParameterSet[]>([])
const backtests = ref<BacktestTask[]>([])
const paperExecutions = ref<PaperExecution[]>([])
const policies = ref<LiveControlPolicy[]>([])
const sessions = ref<LiveAutomationSession[]>([])
const activeSession = ref<LiveAutomationSession>()
const liveOrders = ref<LiveExchangeOrder[]>([])
const selectedSessionId = ref<string>()
const evidenceTab = ref('equity')
const sessionOrdersOnly = ref(true)
const refreshError = ref('')
let timer: number | undefined
let disposed = false

const signals = computed(() => activeSession.value?.signals || [])
const reconciliations = computed(() => activeSession.value?.reconciliations || [])
const alerts = computed(() => activeSession.value?.alerts || [])
const displayedOrders = computed(() => {
  if (!sessionOrdersOnly.value) return liveOrders.value
  const clients = new Set(signals.value.map((signal) => signal.clientOrderId).filter(Boolean))
  return liveOrders.value.filter((order) => clients.has(order.clientOrderId))
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

const activePolicy = computed(() => policies.value.find((item) => item.id === activeSession.value?.policyId) || policies.value[0])
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

async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    const [caps, versions, params, tasks, paper, controls] = await Promise.all([
      getCapabilities(), listStrategyVersions(), listParameterSets(), listBacktests(), listPaperExecutions(), listLiveControls()
    ])
    const groups = await Promise.all(controls.map((policy) => listLiveAutomations(policy.id)))
    const availableSessions = groups.flat().sort((a, b) => b.startedAt - a.startedAt)
    const selected = availableSessions.find((item) => item.id === selectedSessionId.value)
      || availableSessions.find((item) => item.status === 'RUNNING') || availableSessions[0]
    const detail = selected ? await getLiveAutomation(selected.id) : undefined
    const policyId = detail?.policyId || controls[0]?.id
    const orders = policyId ? await listLiveOrders(policyId) : []
    if (disposed) return
    capabilities.value = caps; strategyVersions.value = versions; parameterSets.value = params
    backtests.value = tasks; paperExecutions.value = paper; policies.value = controls
    sessions.value = availableSessions
    selectedSessionId.value = selected?.id
    activeSession.value = detail
    liveOrders.value = orders
    refreshError.value = ''
    lastUpdated.value = Date.now()
  } catch (error) {
    console.error(error)
    selectedSessionId.value = activeSession.value?.id
    refreshError.value = '刷新失败，当前显示上次成功获取的数据，请核对更新时间后重试。'
  } finally { loading.value = false }
}

async function stopCurrentSession() {
  const session = activeSession.value
  if (!session || session.status !== 'RUNNING') return
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
