<template>
  <ContentWrap title="历史回测">
    <el-alert title="固定 EMA20/EMA60 示例 · BTC/USDT 现货 · 1 小时 · 仅历史回测" type="info" :closable="false" />
    <p class="text-gray-500">用于验证研究流程。止损 2%、止盈 4%，包含 240 根预热；日期按 UTC，结束日不包含在区间内。</p>
    <el-alert v-if="!enabled" title="回测尚未启用。请先完成数据准备，并在 yudao-server 中启用量化回测配置。" type="warning" :closable="false" />
    <el-form :model="form" label-width="110px" class="mt-4" @submit.prevent="submit">
      <el-form-item label="策略版本"><el-select v-model="form.strategyVersionId" class="w-100%" placeholder="请选择不可变策略版本"><el-option v-for="item in strategyVersions" :key="item.id" :label="`${item.strategyName} · ${item.sourceHash.slice(0, 12)}`" :value="item.id" /></el-select></el-form-item>
      <el-form-item label="数据集编号"><el-input v-model="form.datasetId" placeholder="例如 okx-btc-202608" maxlength="64" /></el-form-item>
      <el-form-item label="UTC 日期区间"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日（包含）" end-placeholder="结束日（不含）" /></el-form-item>
      <el-form-item label="初始资金"><el-input-number v-model="form.startingBalance" :min="100" :max="1000000" /><span class="ml-2">USDT</span></el-form-item>
      <el-form-item label="单笔投入"><el-input-number v-model="form.stakeAmount" :min="10" :max="1000000" /><span class="ml-2">USDT</span></el-form-item>
      <el-form-item label="单边费率"><el-input-number v-model="form.fee" :min="0" :max="0.01" :step="0.0001" :precision="4" /><span class="ml-2">0.001 = 0.1%</span></el-form-item>
      <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="submitting" :disabled="!enabled" @click="submit">提交历史回测</el-button></el-form-item>
    </el-form>
  </ContentWrap>
  <ContentWrap title="我的回测任务（最近 100 条）">
    <el-button :loading="loading" @click="refresh">刷新</el-button>
    <el-table :data="tasks" class="mt-3" v-loading="loading">
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
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createBacktest, listBacktests, getBacktest, getCapabilities, listStrategyVersions } from '@/api/quant/backtest'
import type { BacktestTask, StrategyVersion } from '@/api/quant/backtest'

defineOptions({ name: 'QuantBacktest' })
interface Result {
  totalTrades: number
  netProfit: number
  returnRatio: number
  maxDrawdownRatio: number
  trades: Array<{ instrument: string; openedAt: string; closedAt: string; netProfit: number; exitReason: string }>
}
const form = reactive({ strategyVersionId: '', datasetId: '', startingBalance: 1000, stakeAmount: 100, fee: 0.001 })
const strategyVersions = ref<StrategyVersion[]>([])
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
  if (!form.strategyVersionId || !/^[A-Za-z0-9_-]{1,64}$/.test(form.datasetId) || dates.value?.length !== 2 || !dates.value[0] || !dates.value[1]) {
    ElMessage.warning('请选择策略版本并填写有效数据集编号和日期区间')
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
    if (detailVisible.value && selected.value) selected.value = await getBacktest(selected.value.id)
  } finally { loading.value = false }
}
async function showDetail(id: string) { selected.value = await getBacktest(id); detailVisible.value = true }
let timer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  enabled.value = (await getCapabilities()).enabled
  strategyVersions.value = await listStrategyVersions()
  if (strategyVersions.value.length) form.strategyVersionId = strategyVersions.value[0].id
  await refresh()
  timer = setInterval(() => { if (tasks.value.some(t => ['QUEUED', 'RUNNING'].includes(t.status))) void refresh().catch(() => {}) }, 5000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>
