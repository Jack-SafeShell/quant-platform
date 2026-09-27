<template>
  <ContentWrap title="历史回测">
    <el-alert title="固定 EMA20/EMA60 示例 · BTC/USDT 现货 · 1 小时 · 仅历史回测" type="info" :closable="false" />
    <p class="text-gray-500">用于验证研究流程。止损 2%、止盈 4%，包含 240 根预热；日期按 UTC，结束日不包含在区间内。</p>
    <el-alert v-if="!enabled" title="回测尚未启用。请先完成数据准备，并在 yudao-server 中启用量化回测配置。" type="warning" :closable="false" />
    <el-form :model="form" label-width="110px" class="mt-4" @submit.prevent="submit">
      <el-form-item label="策略版本"><el-select v-model="form.strategyVersionId" class="w-100%" placeholder="请选择不可变策略版本"><el-option v-for="item in strategyVersions" :key="item.id" :label="`${item.strategyName} · ${item.sourceHash.slice(0, 12)}`" :value="item.id" /></el-select></el-form-item>
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
      <el-table-column prop="datasetId" label="数据集" min-width="160" />
      <el-table-column prop="exchangeName" label="交易所" width="100" />
      <el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="提交时间" min-width="180"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString() }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="scope"><el-button link type="primary" @click="showDetail(scope.row.id)">详情</el-button></template></el-table-column>
    </el-table>
  </ContentWrap>
  <el-dialog v-model="detailVisible" title="回测结果与实验记录" width="80%">
    <template v-if="selected">
      <el-alert v-if="selected.errorMessage" :title="selected.errorMessage" type="error" :closable="false" />
      <el-descriptions :column="1" border>
        <el-descriptions-item label="任务编号">{{ selected.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(selected.status) }}</el-descriptions-item>
        <el-descriptions-item label="策略 SHA-256">{{ selected.strategyHash }}</el-descriptions-item>
        <el-descriptions-item label="策略版本">{{ selected.strategyName }} / {{ selected.strategyVersionId }}</el-descriptions-item>
        <el-descriptions-item label="行情 SHA-256">{{ selected.datasetHash }}</el-descriptions-item>
        <el-descriptions-item label="数据来源">{{ selected.datasetSource }}</el-descriptions-item>
        <el-descriptions-item label="引擎版本">{{ selected.engineVersion || '尚未完成' }}</el-descriptions-item>
        <el-descriptions-item label="参数">{{ selected.parametersJson }}</el-descriptions-item>
      </el-descriptions>
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
    <el-table :data="comparisons"><el-table-column prop="strategyName" label="策略" /><el-table-column prop="datasetId" label="数据集" /><el-table-column prop="totalTrades" label="成交数" /><el-table-column label="净收益"><template #default="s">{{ Number(s.row.netProfit).toFixed(4) }}</template></el-table-column><el-table-column label="收益率"><template #default="s">{{ (Number(s.row.returnRatio) * 100).toFixed(3) }}%</template></el-table-column><el-table-column label="最大回撤"><template #default="s">{{ (Number(s.row.maxDrawdownRatio) * 100).toFixed(3) }}%</template></el-table-column></el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createBacktest, listBacktests, getBacktest, getCapabilities, listStrategyVersions, listParameterSets, createParameterSet, compareBacktests, listDatasets, createDatasetDownload, listDatasetDownloads } from '@/api/quant/backtest'
import type { BacktestTask, StrategyVersion, ParameterSet, BacktestComparison, DatasetQuality, DatasetDownloadTask } from '@/api/quant/backtest'

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
    if (detailVisible.value && selected.value) selected.value = await getBacktest(selected.value.id)
  } finally { loading.value = false }
}
async function submitDownload() {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(downloadForm.datasetId) || downloadDates.value.length !== 2) { ElMessage.warning('请填写有效的数据集编号和日期区间'); return }
  downloadSubmitting.value = true
  try { await createDatasetDownload({ requestKey: crypto.randomUUID(), datasetId: downloadForm.datasetId, startDate: downloadDates.value[0], endDate: downloadDates.value[1] }); ElMessage.success('下载任务已提交'); await refresh() }
  finally { downloadSubmitting.value = false }
}
async function showDetail(id: string) { selected.value = await getBacktest(id); detailVisible.value = true }
const parseParameters = (item: ParameterSet) => JSON.parse(item.parametersJson)
const parameterLabel = (item: ParameterSet) => { const p = parseParameters(item); return `${p.startingBalance} / ${p.stakeAmount} / ${p.fee}` }
function applyParameterSet(id: string) { const item = parameterSets.value.find(value => value.id === id); if (item) Object.assign(form, parseParameters(item)) }
async function saveParameterSet() { form.parameterSetId = await createParameterSet({ startingBalance: form.startingBalance, stakeAmount: form.stakeAmount, fee: form.fee }); parameterSets.value = await listParameterSets(); ElMessage.success('参数集已保存') }
async function compare() { comparisons.value = await compareBacktests(selectedIds.value); compareVisible.value = true }
let timer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  enabled.value = (await getCapabilities()).enabled
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
