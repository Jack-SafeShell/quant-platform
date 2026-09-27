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
  <ContentWrap title="受控参数优化批次">
    <el-alert title="选择 2 至 5 个参数集；训练区间与验证区间完全分离，各至少 7 天。" type="info" :closable="false" />
    <el-form :inline="true" class="mt-4"><el-form-item label="参数集"><el-select v-model="optimization.parameterSetIds" multiple class="w-300px"><el-option v-for="item in parameterSets" :key="item.id" :label="parameterLabel(item)" :value="item.id" /></el-select></el-form-item><el-form-item label="训练/切分/验证"><el-date-picker v-model="optimizationDates" type="dates" value-format="YYYY-MM-DD" /></el-form-item><el-button type="primary" :disabled="!enabled" @click="submitOptimization">提交批次</el-button></el-form>
  <el-table :data="optimizationBatches"><el-table-column prop="dataset_id" label="数据集" /><el-table-column prop="train_start" label="训练开始" /><el-table-column prop="split_date" label="切分日" /><el-table-column prop="validation_end" label="验证结束" /><el-table-column label="操作"><template #default="s"><el-button link type="primary" @click="showOptimization(s.row.id)">结果</el-button></template></el-table-column></el-table>
  <h3>模拟盘会话审批</h3><el-alert title="会话和审批均为只读准备记录，执行始终关闭。" type="info" :closable="false" /><el-table :data="paperSessions" class="mt-3"><el-table-column prop="parameter_set_id" label="参数集" min-width="260" /><el-table-column prop="status" label="状态" /><el-table-column prop="nextAction" label="下一动作" /><el-table-column label="操作"><template #default="s"><el-button link type="primary" @click="showPaperSession(s.row.id)">审批详情</el-button></template></el-table-column></el-table>
  </ContentWrap>
  <el-dialog v-model="optimizationVisible" title="训练/验证结果" width="80%"><el-alert :title="optimizationResult?.terminal ? '结果按验证集收益率排序；系统不会自动采纳参数。' : '任务尚未全部结束，暂不生成排序。'" type="warning" :closable="false" /><el-table :data="optimizationResult?.ranking || []" class="mt-3"><el-table-column prop="rank" label="排名" /><el-table-column prop="parameterSetId" label="参数集" min-width="260" /><el-table-column prop="trainStatus" label="训练状态" /><el-table-column prop="validationStatus" label="验证状态" /><el-table-column prop="trainReturn" label="训练收益率" /><el-table-column prop="validationReturn" label="验证收益率" /><el-table-column prop="overfitGap" label="训练-验证差异" /><el-table-column prop="validationDrawdown" label="验证回撤" /><el-table-column prop="validationTrades" label="验证成交" /></el-table><template v-if="optimizationResult"><h3>研究结论草稿</h3><p>{{ optimizationResult.researchDraft.conclusion }}</p><p><b>证据摘要：</b>{{ optimizationResult.researchDraft.evidenceSha256 }}</p><el-alert v-for="risk in optimizationResult.researchDraft.risks" :key="risk" :title="risk" type="warning" :closable="false" class="mt-2" /><p><b>人工复核：</b>{{ optimizationResult.researchDraft.manualChecks.join('；') }}</p><el-button @click="downloadResearch">导出草稿</el-button><el-input v-model="reviewComment" class="mt-2" maxlength="500" placeholder="填写评审意见" /><el-button class="mt-2" type="success" @click="submitReview('ACCEPTED')">接受研究结论</el-button><el-button class="mt-2" type="danger" @click="submitReview('REJECTED')">拒绝</el-button><el-table :data="optimizationResult.reviews" class="mt-3"><el-table-column prop="decision" label="评审" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><h3>模拟盘准入（只读）</h3><el-alert title="该清单不会启动 dry-run、读取交易凭据或创建订单。" type="info" :closable="false" /><el-table :data="optimizationResult.paperAdmission.checks" class="mt-3"><el-table-column prop="label" label="检查项" /><el-table-column label="结果"><template #default="s"><el-tag :type="s.row.passed ? 'success' : 'danger'">{{ s.row.passed ? '通过' : '未通过' }}</el-tag></template></el-table-column><el-table-column prop="evidence" label="证据" min-width="300" /></el-table><el-input v-model="admissionComment" class="mt-2" maxlength="500" placeholder="填写准入确认意见" /><el-button class="mt-2" type="success" :disabled="!optimizationResult.paperAdmission.eligible" @click="submitAdmission('READY')">确认可进入模拟盘准备</el-button><el-button class="mt-2" type="danger" @click="submitAdmission('NOT_READY')">确认暂不准入</el-button><el-table :data="optimizationResult.paperAdmission.reviews" class="mt-3"><el-table-column prop="decision" label="准入决定" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><el-select v-model="paperParameterSetId" class="mt-3 w-300px" placeholder="人工选择参数集"><el-option v-for="row in optimizationResult.ranking.filter(x => x.validationReturn !== undefined)" :key="row.parameterSetId" :label="row.parameterSetId" :value="row.parameterSetId" /></el-select><el-button class="mt-3" type="primary" :disabled="!optimizationResult.paperAdmission.eligible || !paperParameterSetId" @click="submitPaperSession">创建待审批会话</el-button></template></el-dialog>
  <el-dialog v-model="paperSessionVisible" title="模拟盘会话启动审批" width="65%"><template v-if="selectedPaperSession"><el-descriptions :column="1" border><el-descriptions-item label="状态">{{ selectedPaperSession.status }}</el-descriptions-item><el-descriptions-item label="参数集">{{ selectedPaperSession.parameter_set_id }}</el-descriptions-item><el-descriptions-item label="准入证据">{{ selectedPaperSession.admission_evidence_hash }}</el-descriptions-item><el-descriptions-item label="执行能力">关闭</el-descriptions-item></el-descriptions><el-input v-model="sessionReviewComment" class="mt-3" maxlength="500" placeholder="填写启动审批意见" /><el-button class="mt-2" type="success" :disabled="selectedPaperSession.status !== 'PENDING_APPROVAL'" @click="submitPaperSessionReview('APPROVED')">批准启动准备</el-button><el-button class="mt-2" type="danger" :disabled="selectedPaperSession.status !== 'PENDING_APPROVAL'" @click="submitPaperSessionReview('REJECTED')">拒绝</el-button><el-table :data="selectedPaperSession.reviews || []" class="mt-3"><el-table-column prop="decision" label="决定" /><el-table-column prop="comment" label="意见" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><h3>启动前环境校验</h3><el-button type="primary" :disabled="selectedPaperSession.status !== 'APPROVED'" @click="generateReadiness">生成配置与校验快照</el-button><el-table :data="readinessSnapshots" class="mt-3"><el-table-column label="结果"><template #default="s"><el-tag :type="s.row.ready ? 'success' : 'danger'">{{ s.row.ready ? '通过' : '未通过' }}</el-tag></template></el-table-column><el-table-column prop="manifestHash" label="清单摘要" min-width="300" /><el-table-column label="时间"><template #default="s">{{ new Date(s.row.createdAt).toLocaleString() }}</template></el-table-column></el-table><el-alert v-if="readinessSnapshots.length" class="mt-3" :title="readinessSummary" type="info" :closable="false" /></template></el-dialog>
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
    <el-table :data="comparisons"><el-table-column prop="strategyName" label="策略" /><el-table-column prop="datasetId" label="数据集" /><el-table-column prop="totalTrades" label="成交数" /><el-table-column label="净收益"><template #default="s">{{ Number(s.row.netProfit).toFixed(4) }}</template></el-table-column><el-table-column label="收益率"><template #default="s">{{ (Number(s.row.returnRatio) * 100).toFixed(3) }}%</template></el-table-column><el-table-column label="最大回撤"><template #default="s">{{ (Number(s.row.maxDrawdownRatio) * 100).toFixed(3) }}%</template></el-table-column></el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createBacktest, listBacktests, getBacktest, getCapabilities, listStrategyVersions, listParameterSets, createParameterSet, compareBacktests, listDatasets, createDatasetDownload, listDatasetDownloads, exportBacktestReport, createOptimization, listOptimizations, getOptimization, reviewOptimization, reviewPaperAdmission, exportResearchReport, createPaperSession, listPaperSessions, getPaperSession, reviewPaperSession, createPaperReadiness, listPaperReadiness } from '@/api/quant/backtest'
import type { BacktestTask, StrategyVersion, ParameterSet, BacktestComparison, DatasetQuality, DatasetDownloadTask, OptimizationBatch, OptimizationResult, PaperSession, PaperReadinessSnapshot } from '@/api/quant/backtest'

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
