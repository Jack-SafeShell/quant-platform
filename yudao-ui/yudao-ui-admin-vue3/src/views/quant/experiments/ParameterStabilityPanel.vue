<template>
  <ContentWrap title="把参数稍微改一下，结果会变很多吗？">
    <p>只改一个参数，比较“略小、原值、略大”三组历史表现。用它观察赚亏方向、收益差和成交变化，原方案保留。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-width="130px" class="mt-4" @submit.prevent="preview">
      <el-form-item label="原策略方案"><el-select v-model="form.strategyVersionId" placeholder="选择已有策略" class="w-100%"><el-option v-for="(v, i) in versions.filter(v => v.configuration)" :key="v.id" :value="v.id" :label="`${strategyConfigurationLabel(v.configuration)} · 版本${i + 1}`" /></el-select></el-form-item>
      <el-form-item label="检查哪个参数"><el-select v-model="form.parameter" placeholder="先选原策略"><el-option v-for="key in parameterOptions" :key="key" :value="key" :label="parameterLabels[key]" /></el-select></el-form-item>
      <el-form-item label="上下调整多少"><el-input-number v-model="form.step" :min="1" :max="10" :precision="0" /><span class="ml-3">{{ parameterUnit }}；只改所选参数，其他设置保持相同</span></el-form-item>
      <el-form-item label="资金与费率"><el-select v-model="form.parameterSetId" class="w-100%" placeholder="三组使用相同本金和每笔金额"><el-option v-for="p in parameters" :key="p.id" :value="p.id" :label="parameterLabel(p)" /></el-select></el-form-item>
      <el-form-item label="行情数据"><el-select v-model="form.datasetId" class="w-100%" placeholder="选择已下载行情"><el-option v-for="d in datasets.filter(d => d.status === 'VALID')" :key="d.id" :value="d.id" :label="`${d.exchange.toUpperCase()} · ${d.pair} · ${d.timeframe} · ${day(d.firstTimestamp)} ～ ${day(d.lastTimestamp)}`" /></el-select></el-form-item>
      <el-form-item label="同一段历史"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="UTC开始日" end-placeholder="结束日（不含）" /><el-button class="ml-3" :disabled="!selectedDataset" @click="useRange">使用行情可回看范围</el-button></el-form-item>
      <p class="text-gray-500">区间14～366天；三组共用完整日期，各自重置本金。预热240根K线：1小时10天、4小时40天。</p>
      <el-form-item><el-button :loading="busy" :disabled="!ready || !enabled" @click="preview">1. 预览三组参数</el-button><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="busy" :disabled="!plan || !enabled" @click="submit">2. 开始稳定性回看</el-button></el-form-item>
    </el-form>
    <el-table v-if="plan" :data="plan.variants"><el-table-column label="方案" width="100"><template #default="s">{{ role(s.row.role) }}</template></el-table-column><el-table-column :label="parameterLabels[plan.parameter]" prop="value" width="180" /><el-table-column label="完整配置"><template #default="s">{{ strategyConfigurationLabel(s.row.configuration) }}</template></el-table-column></el-table>
    <p class="text-gray-500">如果调整后越过参数范围或违反原策略规则，会提示调整幅度；不会悄悄截断数值或用重复配置代替。</p>
    <el-divider />
    <el-button :loading="refreshing" @click="refresh">刷新进度</el-button>
    <el-select v-model="selectedId" class="ml-3" style="width: 480px; max-width: 100%" placeholder="查看已有检查" @change="selectBatch"><el-option v-for="b in batches" :key="b.id" :value="b.id" :label="`${parameterLabels[b.parameter]} ±${b.step} · ${b.exchange.toUpperCase()} ${b.pair} ${b.timeframe} · ${b.startDate} ～ ${b.endDate}`" /></el-select>
    <template v-if="result">
      <p>{{ result.plan.exchange.toUpperCase() }} {{ result.plan.pair }} {{ result.plan.timeframe }} · {{ result.plan.startDate }} ～ {{ result.plan.endDate }} UTC（结束不含） · {{ parameterLabels[result.plan.parameter] }} ±{{ result.plan.step }}</p>
      <el-alert v-if="result.terminal && !result.summary" title="有失败或证据不完整的配置，暂不生成汇总；已完成方案可单独查看。" type="warning" :closable="false" />
      <p v-if="!result.terminal">三组依次回测中，每15秒自动刷新。</p>
      <template v-if="result.summary">
        <el-alert :title="finding(result.summary.finding)" type="info" :closable="false" />
        <p>最好与最差收益相差 {{ points(result.summary.returnSpread) }} · 最差收益 {{ percent(result.summary.worstReturn) }} · 最大原回撤 {{ percent(result.summary.maxDrawdown) }} · 最少成交 {{ result.summary.minTrades }} 笔</p>
        <p v-if="result.summary.noFillVariants">{{ result.summary.noFillVariants }}组没有成交，零收益不能说明参数稳定。</p>
      </template>
      <el-table :data="result.rows" row-key="role" class="mt-3">
        <el-table-column label="方案" width="90"><template #default="s">{{ role(s.row.role) }}</template></el-table-column>
        <el-table-column label="参数值" prop="value" width="85" />
        <el-table-column label="状态" min-width="110"><template #default="s">{{ status(s.row.status) }}{{ s.row.noFills ? ' · 无成交' : '' }}</template></el-table-column>
        <el-table-column label="收益率"><template #default="s">{{ percent(s.row.returnRatio) }}</template></el-table-column>
        <el-table-column label="比原值多赚 / 少赚" min-width="170"><template #default="s">{{ points(s.row.returnDelta) }}</template></el-table-column>
        <el-table-column label="赚亏（USDT）"><template #default="s">{{ s.row.netProfit == null ? '—' : Number(s.row.netProfit).toFixed(2) }}</template></el-table-column>
        <el-table-column label="最大回撤"><template #default="s">{{ percent(s.row.maxDrawdownRatio) }}</template></el-table-column>
        <el-table-column label="成交笔数"><template #default="s">{{ s.row.totalTrades ?? '—' }}</template></el-table-column>
        <el-table-column label="操作" width="105"><template #default="s"><el-button link type="primary" :disabled="!s.row.available" @click="reviewId = s.row.taskId">查看买卖</el-button></template></el-table-column>
      </el-table>
      <el-button class="mt-3" @click="exportResult">导出参数检查</el-button>
      <p v-for="notice in result.limitations" :key="notice" class="text-gray-500">{{ notice }}</p>
    </template>
    <el-dialog v-model="reviewOpen" title="这一组参数的历史买卖" width="92%" destroy-on-close><HistoricalReviewPanel v-if="reviewId" :task-id="reviewId" /></el-dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { strategyConfigurationLabel, type StrategyVersion, type ParameterSet, type DatasetQuality } from '@/api/quant/backtest'
import { parameterLabels, stabilityParameters, previewStability, createStability, listStability, getStability, type StabilityPlan, type StabilityBatch, type StabilityResult } from '@/api/quant/stability'
import HistoricalReviewPanel from '@/views/quant/backtest/HistoricalReviewPanel.vue'
const props = defineProps<{ versions: StrategyVersion[]; parameters: ParameterSet[]; datasets: DatasetQuality[]; enabled: boolean }>()
const form = reactive({ strategyVersionId: '', parameterSetId: '', datasetId: '', parameter: '', step: 2 })
const dates = ref<string[]>([]), plan = ref<StabilityPlan>(), batches = ref<StabilityBatch[]>([]), result = ref<StabilityResult>()
const error = ref(''), busy = ref(false), refreshing = ref(false), selectedId = ref(''), reviewId = ref('')
let requestKey = crypto.randomUUID(), generation = 0, disposed = false, timer: number | undefined
const parameterOptions = computed(() => stabilityParameters(props.versions.find(v => v.id === form.strategyVersionId)?.configuration))
const parameterUnit = computed(() => form.parameter.endsWith('Threshold') || form.parameter === 'rsiCeiling' ? 'RSI刻度（0～100），不是百分比' : '根K线，按所选行情周期计算')
const selectedDataset = computed(() => props.datasets.find(d => d.id === form.datasetId))
const ready = computed(() => !!form.strategyVersionId && !!form.parameterSetId && !!form.datasetId && parameterOptions.value.includes(form.parameter) && dates.value?.length === 2)
const reviewOpen = computed({ get: () => !!reviewId.value, set: (open) => { if (!open) reviewId.value = '' } })
const day = (stamp: number) => new Date(stamp).toISOString().slice(0, 10)
const percent = (v?: number) => v == null ? '—' : `${(Number(v) * 100).toFixed(2)}%`
const points = (v?: number) => v == null ? '—' : `${(Number(v) * 100).toFixed(2)} 个百分点`
const role = (v: string) => ({ LOWER: '略小', BASE: '原值', UPPER: '略大' }[v] || v)
const status = (v: string) => ({ QUEUED: '排队中', RUNNING: '回测中', SUCCEEDED: '完成', FAILED: '失败', CANCELLED: '已取消', EVIDENCE_MISMATCH: '证据不完整' }[v] || v)
const finding = (v: string) => ({ INSUFFICIENT_TRADES: '至少一组不足5笔成交，样本太少，暂不判断参数稳定性。', ALL_POSITIVE_THIS_WINDOW: '三组在这段历史里均为正收益；还需结合收益差、回撤及其他时段观察。', ALL_NON_POSITIVE: '三组在这段历史里都没有正收益，原参数附近未表现出盈利优势。', DIRECTION_CHANGED: '调整参数后，赚亏方向发生变化，需留意对参数的敏感程度。' }[v] || v)
function parameterLabel(p: ParameterSet) { const c = JSON.parse(p.parametersJson); return `本金${c.startingBalance} / 每笔${c.stakeAmount} USDT / 单边手续费${(c.fee * 100).toFixed(2)}%` }
watch(() => props.parameters, p => { if (!form.parameterSetId) form.parameterSetId = p[0]?.id || '' }, { immediate: true })
watch(parameterOptions, options => { if (!options.includes(form.parameter)) form.parameter = options[0] || '' })
watch(() => JSON.stringify([form, dates.value]), () => { generation++; plan.value = undefined; requestKey = crypto.randomUUID() })
function useRange() {
  const d = selectedDataset.value; if (!d) return
  const step = d.timeframe === '4h' ? 14400000 : 3600000
  const start = Math.ceil((d.firstTimestamp + 240 * step) / 86400000) * 86400000, end = Math.floor((d.lastTimestamp + step) / 86400000) * 86400000
  dates.value = [day(Math.max(start, end - 366 * 86400000)), day(end)]
}
function request() { return { ...form, requestKey, startDate: dates.value[0], endDate: dates.value[1] } }
async function preview() {
  if (busy.value || !ready.value) return
  busy.value = true; error.value = ''; plan.value = undefined; const stamp = generation, body = request()
  try { const value = await previewStability(body); if (!disposed && stamp === generation) plan.value = value }
  catch { error.value = '无法预览。请检查上下调整后是否越界、EMA短期是否小于长期、RSI买入线是否低于卖出线，以及日期和预热是否完整。' }
  finally { busy.value = false }
}
async function submit() {
  if (busy.value || !plan.value) return
  busy.value = true; error.value = ''; const body = request()
  try { const id = await createStability(body); if (!disposed) { selectedId.value = id; await refresh() } }
  catch { error.value = '检查创建失败。请检查队列是否已满；相同条件重试不会重复创建。' }
  finally { busy.value = false }
}
async function selectBatch() {
  const id = selectedId.value; result.value = undefined; reviewId.value = ''
  if (!id) return
  try { const value = await getStability(id); if (!disposed && id === selectedId.value) result.value = value }
  catch { if (id === selectedId.value) error.value = '检查结果读取失败，请刷新重试。' }
}
async function refresh() {
  if (refreshing.value || disposed) return
  refreshing.value = true
  try {
    const values = await listStability(); if (disposed) return
    batches.value = values; if (!selectedId.value) selectedId.value = values[0]?.id || ''
    const id = selectedId.value
    if (id) { const value = await getStability(id); if (!disposed && id === selectedId.value) result.value = value }
    error.value = ''
  } catch { result.value = undefined; error.value = '检查进度读取失败，请确认服务已更新后重试。' }
  finally { refreshing.value = false }
}
function exportResult() {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(result.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = `parameter-stability-${result.value.id}.json`; link.click(); URL.revokeObjectURL(url)
}
onMounted(() => { refresh(); timer = window.setInterval(() => { if (!result.value?.terminal) refresh() }, 15000) })
onBeforeUnmount(() => { disposed = true; if (timer) window.clearInterval(timer) })
</script>
