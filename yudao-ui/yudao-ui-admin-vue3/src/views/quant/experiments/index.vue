<template>
  <ContentWrap title="策略实验工作台">
    <p>用相同资金、费率和数据比较候选方案，再进入评审、模拟盘及交易运行。收益、回撤和执行表现共同决定后续选择。</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
    <el-form label-width="110px" class="mt-4" @submit.prevent="submit">
      <el-form-item label="候选方案">
        <el-select v-model="form.strategyVersionIds" multiple class="w-100%" :multiple-limit="5" placeholder="选择 2 至 5 个策略版本">
          <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`${label(version.configuration)} · ${version.sourceHash.slice(0, 10)}`" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button v-hasPermi="['quant:backtest:create']" :loading="presetLoading" @click="addPresets">添加三组 EMA 研究预设</el-button><el-button @click="router.push('/quant/backtest')">自定义策略配置</el-button></el-form-item>
      <el-form-item label="资金与费率">
        <el-select v-model="form.parameterSetId" class="w-100%" placeholder="所有方案共用一个资金参数集"><el-option v-for="p in parameters" :key="p.id" :value="p.id" :label="parameterLabel(p)" /></el-select>
      </el-form-item>
      <el-form-item label="行情数据"><el-select v-model="form.datasetId" class="w-100%"><el-option v-for="d in datasets.filter(d => d.status === 'VALID')" :key="d.id" :value="d.id" :label="`${d.id} · ${d.exchange} · ${d.candles} 根`" /></el-select></el-form-item>
      <el-form-item label="训练区间"><el-date-picker v-model="trainDates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="UTC 开始日" end-placeholder="切分日（不含）" /></el-form-item>
      <el-form-item label="验证结束"><el-date-picker v-model="form.validationEnd" value-format="YYYY-MM-DD" placeholder="从训练切分日开始验证，结束日不含" /></el-form-item>
      <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="submitting" :disabled="!enabled" @click="submit">提交多方案实验</el-button><span class="ml-3">每个方案生成训练、验证各一次回测，两段各至少 7 天。</span></el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap title="我的实验（最近 50 组）">
    <el-button :loading="loading" @click="refresh">刷新</el-button>
    <el-table :data="experiments" class="mt-3">
      <el-table-column prop="datasetId" label="数据集" min-width="160" />
      <el-table-column label="训练区间" min-width="220"><template #default="s">{{ s.row.trainStart }} ～ {{ s.row.splitDate }}</template></el-table-column>
      <el-table-column label="验证区间" min-width="220"><template #default="s">{{ s.row.splitDate }} ～ {{ s.row.validationEnd }}</template></el-table-column>
      <el-table-column label="创建时间" min-width="180"><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="110"><template #default="s"><el-button link type="primary" @click="selectExperiment(s.row.id)">查看进度</el-button></template></el-table-column>
    </el-table>
  </ContentWrap>

  <ContentWrap v-if="result" title="比较结果与下一动作">
    <p>实验 {{ result.id }} · {{ parameterSummary }} · {{ result.terminal ? '全部回测结束，按验证收益排序' : '回测执行中，暂不排名' }}</p>
    <Echart v-if="chartRows.length" :options="comparisonOptions" :not-merge="true" height="300px" />
    <el-table :data="result.rows" row-key="strategyVersionId">
      <el-table-column prop="rank" label="排名" width="65" />
      <el-table-column label="方案" min-width="245"><template #default="s">{{ label(s.row.configuration) }}</template></el-table-column>
      <el-table-column label="训练 / 验证" width="205"><template #default="s">{{ s.row.trainStatus }} / {{ s.row.validationStatus }}</template></el-table-column>
      <el-table-column label="训练收益" width="105"><template #default="s">{{ percent(s.row.trainReturn) }}</template></el-table-column>
      <el-table-column label="验证收益" width="105"><template #default="s">{{ percent(s.row.validationReturn) }}</template></el-table-column>
      <el-table-column label="验证回撤" width="105"><template #default="s">{{ percent(s.row.validationDrawdown) }}</template></el-table-column>
      <el-table-column label="收益差" width="100"><template #default="s">{{ percent(s.row.overfitGap) }}</template></el-table-column>
      <el-table-column prop="validationTrades" label="验证成交" width="95" />
      <el-table-column label="当前阶段" min-width="160"><template #default="s">{{ stage(s.row) }}</template></el-table-column>
      <el-table-column label="操作" width="125"><template #default="s"><el-button link type="primary" @click="openReview(s.row)">评审与模拟盘</el-button></template></el-table-column>
    </el-table>
    <p class="text-gray-500">历史排名用于筛选候选方案，需结合样本外表现与模拟运行。此处的收益率已计入所选费率，不能等同于未来实盘收益。</p>
  </ContentWrap>

  <ContentWrap v-if="result" title="费用与滑点敏感性">
    <p>固定原成交序列，估算双边费用和不利滑点。1 基点 = 0.01%；费用按每边成交金额计入。</p>
    <el-form :inline="true" @submit.prevent="loadCosts">
      <el-form-item label="单边手续费（基点）"><el-input-number v-model="costAssumptions.feeBps" :min="0" :max="100" :precision="0" /></el-form-item>
      <el-form-item label="每边滑点（基点）"><el-input-number v-model="costAssumptions.slippageBps" :min="0" :max="100" :precision="0" /></el-form-item>
      <el-button :loading="costLoading" @click="loadCosts">计算比较</el-button>
      <el-button :disabled="!costs" @click="exportCosts">导出证据 JSON</el-button>
    </el-form>
    <el-alert v-if="costError" :title="costError" type="warning" :closable="false" />
    <template v-if="costs">
      <p>本次假设：手续费 {{ costs.feeBps }} / 滑点 {{ costs.slippageBps }} 基点 · 初始 {{ costs.startingBalance }} USDT</p>
      <el-table :data="costs.rows">
        <el-table-column label="方案" min-width="230"><template #default="s">{{ label(result.rows.find(r => r.strategyVersionId === s.row.strategyVersionId)?.configuration) }}</template></el-table-column>
        <el-table-column label="原净收益"><template #default="s">{{ money(s.row.baseNetProfit) }}</template></el-table-column>
        <el-table-column label="调整后净收益"><template #default="s">{{ money(s.row.adjustedNetProfit) }}</template></el-table-column>
        <el-table-column label="调整后收益率"><template #default="s">{{ percent(s.row.adjustedReturn) }}</template></el-table-column>
        <el-table-column label="估算手续费"><template #default="s">{{ money(s.row.estimatedFees) }}</template></el-table-column>
        <el-table-column label="估算滑点成本"><template #default="s">{{ money(s.row.estimatedSlippageCost) }}</template></el-table-column>
        <el-table-column label="已平仓权益回撤"><template #default="s">{{ percent(s.row.realizedDrawdown) }}</template></el-table-column>
        <el-table-column label="证据"><template #default="s">{{ s.row.available ? `${s.row.tradeCount} 笔` : (s.row.status === 'SUCCEEDED' ? '旧结果缺少成交金额或不支持该账本，请重新回测' : s.row.status) }}</template></el-table-column>
      </el-table>
    </template>
    <p>已平仓权益回撤只在退出时计算，与原回测逐 K 线回撤口径不同。费用变化可能改变入场、退出及资金可用性，此处不重跑信号或撮合，也不修改原排名、审批和运行参数。</p>
  </ContentWrap>

  <ContentWrap v-if="result" title="候选方案模拟运行与复盘">
    <el-alert v-if="paperReviewError" title="模拟复盘读取失败，请确认新增接口已部署后重试。" type="warning" :closable="false" />
    <template v-if="paperReview">
      <p>共同初始资金 {{ paperReview.startingBalance }} USDT · 数据生成 {{ time(paperReview.generatedAt) }}</p>
      <p class="text-gray-500">每个方案展示最新模拟会话的最新执行；观测起止和保留快照数分别列出。运行时长、市场区间及成交样本可能不同，暂不自动排名或选用。收益仅为引擎报告的已平仓收益，未包含当前持仓浮盈亏；零成交不能代表方案有效。</p>
      <el-button @click="exportPaperReview">导出复盘 JSON</el-button>
      <el-button @click="router.push('/quant/backtest')">进入模拟审批与运行</el-button>
      <el-table :data="paperReview.rows" class="mt-3" row-key="strategyVersionId">
        <el-table-column label="方案" min-width="250"><template #default="s">{{ label(s.row.configuration) }}</template></el-table-column>
        <el-table-column label="运行阶段" min-width="150"><template #default="s">{{ s.row.executionStatus || s.row.sessionStatus || '尚未准备模拟盘' }}</template></el-table-column>
        <el-table-column label="已平仓收益 USDT" width="155"><template #default="s">{{ s.row.realizedProfit == null ? '-' : Number(s.row.realizedProfit).toFixed(6) }}</template></el-table-column>
        <el-table-column label="相对初始资金" width="130"><template #default="s">{{ percent(s.row.realizedReturnRatio) }}</template></el-table-column>
        <el-table-column label="成交 / 持仓 / 挂单" width="175"><template #default="s">{{ s.row.closedTrades ?? '-' }} / {{ s.row.openPositions ?? '-' }} / {{ s.row.openOrders ?? '-' }}</template></el-table-column>
        <el-table-column prop="snapshotCount" label="保留快照" width="100" />
        <el-table-column label="观测起止" min-width="210"><template #default="s">{{ s.row.firstObservedAt ? time(s.row.firstObservedAt) : '-' }}<br />{{ s.row.lastObservedAt ? time(s.row.lastObservedAt) : '-' }}</template></el-table-column>
        <el-table-column label="对账 / 未知订单" width="160"><template #default="s">{{ s.row.reconciliationStatus || '-' }} / {{ s.row.unknownOrders ?? '-' }}</template></el-table-column>
        <el-table-column label="未解决告警" width="105"><template #default="s">{{ s.row.executionId ? s.row.unresolvedAlerts : '-' }}</template></el-table-column>
        <el-table-column label="样本情况" width="155"><template #default="s">{{ s.row.sampleState === 'NO_READABLE_TELEMETRY' ? '无可读观测' : s.row.sampleState === 'NO_CLOSED_TRADES' ? '尚无已平仓成交' : '有成交，待复盘' }}</template></el-table-column>
      </el-table>
      <p class="text-gray-500">这是保留快照的历史摘要，不调用交易所或启动容器。历史执行停止后数据不会继续更新；快照保留清理可能缩短可见区间。未读取到遥测时收益显示为空，不按零收益填补。</p>
    </template>
  </ContentWrap>

  <el-dialog v-model="reviewVisible" title="评审与模拟盘准备" width="80%">
    <template v-if="activeBatch && activeRow">
      <p>{{ label(activeRow.configuration) }} · 版本 {{ activeRow.strategyVersionId }}</p>
      <p>{{ activeBatch.researchDraft.conclusion }}</p>
      <el-alert v-for="risk in activeBatch.researchDraft.risks" :key="risk" :title="risk" type="warning" :closable="false" class="mt-2" />
      <el-table :data="activeBatch.paperAdmission.checks" class="mt-3"><el-table-column prop="label" label="模拟盘条件" /><el-table-column label="结果" width="80"><template #default="s">{{ s.row.passed ? '通过' : '待满足' }}</template></el-table-column><el-table-column prop="evidence" label="说明" min-width="260" /></el-table>
      <el-input v-model="reviewComment" class="mt-3" maxlength="500" placeholder="填写研究结论与模拟盘准备意见" type="textarea" />
      <div class="mt-3">
        <el-button v-hasPermi="['quant:backtest:create']" type="primary" :disabled="!canPrepare" :loading="acting" @click="preparePaper">接受评审并创建模拟会话</el-button>
        <el-button v-hasPermi="['quant:backtest:create']" type="danger" plain :disabled="!activeBatch.terminal" :loading="acting" @click="rejectReview">拒绝方案</el-button>
        <el-button @click="router.push('/quant/backtest')">进入模拟盘审批与执行</el-button>
        <el-button @click="router.push('/quant/operations')">运行面板</el-button>
      </div>
      <el-table :data="activeBatch.reviews" class="mt-3"><el-table-column prop="decision" label="评审决定" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column></el-table>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Echart } from '@/components/Echart'
import type { EChartsOption } from 'echarts'
import { getCostSensitivity, type CostSensitivity, getPaperExperimentReview, type PaperExperimentReview, createStrategyExperiment, listStrategyExperiments, getStrategyExperiment, type StrategyExperiment, type StrategyExperimentResult, type ExperimentRow } from '@/api/quant/experiments'
import { getCapabilities, listStrategyVersions, listParameterSets, listDatasets, createStrategyVersion, getOptimization, reviewOptimization, reviewPaperAdmission, createPaperSession, strategyConfigurationLabel, type StrategyVersion, type ParameterSet, type DatasetQuality, type OptimizationResult } from '@/api/quant/backtest'

defineOptions({ name: 'QuantExperiments' })
const router = useRouter()
const form = reactive({ strategyVersionIds: [] as string[], parameterSetId: '', datasetId: '', validationEnd: '' })
const trainDates = ref<string[]>([])
const versions = ref<StrategyVersion[]>([]), parameters = ref<ParameterSet[]>([]), datasets = ref<DatasetQuality[]>([])
const experiments = ref<StrategyExperiment[]>([]), result = ref<StrategyExperimentResult>()
const enabled = ref(false), loading = ref(false), submitting = ref(false), presetLoading = ref(false), acting = ref(false)
const errorMessage = ref(''), reviewVisible = ref(false), reviewComment = ref('')
const paperReview = ref<PaperExperimentReview>()
const paperReviewError = ref('')
const costs = ref<CostSensitivity>(), costLoading = ref(false), costError = ref('')
const costAssumptions = reactive({ feeBps: 10, slippageBps: 5 })
let costRequest = 0
function money(value?: number) { return value == null ? '-' : Number(value).toFixed(6) }
async function loadCosts() {
  const id = result.value?.id
  if (!id) return
  const requestId = ++costRequest
  costLoading.value = true; costs.value = undefined; costError.value = ''
  try {
    const data = await getCostSensitivity(id, costAssumptions.feeBps, costAssumptions.slippageBps)
    if (!disposed && requestId === costRequest && result.value?.id === id) costs.value = data
  } catch { if (requestId === costRequest) costError.value = '成本比较读取失败，请重试。' }
  finally { if (requestId === costRequest) costLoading.value = false }
}
function exportCosts() {
  if (!costs.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(costs.value, null, 2)], { type: 'application/json' }))
  const a = document.createElement('a'); a.href = url; a.download = `costs-${costs.value.experimentId}.json`; a.click(); URL.revokeObjectURL(url)
}
const activeBatch = ref<OptimizationResult>(), activeRow = ref<ExperimentRow>()
let pendingRequest: { signature: string; key: string } | undefined
let timer: number | undefined
let disposed = false
let reviewParameterId = ''
const canPrepare = computed(() => Boolean(activeBatch.value?.terminal && activeBatch.value.paperAdmission.checks.filter(c => c.id !== 'RESEARCH_ACCEPTED').every(c => c.passed)))
const chartRows = computed(() => result.value?.rows.filter(row => row.validationReturn != null) || [])
const comparisonOptions = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis' }, legend: { data: ['验证收益 %', '验证回撤 %'] },
  grid: { left: 55, right: 20, top: 40, bottom: 65 },
  xAxis: { type: 'category', data: chartRows.value.map(row => row.configuration ? strategyConfigurationLabel(row.configuration).split(' · ')[0] : row.strategyVersionId.slice(0, 8)) },
  yAxis: { type: 'value', name: '%' },
  series: [
    { name: '验证收益 %', type: 'bar', data: chartRows.value.map(row => Number(row.validationReturn) * 100) },
    { name: '验证回撤 %', type: 'bar', data: chartRows.value.map(row => Number(row.validationDrawdown) * 100) }
  ]
}))
const parameterSummary = computed(() => {
  try { const p = JSON.parse(result.value?.parametersJson || '{}'); return `初始 ${p.startingBalance} USDT / 单笔 ${p.stakeAmount} USDT / 单边费率 ${p.fee}` }
  catch { return '资金参数待加载' }
})
const label = strategyConfigurationLabel
function parameterLabel(p: ParameterSet) { try { const v = JSON.parse(p.parametersJson); return `${v.startingBalance} USDT / 单笔 ${v.stakeAmount} / 费率 ${v.fee}` } catch { return p.id } }
function percent(value?: number | null) { return value == null ? '-' : `${(Number(value) * 100).toFixed(3)}%` }
function time(value: number) { return new Date(value).toLocaleString() }
function stage(row: ExperimentRow) {
  if (row.paperExecutionStatus) return `模拟盘 ${row.paperExecutionStatus}`
  if (row.paperSessionStatus) return `模拟会话 ${row.paperSessionStatus}`
  if (row.paperEligible) return '可准备模拟盘'
  if (row.researchDecision === 'REJECTED') return '方案已拒绝'
  return row.researchDecision === 'ACCEPTED' ? '已评审，条件待满足' : '待评审'
}
async function loadPaperReview(id: string) {
  if (costs.value?.experimentId !== id) { costs.value = undefined; costError.value = ''; ++costRequest; costLoading.value = false }
  try { paperReview.value = await getPaperExperimentReview(id); paperReviewError.value = '' }
  catch { paperReview.value = undefined; paperReviewError.value = 'SIMULATION_REVIEW_UNAVAILABLE' }
}
function exportPaperReview() {
  if (!paperReview.value) return
  const blob = new Blob([JSON.stringify(paperReview.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob), a = document.createElement('a')
  a.href = url; a.download = `paper-review-${paperReview.value.experimentId}.json`; a.click(); URL.revokeObjectURL(url)
}
async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    const list = await listStrategyExperiments()
    const detail = result.value ? await getStrategyExperiment(result.value.id) : undefined
    experiments.value = list
    if (detail) { result.value = detail; await loadPaperReview(detail.id) }
    errorMessage.value = ''
  } catch { errorMessage.value = '工作台刷新失败；已有结果可能不是最新数据，请重试。' }
  finally { loading.value = false }
}
async function selectExperiment(id: string) {
  if (loading.value) return
  loading.value = true
  try { result.value = await getStrategyExperiment(id); await loadPaperReview(id); errorMessage.value = '' }
  finally { loading.value = false }
}
async function addPresets() {
  presetLoading.value = true
  try {
    const ids: string[] = []
    for (const config of [
      { fastPeriod: 12, slowPeriod: 48, stopLossRatio: 0.015, takeProfitRatio: 0.03 },
      { fastPeriod: 20, slowPeriod: 60, stopLossRatio: 0.02, takeProfitRatio: 0.04 },
      { fastPeriod: 30, slowPeriod: 90, stopLossRatio: 0.03, takeProfitRatio: 0.06 }
    ]) ids.push(await createStrategyVersion(config))
    versions.value = await listStrategyVersions(); form.strategyVersionIds = ids
    ElMessage.success('三组研究版本已选中；请设置共同实验条件')
  } finally { presetLoading.value = false }
}
async function submit() {
  if (form.strategyVersionIds.length < 2 || !form.parameterSetId || !form.datasetId || trainDates.value?.length !== 2 || !form.validationEnd) {
    ElMessage.warning('请选择方案、资金参数、数据及完整训练/验证日期'); return
  }
  const data = { strategyVersionIds: [...form.strategyVersionIds].sort(), parameterSetId: form.parameterSetId, datasetId: form.datasetId, trainStart: trainDates.value[0], splitDate: trainDates.value[1], validationEnd: form.validationEnd }
  const signature = JSON.stringify(data)
  if (!pendingRequest || pendingRequest.signature !== signature) pendingRequest = { signature, key: `experiment-${crypto.randomUUID()}` }
  submitting.value = true
  try {
    const id = await createStrategyExperiment({ ...data, requestKey: pendingRequest.key })
    result.value = await getStrategyExperiment(id)
    pendingRequest = undefined
    await refresh(); ElMessage.success('多方案实验已进入持久回测队列')
  } finally { submitting.value = false }
}
async function openReview(row: ExperimentRow) {
  activeBatch.value = await getOptimization(row.batchId); activeRow.value = row; reviewComment.value = ''; reviewParameterId = result.value?.parameterSetId || ''; reviewVisible.value = true
}
async function preparePaper() {
  const batch = activeBatch.value, row = activeRow.value, comment = reviewComment.value.trim()
  if (!batch || !row || !result.value || !comment) { ElMessage.warning('请填写评审意见'); return }
  acting.value = true
  try {
    await reviewOptimization(batch.id, { decision: 'ACCEPTED', comment, evidenceHash: batch.researchDraft.evidenceSha256 })
    const current = await getOptimization(batch.id); activeBatch.value = current
    if (!current.paperAdmission.eligible) { ElMessage.warning('评审已保存，模拟盘条件尚未全部满足'); return }
    await reviewPaperAdmission(batch.id, { decision: 'READY', comment, evidenceHash: current.paperAdmission.evidenceSha256 })
    const id = await createPaperSession({ batchId: batch.id, parameterSetId: reviewParameterId })
    activeBatch.value = await getOptimization(batch.id); await refresh()
    ElMessage.success(`待审批模拟会话已创建：${id}`)
  } finally { acting.value = false }
}
async function rejectReview() {
  if (!activeBatch.value || !reviewComment.value.trim()) { ElMessage.warning('请填写拒绝原因'); return }
  acting.value = true
  try {
    await reviewOptimization(activeBatch.value.id, { decision: 'REJECTED', comment: reviewComment.value.trim(), evidenceHash: activeBatch.value.researchDraft.evidenceSha256 })
    activeBatch.value = await getOptimization(activeBatch.value.id); await refresh()
    ElMessage.success('研究拒绝意见已保存')
  } finally { acting.value = false }
}
onMounted(async () => {
  try {
    const [caps, v, p, d] = await Promise.all([getCapabilities(), listStrategyVersions(), listParameterSets(), listDatasets()])
    enabled.value = caps.enabled; versions.value = v; parameters.value = p; datasets.value = d; form.parameterSetId = p[0]?.id || ''
    await refresh()
    if (!disposed && experiments.value[0]) await selectExperiment(experiments.value[0].id)
    if (!disposed) timer = window.setInterval(refresh, 15000)
  } catch { errorMessage.value = '工作台加载失败，请确认新增接口已部署后刷新。' }
})
onBeforeUnmount(() => { disposed = true; if (timer) window.clearInterval(timer) })
</script>
