<template>
  <HistoryGuide />
  <ContentWrap title="用同一段历史比较多个策略">
    <p>用相同资金、费率和数据比较候选方案，查看历史赚亏与买卖记录。收益、回撤和执行表现共同决定后续选择。</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
    <el-form label-width="110px" class="mt-4" @submit.prevent="submit">
      <el-form-item label="候选方案">
        <el-select v-model="form.strategyVersionIds" multiple class="w-100%" :multiple-limit="5" placeholder="选择 2 至 5 个策略版本">
          <el-option v-for="(version, index) in versions" :key="version.id" :value="version.id" :label="`${label(version.configuration)} · 版本 ${index + 1}`" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button v-hasPermi="['quant:backtest:create']" :loading="presetLoading" @click="addPresets">添加三组 EMA 研究预设</el-button><el-button @click="router.push('/quant/backtest')">自定义策略配置</el-button></el-form-item>
      <el-form-item label="资金与费率">
        <el-select v-model="form.parameterSetId" class="w-100%" placeholder="所有方案共用一个资金参数集"><el-option v-for="p in parameters" :key="p.id" :value="p.id" :label="parameterLabel(p)" /></el-select>
      </el-form-item>
      <el-form-item label="行情数据"><el-select v-model="form.datasetId" class="w-100%"><el-option v-for="d in datasets.filter(d => d.status === 'VALID')" :key="d.id" :value="d.id" :label="`${d.exchange.toUpperCase()} · ${d.pair} · ${new Date(d.firstTimestamp).toISOString().slice(0, 10)} 至 ${new Date(d.lastTimestamp).toISOString().slice(0, 10)}`" /></el-select></el-form-item>
      <p class="text-gray-500">前一段用于比较方案；后一段从切分日开始，用来检查换一段行情后是否还能保持表现。两段各至少 7 天，结束日不计入。</p>
      <el-form-item label="前一段历史"><el-date-picker v-model="trainDates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="UTC 开始日" end-placeholder="切分日（不含）" /></el-form-item>
      <el-form-item label="后一段结束日"><el-date-picker v-model="form.validationEnd" value-format="YYYY-MM-DD" placeholder="从训练切分日开始验证，结束日不含" /></el-form-item>
      <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="submitting" :disabled="!enabled" @click="submit">开始比较这些策略</el-button><span class="ml-3">每个方案生成训练、验证各一次回测，两段各至少 7 天。</span></el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap title="我的实验（最近 50 组）">
    <el-button :loading="loading" @click="refresh">刷新</el-button>
    <el-table :data="experiments" class="mt-3">
      <el-table-column prop="datasetId" label="数据集" min-width="160" />
      <el-table-column label="前一段历史" min-width="220"><template #default="s">{{ s.row.trainStart }} ～ {{ s.row.splitDate }}</template></el-table-column>
      <el-table-column label="验证区间" min-width="220"><template #default="s">{{ s.row.splitDate }} ～ {{ s.row.validationEnd }}</template></el-table-column>
      <el-table-column label="创建时间" min-width="180"><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="110"><template #default="s"><el-button link type="primary" @click="selectExperiment(s.row.id)">查看结果</el-button></template></el-table-column>
    </el-table>
  </ContentWrap>

  <ContentWrap title="多时段候选评估">
    <p>选择2至4个同数据、同资金和同候选的已完成实验；验证时段不得重叠。各时段重置资金，结果不合并为组合收益。</p>
    <el-select v-model="assessmentIds" multiple :multiple-limit="4" class="w-100%" @change="assessment = undefined">
      <el-option v-for="e in experiments" :key="e.id" :value="e.id" :label="`${e.datasetId} · ${e.splitDate} ～ ${e.validationEnd} · ${e.id.slice(0, 8)}`" />
    </el-select>
    <p>单边手续费/每边滑点：基础10/5基点，压力20/10基点；每个验证时段至少5笔成交只是研究筛选标准。</p>
    <el-button class="mt-3" :loading="assessmentLoading" :disabled="assessmentIds.length < 2" @click="loadAssessment">评估候选</el-button>
    <el-button class="mt-3" :disabled="!assessment" @click="exportAssessment">导出评估证据</el-button>
    <el-alert v-if="assessmentError" :title="assessmentError" type="warning" :closable="false" class="mt-3" />
    <template v-if="assessment">
      <p>共{{ assessment.validationDays }}个验证日 · 证据{{ assessment.evidenceHash.slice(0, 12) }} · 仅研究建议，不更改参数或启动交易</p>
      <el-table :data="assessment.rows">
        <el-table-column prop="rank" label="排序" width="65" />
        <el-table-column label="方案" min-width="220"><template #default="s">{{ label(s.row.configuration) }}</template></el-table-column>
        <el-table-column label="评估" min-width="150"><template #default="s">{{ assessmentLabel(s.row.classification) }}</template></el-table-column>
        <el-table-column label="正收益时段" width="115"><template #default="s">{{ s.row.positiveWindows }}/{{ s.row.windowCount }}</template></el-table-column>
        <el-table-column label="最差基础收益"><template #default="s">{{ percent(s.row.worstBaseReturn) }}</template></el-table-column>
        <el-table-column label="最差压力收益"><template #default="s">{{ percent(s.row.worstStressReturn) }}</template></el-table-column>
        <el-table-column label="原回测最大回撤"><template #default="s">{{ percent(s.row.maxBaseDrawdown) }}</template></el-table-column>
        <el-table-column prop="minWindowTrades" label="最少时段成交" />
        <el-table-column prop="tradesPer30Days" label="成交/30日" />
      </el-table>
      <p v-for="limitation in assessment.limitations" :key="limitation" class="text-gray-500">{{ limitation }}</p>
    </template>
  </ContentWrap>

  <ContentWrap v-if="result" title="比较历史表现与回看结论">
    <p>实验 {{ result.id }} · {{ parameterSummary }} · {{ result.terminal ? '全部回测结束，按验证收益排序' : '回测执行中，暂不排名' }}</p>
    <Echart v-if="chartRows.length" :options="comparisonOptions" :not-merge="true" height="300px" />
    <el-table :data="result.rows" row-key="strategyVersionId">
      <el-table-column prop="rank" label="排名" width="65" />
      <el-table-column label="方案" min-width="245"><template #default="s">{{ label(s.row.configuration) }}</template></el-table-column>
      <el-table-column label="训练 / 验证" width="205"><template #default="s">{{ taskStatus(s.row.trainStatus) }} / {{ taskStatus(s.row.validationStatus) }}</template></el-table-column>
      <el-table-column label="训练收益" width="105"><template #default="s">{{ percent(s.row.trainReturn) }}</template></el-table-column>
      <el-table-column label="验证收益" width="105"><template #default="s">{{ percent(s.row.validationReturn) }}</template></el-table-column>
      <el-table-column label="验证回撤" width="105"><template #default="s">{{ percent(s.row.validationDrawdown) }}</template></el-table-column>
      <el-table-column label="收益差" width="100"><template #default="s">{{ percent(s.row.overfitGap) }}</template></el-table-column>
      <el-table-column prop="validationTrades" label="验证成交" width="95" />
      <el-table-column label="当前阶段" min-width="160"><template #default="s">{{ stage(s.row) }}</template></el-table-column>
      <el-table-column label="操作" width="125"><template #default="s"><el-button link type="primary" @click="openReview(s.row)">查看回看结论</el-button></template></el-table-column>
    </el-table>
    <p class="text-gray-500">历史排名用于比较过去的表现，建议再用另一段历史检查结果是否稳定。此处的收益率已计入所选费率，不能等同于未来实盘收益。</p>
  </ContentWrap>

  <ContentWrap v-if="result" title="费用与滑点敏感性">
    <p>固定原成交序列，估算双边费用和不利滑点。1 基点 = 0.01%；费用按每边成交金额计入。</p>
    <el-form :inline="true" @submit.prevent="loadCosts">
      <el-form-item label="单边手续费（基点）"><el-input-number v-model="costAssumptions.feeBps" :min="0" :max="100" :precision="0" /></el-form-item>
      <el-form-item label="每边滑点（基点）"><el-input-number v-model="costAssumptions.slippageBps" :min="0" :max="100" :precision="0" /></el-form-item>
      <el-button :loading="costLoading" @click="loadCosts">计算比较</el-button>
      <el-button :disabled="!costs" @click="exportCosts">导出详细记录</el-button>
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

  <el-dialog v-model="reviewVisible" title="回看结论与研究笔记" width="80%">
    <template v-if="activeBatch && activeRow">
      <p>{{ label(activeRow.configuration) }} · 版本 {{ activeRow.strategyVersionId }}</p>
      <p>{{ activeBatch.researchDraft.conclusion }}</p>
      <el-alert v-for="risk in activeBatch.researchDraft.risks" :key="risk" :title="risk" type="warning" :closable="false" class="mt-2" />
      <p>这里记录你对历史结果的判断，保存笔记即可完成本次回看；不会创建模拟盘或运行交易。</p>
      <el-input v-model="reviewComment" class="mt-3" maxlength="500" placeholder="写下你对收益、回撤、成交次数和成本的判断" type="textarea" />
      <div class="mt-3">
        <el-button v-hasPermi="['quant:backtest:create']" type="primary" :disabled="!activeBatch.terminal" :loading="acting" @click="acceptResearch">保存：保留这个方案</el-button>
        <el-button v-hasPermi="['quant:backtest:create']" type="danger" plain :disabled="!activeBatch.terminal" :loading="acting" @click="rejectReview">保存：暂不采用</el-button>
        <el-button @click="router.push('/quant/backtest')">查看单次回看和买卖记录</el-button>
      </div>
      <el-table :data="activeBatch.reviews" class="mt-3"><el-table-column label="回看判断"><template #default="s">{{ s.row.decision === 'ACCEPTED' ? '保留研究' : '暂不采用' }}</template></el-table-column><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column></el-table>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import HistoryGuide from '../HistoryGuide.vue'
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Echart } from '@/components/Echart'
import type { EChartsOption } from 'echarts'
import { assessCandidates, type CandidateAssessment } from '@/api/quant/experiments'
import { getCostSensitivity, type CostSensitivity, createStrategyExperiment, listStrategyExperiments, getStrategyExperiment, type StrategyExperiment, type StrategyExperimentResult, type ExperimentRow } from '@/api/quant/experiments'
import { getCapabilities, listStrategyVersions, listParameterSets, listDatasets, createStrategyVersion, getOptimization, reviewOptimization, strategyConfigurationLabel, type StrategyVersion, type ParameterSet, type DatasetQuality, type OptimizationResult } from '@/api/quant/backtest'

defineOptions({ name: 'QuantExperiments' })
const router = useRouter()
const form = reactive({ strategyVersionIds: [] as string[], parameterSetId: '', datasetId: '', validationEnd: '' })
const trainDates = ref<string[]>([])
const versions = ref<StrategyVersion[]>([]), parameters = ref<ParameterSet[]>([]), datasets = ref<DatasetQuality[]>([])
const experiments = ref<StrategyExperiment[]>([]), result = ref<StrategyExperimentResult>()
const assessmentIds = ref<string[]>([]), assessment = ref<CandidateAssessment>()
const assessmentLoading = ref(false), assessmentError = ref('')
function assessmentLabel(value: string) { return ({ RESEARCH_CANDIDATE:'可供研究复核', INSUFFICIENT_TRADES:'成交样本不足', INCONSISTENT:'时段收益不一致', COST_SENSITIVE:'成本敏感' } as Record<string,string>)[value] || value }
async function loadAssessment() {
  if (assessmentLoading.value) return
  const ids = [...assessmentIds.value].sort(), signature = JSON.stringify(ids)
  assessmentLoading.value = true; assessment.value = undefined; assessmentError.value = ''
  try {
    const value = await assessCandidates({experimentIds:ids,feeBps:10,slippageBps:5,stressFeeBps:20,stressSlippageBps:10})
    if (signature === JSON.stringify([...assessmentIds.value].sort())) assessment.value = value
  } catch { assessmentError.value = '评估失败，请检查实验是否全部成功、共用条件及验证时段是否重叠。' }
  finally { assessmentLoading.value = false }
}
function exportAssessment() {
  if (!assessment.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(assessment.value,null,2)],{type:'application/json'})), a = document.createElement('a')
  a.href = url; a.download = `candidate-assessment-${assessment.value.evidenceHash.slice(0,12)}.json`; a.click(); URL.revokeObjectURL(url)
}
const enabled = ref(false), loading = ref(false), submitting = ref(false), presetLoading = ref(false), acting = ref(false)
const errorMessage = ref(''), reviewVisible = ref(false), reviewComment = ref('')
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
  try { const p = JSON.parse(result.value?.parametersJson || '{}'); return `初始 ${p.startingBalance} USDT / 单笔 ${p.stakeAmount} USDT / 每次手续费 ${(Number(p.fee) * 100).toFixed(2)}%` }
  catch { return '资金参数待加载' }
})
const label = strategyConfigurationLabel
function parameterLabel(p: ParameterSet) { try { const v = JSON.parse(p.parametersJson); return `${v.startingBalance} USDT / 单笔 ${v.stakeAmount} / 手续费 ${(Number(v.fee) * 100).toFixed(2)}%` } catch { return p.id } }
function percent(value?: number | null) { return value == null ? '-' : `${(Number(value) * 100).toFixed(3)}%` }
function time(value: number) { return new Date(value).toLocaleString() }
function taskStatus(status: string) { return ({ QUEUED: '排队中', RUNNING: '计算中', SUCCEEDED: '已完成', FAILED: '计算失败' } as Record<string, string>)[status] || status }
function stage(row: ExperimentRow) {
  if (row.trainStatus !== 'SUCCEEDED' || row.validationStatus !== 'SUCCEEDED') return '先等待两段历史计算完成'
  if (row.researchDecision === 'REJECTED') return '已记录：暂不采用'
  return row.researchDecision === 'ACCEPTED' ? '已记录：保留研究' : '已完成，查看回看结论'
}
async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    const list = await listStrategyExperiments()
    const detail = result.value ? await getStrategyExperiment(result.value.id) : undefined
    experiments.value = list
    if (detail) result.value = detail
    errorMessage.value = ''
  } catch { errorMessage.value = '工作台刷新失败；已有结果可能不是最新数据，请重试。' }
  finally { loading.value = false }
}
async function selectExperiment(id: string) {
  if (loading.value) return
  loading.value = true
  try { result.value = await getStrategyExperiment(id); costs.value = undefined; costError.value = ''; ++costRequest; errorMessage.value = '' }
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
    ElMessage.warning('请选择至少两个策略、一组金额、历史行情及前后两段日期'); return
  }
  const data = { strategyVersionIds: [...form.strategyVersionIds].sort(), parameterSetId: form.parameterSetId, datasetId: form.datasetId, trainStart: trainDates.value[0], splitDate: trainDates.value[1], validationEnd: form.validationEnd }
  const signature = JSON.stringify(data)
  if (!pendingRequest || pendingRequest.signature !== signature) pendingRequest = { signature, key: `experiment-${crypto.randomUUID()}` }
  submitting.value = true
  try {
    const id = await createStrategyExperiment({ ...data, requestKey: pendingRequest.key })
    result.value = await getStrategyExperiment(id)
    pendingRequest = undefined
    await refresh(); ElMessage.success('比较任务已开始，请在下方查看结果')
  } finally { submitting.value = false }
}
async function openReview(row: ExperimentRow) {
  activeBatch.value = await getOptimization(row.batchId); activeRow.value = row; reviewComment.value = ''; reviewVisible.value = true
}
async function acceptResearch() {
  const batch = activeBatch.value, comment = reviewComment.value.trim()
  if (!batch || !batch.terminal || !comment) { ElMessage.warning('请写下你的回看结论'); return }
  acting.value = true
  try {
    await reviewOptimization(batch.id, { decision: 'ACCEPTED', comment, evidenceHash: batch.researchDraft.evidenceSha256 })
    activeBatch.value = await getOptimization(batch.id); await refresh()
    ElMessage.success('回看结论已保存，没有启动模拟盘或交易')
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
