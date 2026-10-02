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
      <div class="metric-grid">
        <div class="metric-card"><span>策略版本</span><strong>{{ strategyVersions.length }}</strong><small>{{ strategyCount }} 个策略</small></div>
        <div class="metric-card"><span>运行环境</span><strong>{{ runningCount }}</strong><small>实盘与模拟盘</small></div>
        <div class="metric-card"><span>账户权益</span><strong>{{ money(latestReconciliation?.totalEquity) }}</strong><small>USDT</small></div>
        <div class="metric-card"><span>BTC 敞口</span><strong>{{ money(latestReconciliation?.btcExposure) }}</strong><small>USDT</small></div>
        <div class="metric-card" :class="{ danger: openAlerts > 0 }"><span>未处理告警</span><strong>{{ openAlerts }}</strong><small>{{ openAlerts ? '需要处理' : '当前正常' }}</small></div>
      </div>
    </ContentWrap>

    <el-row :gutter="16">
      <el-col :xs="24" :xl="16">
        <ContentWrap title="当前自动实盘会话">
          <template #header>
            <div class="section-head">
              <span>当前自动实盘会话</span>
              <el-tag :type="sessionTag(activeSession?.status)">{{ statusLabel(activeSession?.status) }}</el-tag>
            </div>
          </template>
          <el-empty v-if="!activeSession" description="暂无自动实盘会话" />
          <template v-else>
            <el-descriptions :column="3" border>
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

    <ContentWrap title="策略版本">
      <el-table :data="strategyRows" v-loading="loading">
        <el-table-column prop="strategyName" label="策略" min-width="180" />
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
      <el-table :data="liveOrders" max-height="360">
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
let timer: number | undefined

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
    capabilities.value = caps; strategyVersions.value = versions; parameterSets.value = params
    backtests.value = tasks; paperExecutions.value = paper; policies.value = controls
    const groups = await Promise.all(controls.map((policy) => listLiveAutomations(policy.id)))
    sessions.value = groups.flat().sort((a, b) => b.startedAt - a.startedAt)
    const selected = sessions.value.find((item) => item.status === 'RUNNING') || sessions.value[0]
    activeSession.value = selected ? await getLiveAutomation(selected.id) : undefined
    const policyId = activeSession.value?.policyId || controls[0]?.id
    liveOrders.value = policyId ? await listLiveOrders(policyId) : []
    lastUpdated.value = Date.now()
  } catch (error) {
    console.error(error)
    ElMessage.error('运行面板刷新失败')
  } finally { loading.value = false }
}

async function stopCurrentSession() {
  if (!activeSession.value) return
  const { value } = await ElMessageBox.prompt('请输入停止原因', '停止自动实盘会话', {
    confirmButtonText: '确认停止', cancelButtonText: '取消', type: 'warning', inputPattern: /\S+/, inputErrorMessage: '停止原因不能为空'
  })
  await stopLiveAutomation(activeSession.value.id, value.trim())
  ElMessage.success('自动实盘会话已停止')
  await refresh()
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
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style scoped>
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
