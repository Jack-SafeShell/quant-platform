<template>
  <el-alert title="检查预算后可保存固定组合配置；启动会重新核对各版本准入、资金及共享额度。停止组合会停止会话并撤销挂单，已有持仓仍归原会话。" type="info" :closable="false" />
  <el-form inline class="mt-3">
    <el-form-item label="组合资金 USDT"><el-input-number v-model="form.totalCapital" :min="0.01" :precision="2" /></el-form-item>
    <el-form-item label="单边手续费基点"><el-input-number v-model="form.feeBps" :min="0" :max="100" :precision="0" /></el-form-item>
    <el-form-item label="每边滑点基点"><el-input-number v-model="form.slippageBps" :min="0" :max="100" :precision="0" /></el-form-item>
  </el-form>
  <el-table :data="form.allocations">
    <el-table-column label="准入报告" min-width="320"><template #default="s"><el-select v-model="s.row.reportId" filterable style="width:100%"><el-option v-for="r in reports" :key="r.id" :value="r.id" :label="`${r.id} / ${r.confirmationState}`" /></el-select></template></el-table-column>
    <el-table-column v-for="field in fields" :key="field.key" :label="field.label" width="180"><template #default="s"><el-input-number v-model="s.row[field.key]" :min="0.01" :precision="2" style="width:150px" /></template></el-table-column>
    <el-table-column width="80"><template #default="s"><el-button link @click="form.allocations.splice(s.$index,1)">移除</el-button></template></el-table-column>
  </el-table>
  <div class="mt-3">
    <el-button :disabled="form.allocations.length >= 10" @click="add">添加方案</el-button>
    <el-button :loading="loading" :disabled="!form.allocations.length" @click="check">检查组合预算</el-button>
    <el-button v-hasPermi="['quant:backtest:create']" :disabled="!plan || dirty || loading" @click="save">保存组合配置</el-button>
    <el-button :disabled="!plan || dirty || loading" @click="download">导出分配清单</el-button>
  </div>
  <el-alert v-if="error" class="mt-3" :title="error" type="warning" :closable="false" />
  <template v-if="plan">
    <el-alert v-if="dirty" class="mt-3" title="分配已修改，以下为上次检查结果；请重新检查。" type="warning" :closable="false" />
    <p>已分配 {{ plan.totals.allocatedCapital }} / 总资金 {{ plan.totals.totalCapital }} / 未分配 {{ plan.totals.unallocatedCapital }} USDT</p>
    <p>合计亏损预算 {{ plan.totals.combinedLossBudget }} / 系统上限 {{ plan.totals.maxSessionLoss }}；合计单日额度 {{ plan.totals.combinedDailyNotional }} / 系统上限 {{ plan.totals.maxDailyNotional }} USDT</p>
    <p>预算检查通过，尚未预留交易所资金。运行将执行各策略资金、日额度及组合亏损限制。</p>
    <el-table :data="plan.allocations">
      <el-table-column label="策略与版本" min-width="280"><template #default="s">{{ strategyConfigurationLabel(s.row.runPlan.strategy?.configuration) }} · {{ s.row.runPlan.strategy?.strategyVersionId }}</template></el-table-column>
      <el-table-column label="独立方案缺口" min-width="360"><template #default="s">{{ s.row.runPlan.checks.filter((c: {passed:boolean})=>!c.passed).map((c: {id:string})=>c.id).join('、') || '独立准备检查齐备' }}</template></el-table-column>
    </el-table>
  </template>
  <el-divider>已保存组合</el-divider>
  <el-select v-model="selected" placeholder="选择固定配置" style="width:420px" :disabled="loading" @change="loadSelected"><el-option v-for="row in portfolios" :key="row.id" :label="`${row.id} / ${row.status}`" :value="row.id" /></el-select>
  <el-button :disabled="loading" @click="reload">刷新组合</el-button>
  <template v-if="saved">
    <p>状态 {{ saved.status }} · 总资金 {{ saved.totalCapital }} · 亏损 {{ saved.combinedLoss ?? '证据不完整' }} / {{ saved.lossBudget }} USDT</p>
    <p>组合净贡献 {{ saved.combinedNetContribution ?? '证据不完整' }} USDT（各会话最近闭盘估值） · {{ saved.filledOrderCount ?? 0 }} 单有成交；无成交不构成有效收益证据。</p>
    <el-alert v-if="saved.stopReason" :title="saved.stopReason" type="warning" :closable="false" />
    <el-table :data="saved.members"><el-table-column prop="reportId" label="报告" min-width="250" /><el-table-column prop="capital" label="资金 USDT" /><el-table-column prop="dailyNotional" label="日额度 USDT" /><el-table-column prop="sessionId" label="运行会话" min-width="260" /></el-table>
    <p v-if="!executionReady">启动需开启真实执行、自动策略开关并配置凭据；后端还会复核报告双确认和门禁。</p>
    <el-button v-hasPermi="['quant:backtest:create']" :disabled="loading || saved.status !== 'READY' || !executionReady" type="warning" @click="startSaved">启动此固定组合</el-button>
    <el-button v-hasPermi="['quant:backtest:create']" :disabled="loading || !['RUNNING','STOPPING'].includes(saved.status)" type="danger" @click="stopSaved">停止此组合</el-button>
  </template>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getCapabilities, strategyConfigurationLabel, type LiveAdmissionSummary } from '@/api/quant/backtest'
import { getPortfolio, listPortfolios, savePortfolio, startPortfolio, stopPortfolio, checkPortfolioBudget, type LivePortfolio, type PortfolioBudgetPlan, type PortfolioBudgetRequest } from '@/api/quant/portfolio'
import { ElMessageBox } from 'element-plus'
defineProps<{reports:LiveAdmissionSummary[]}>()
const portfolios=ref<LivePortfolio[]>([]),saved=ref<LivePortfolio>(),selected=ref<string>(),executionReady=ref(false)
onMounted(()=>{void reload()})
async function loadSelected(){saved.value=undefined;if(selected.value)saved.value=await getPortfolio(selected.value)}
async function reload(){if(loading.value)return;loading.value=true;try{await reloadData()}catch{error.value='组合读取失败，请刷新重试'}finally{loading.value=false}}
async function reloadData(){portfolios.value=await listPortfolios();const caps=await getCapabilities();executionReady.value=caps.liveExecutionEnabled&&caps.liveAutomationEnabled;await loadSelected()}
async function save(){if(!plan.value||dirty.value||loading.value)return;loading.value=true;try{selected.value=await savePortfolio(JSON.parse(checked.value));await reloadData()}catch{error.value='保存失败，请核对报告与预算'}finally{loading.value=false}}
async function startSaved(){const id=saved.value?.id;if(!id||loading.value)return;try{await ElMessageBox.confirm('将使用已保存的资金分配和版本启动真实自动交易。','启动固定组合',{type:'warning'});}catch{return}loading.value=true;try{await startPortfolio(id);await reloadData()}catch{error.value='组合启动未完成，请刷新核对准入、开关、资金及已有会话；不要重复新建组合'}finally{loading.value=false}}
async function stopSaved(){const id=saved.value?.id;if(!id||loading.value)return;loading.value=true;try{await stopPortfolio(id);await reloadData()}catch{error.value='组合停止尚未确认，请刷新检查挂单与会话'}finally{loading.value=false}}
const form=reactive<PortfolioBudgetRequest>({totalCapital:20,feeBps:10,slippageBps:5,allocations:[]})
const fields=[{key:'capital',label:'分配资金 USDT'},{key:'orderNotional',label:'每单 USDT'},{key:'maxSessionLoss',label:'亏损预算 USDT'},{key:'dailyNotional',label:'单日额度 USDT'}]
const plan=ref<PortfolioBudgetPlan>(),loading=ref(false),error=ref(''),checked=ref('')
const dirty=computed(()=>JSON.stringify(form)!==checked.value)
function add(){form.allocations.push({reportId:'',capital:10,orderNotional:5,maxSessionLoss:2,dailyNotional:10})}
async function check(){
  if(loading.value)return
  const snapshot=JSON.stringify(form);loading.value=true;error.value=''
  try{plan.value=await checkPortfolioBudget(JSON.parse(snapshot));checked.value=snapshot}
  catch{plan.value=undefined;error.value='预算检查失败：请核对总额、单笔/亏损限额、所属报告及版本重复；如接口未部署，请先完成后端部署。'}
  finally{loading.value=false}
}
function download(){
  if(!plan.value||dirty.value||loading.value)return
  const url=URL.createObjectURL(new Blob([JSON.stringify({request:JSON.parse(checked.value),plan:plan.value},null,2)],{type:'application/json'})),a=document.createElement('a')
  a.href=url;a.download='portfolio-budget.json';a.click();URL.revokeObjectURL(url)
}
</script>
