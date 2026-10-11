<template>
  <ContentWrap title="把一个策略放到多段历史中检查">
    <p>选一个策略、一份行情和日期，自动分成几段分别回看。看看换一段行情后，赚亏和回撤是否变化。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-width="120px" class="mt-4" @submit.prevent="preview">
      <el-form-item label="策略方案"><el-select v-model="form.strategyVersionId" placeholder="选择已有策略" class="w-100%"><el-option v-for="(v, i) in versions" :key="v.id" :value="v.id" :label="`${strategyConfigurationLabel(v.configuration)} · 版本${i + 1}`" /></el-select></el-form-item>
      <el-form-item label="资金与费率"><el-select v-model="form.parameterSetId" placeholder="每段使用相同本金和每笔金额" class="w-100%"><el-option v-for="p in parameters" :key="p.id" :value="p.id" :label="parameterLabel(p)" /></el-select></el-form-item>
      <el-form-item label="行情数据"><el-select v-model="form.datasetId" placeholder="选择已下载的行情" class="w-100%"><el-option v-for="d in datasets.filter(d => d.status === 'VALID')" :key="d.id" :value="d.id" :label="`${d.exchange.toUpperCase()} · ${d.pair} · ${d.timeframe} · ${day(d.firstTimestamp)} ～ ${day(d.lastTimestamp)}`" /></el-select></el-form-item>
      <el-form-item label="历史日期"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日（UTC）" end-placeholder="结束日（不含）" /><el-button class="ml-3" :disabled="!selectedDataset" @click="useRange">使用行情可回看范围</el-button></el-form-item>
      <el-form-item label="分成几段"><el-input-number v-model="form.segmentCount" :min="2" :max="5" :precision="0" /><span class="ml-3">每段至少7天，总日期最多366天</span></el-form-item>
      <p class="text-gray-500">{{ selectedDataset?.timeframe === '4h' ? '4小时行情需要40天' : '1小时行情需要10天' }}预热，日期范围按钮已留出这部分。按UTC天数平均切分，每段天数最多相差1天。</p>
      <el-form-item><el-button :loading="busy" :disabled="!ready || !enabled" @click="preview">1. 预览分段日期</el-button><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="busy" :disabled="!plan || !enabled" @click="submit">2. 一次开始全部回看</el-button></el-form-item>
    </el-form>
    <el-table v-if="plan" :data="plan.windows" class="mb-4"><el-table-column prop="index" label="第几段" /><el-table-column prop="startDate" label="开始日（含）" /><el-table-column prop="endDate" label="结束日（不含）" /><el-table-column prop="days" label="天数" /></el-table>
    <p class="text-gray-500">各段会重置本金，不能把它们的收益相加当作连续运行收益。段末未平仓由历史回测引擎处理，不会启动模拟或实盘交易。</p>
    <el-divider />
    <el-button :loading="refreshing" @click="refresh">刷新进度</el-button>
    <el-select v-model="selectedId" class="ml-3" style="width: 430px; max-width: 100%" placeholder="查看已有分段回看" @change="selectBatch"><el-option v-for="b in batches" :key="b.id" :value="b.id" :label="`${b.exchange.toUpperCase()} ${b.pair} ${b.timeframe} · ${b.startDate} ～ ${b.endDate} · ${b.segmentCount}段 · ${b.id.slice(0, 8)}`" /></el-select>
    <template v-if="result">
      <p>{{ strategyConfigurationLabel(result.plan.configuration) }} · {{ result.plan.exchange.toUpperCase() }} {{ result.plan.pair }} {{ result.plan.timeframe }} · {{ result.terminal ? (result.summary ? '全部完成' : '已结束，部分结果不可用') : '依次回测中，每15秒自动刷新' }}</p>
      <el-alert v-if="result.terminal && !result.summary" title="有失败或证据不完整的区间，暂不生成整体汇总；已完成区间可单独查看。" type="warning" :closable="false" />
      <p v-if="result.summary">正收益 {{ result.summary.positiveWindows }}/{{ result.summary.windowCount }} 段 · 最差收益 {{ percent(result.summary.worstReturn) }} · 最大回撤 {{ percent(result.summary.maxDrawdown) }} · 最少成交 {{ result.summary.minTrades }} 笔</p>
      <el-alert v-if="result.summary?.noFillWindows" :title="`${result.summary.noFillWindows}段没有成交，零收益不能说明策略稳定。`" type="info" :closable="false" />
      <el-table :data="result.rows" class="mt-3">
        <el-table-column prop="index" label="段" width="55" /><el-table-column label="历史日期（UTC，结束不含）" min-width="245"><template #default="s">{{ s.row.startDate }} ～ {{ s.row.endDate }}</template></el-table-column>
        <el-table-column label="状态" min-width="125"><template #default="s">{{ status(s.row.status) }}{{ s.row.noFills ? ' · 无成交' : '' }}</template></el-table-column>
        <el-table-column label="收益率"><template #default="s">{{ percent(s.row.returnRatio) }}</template></el-table-column>
        <el-table-column label="赚亏（USDT）"><template #default="s">{{ s.row.netProfit == null ? '—' : Number(s.row.netProfit).toFixed(2) }}</template></el-table-column>
        <el-table-column label="最大回撤"><template #default="s">{{ percent(s.row.maxDrawdownRatio) }}</template></el-table-column>
        <el-table-column label="成交笔数"><template #default="s">{{ s.row.totalTrades ?? '—' }}</template></el-table-column>
        <el-table-column label="操作" width="110"><template #default="s"><el-button link type="primary" :disabled="!s.row.available" @click="reviewId = s.row.taskId">查看买卖</el-button></template></el-table-column>
      </el-table>
      <el-button class="mt-3" @click="exportResult">导出这组回看</el-button>
      <p v-for="notice in result.limitations" :key="notice" class="text-gray-500">{{ notice }}</p>
    </template>
    <el-dialog v-model="reviewOpen" title="这一段历史的买卖与资金" width="92%" destroy-on-close><HistoricalReviewPanel v-if="reviewId" :task-id="reviewId" /></el-dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { strategyConfigurationLabel, type StrategyVersion, type ParameterSet, type DatasetQuality } from '@/api/quant/backtest'
import { previewSegments, createSegments, listSegments, getSegments, type SegmentPlan, type SegmentBatch, type SegmentResult } from '@/api/quant/segments'
import HistoricalReviewPanel from '@/views/quant/backtest/HistoricalReviewPanel.vue'
const props = defineProps<{ versions: StrategyVersion[]; parameters: ParameterSet[]; datasets: DatasetQuality[]; enabled: boolean }>()
const form = reactive({ strategyVersionId: '', parameterSetId: '', datasetId: '', segmentCount: 3 })
const dates = ref<string[]>([]), plan = ref<SegmentPlan>(), batches = ref<SegmentBatch[]>([]), result = ref<SegmentResult>()
const error = ref(''), busy = ref(false), refreshing = ref(false), selectedId = ref(''), reviewId = ref('')
let requestKey = crypto.randomUUID(), disposed = false, timer: number | undefined, generation = 0
const selectedDataset = computed(() => props.datasets.find(d => d.id === form.datasetId))
const ready = computed(() => !!form.strategyVersionId && !!form.parameterSetId && !!form.datasetId && dates.value?.length === 2)
const reviewOpen = computed({ get: () => !!reviewId.value, set: (open) => { if (!open) reviewId.value = '' } })
const day = (stamp: number) => new Date(stamp).toISOString().slice(0, 10)
const percent = (v?: number) => v == null ? '—' : `${(Number(v) * 100).toFixed(2)}%`
const status = (v: string) => ({ QUEUED: '排队中', RUNNING: '回测中', SUCCEEDED: '完成', FAILED: '失败', CANCELLED: '已取消', EVIDENCE_MISMATCH: '证据不完整' }[v] || v)
function parameterLabel(p: ParameterSet) { const v = JSON.parse(p.parametersJson); return `本金${v.startingBalance} / 每笔${v.stakeAmount} USDT / 单边手续费${(v.fee * 100).toFixed(2)}%` }
watch(() => props.parameters, p => { if (!form.parameterSetId) form.parameterSetId = p[0]?.id || '' }, { immediate: true })
watch(() => JSON.stringify([form, dates.value]), () => { generation++; plan.value = undefined; requestKey = crypto.randomUUID() })
function useRange() {
  const d = selectedDataset.value; if (!d) return
  const step = d.timeframe === '4h' ? 14400000 : 3600000
  const start = Math.ceil((d.firstTimestamp + 240 * step) / 86400000) * 86400000
  const end = Math.floor((d.lastTimestamp + step) / 86400000) * 86400000
  dates.value = [day(Math.max(start, end - 366 * 86400000)), day(end)]
}
function request() { return { ...form, requestKey, startDate: dates.value[0], endDate: dates.value[1] } }
async function preview() {
  if (busy.value || !ready.value) return
  busy.value = true; error.value = ''; plan.value = undefined; const stamp = generation, body = request()
  try { const value = await previewSegments(body); if (!disposed && stamp === generation) plan.value = value }
  catch { error.value = '无法预览。请检查每段是否至少7天、行情是否覆盖完整区间和240根预热K线。' }
  finally { busy.value = false }
}
async function submit() {
  if (busy.value || !plan.value) return
  busy.value = true; error.value = ''; const body = request()
  try { const id = await createSegments(body); if (!disposed) { selectedId.value = id; await refresh() } }
  catch { error.value = '未能创建分段回看。请检查队列是否已满；相同条件重试不会重复创建任务。' }
  finally { busy.value = false }
}
async function selectBatch() {
  const id = selectedId.value; result.value = undefined; reviewId.value = ''
  if (!id) return
  try { const value = await getSegments(id); if (!disposed && id === selectedId.value) result.value = value }
  catch { if (id === selectedId.value) error.value = '结果读取失败，请刷新重试。' }
}
async function refresh() {
  if (refreshing.value || disposed) return
  refreshing.value = true
  try {
    const values = await listSegments(); if (disposed) return
    batches.value = values
    if (!selectedId.value) selectedId.value = values[0]?.id || ''
    const id = selectedId.value
    if (id) { const value = await getSegments(id); if (!disposed && id === selectedId.value) result.value = value }
  } catch { result.value = undefined; error.value = '分段进度读取失败，请确认服务已更新后重试。' }
  finally { refreshing.value = false }
}
function exportResult() {
  if (!result.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(result.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = `historical-segments-${result.value.id}.json`; link.click(); URL.revokeObjectURL(url)
}
onMounted(() => { refresh(); timer = window.setInterval(() => { if (!result.value?.terminal) refresh() }, 15000) })
onBeforeUnmount(() => { disposed = true; if (timer) window.clearInterval(timer) })
</script>
