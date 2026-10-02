<template>
  <el-alert title="组合预算仅用于规划，不预留账户资金、不启动策略。同一账户多策略同时交易仍需接入共享订单预算和持仓归属控制。" type="info" :closable="false" />
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
    <el-button :disabled="!plan || dirty || loading" @click="download">导出分配清单</el-button>
  </div>
  <el-alert v-if="error" class="mt-3" :title="error" type="warning" :closable="false" />
  <template v-if="plan">
    <el-alert v-if="dirty" class="mt-3" title="分配已修改，以下为上次检查结果；请重新检查。" type="warning" :closable="false" />
    <p>已分配 {{ plan.totals.allocatedCapital }} / 总资金 {{ plan.totals.totalCapital }} / 未分配 {{ plan.totals.unallocatedCapital }} USDT</p>
    <p>合计亏损预算 {{ plan.totals.combinedLossBudget }} / 系统上限 {{ plan.totals.maxSessionLoss }}；合计单日额度 {{ plan.totals.combinedDailyNotional }} / 系统上限 {{ plan.totals.maxDailyNotional }} USDT</p>
    <p>预算检查通过；未预留资金，组合不可启动。{{ plan.sharedInstrument ? '多个方案共用同一交易对，尚须统一订单预约与持仓归属。' : '' }}</p>
    <el-table :data="plan.allocations">
      <el-table-column label="策略与版本" min-width="280"><template #default="s">{{ strategyConfigurationLabel(s.row.runPlan.strategy?.configuration) }} · {{ s.row.runPlan.strategy?.strategyVersionId }}</template></el-table-column>
      <el-table-column label="独立方案缺口" min-width="360"><template #default="s">{{ s.row.runPlan.checks.filter((c: {passed:boolean})=>!c.passed).map((c: {id:string})=>c.id).join('、') || '独立准备检查齐备' }}</template></el-table-column>
    </el-table>
  </template>
</template>
<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { strategyConfigurationLabel, type LiveAdmissionSummary } from '@/api/quant/backtest'
import { checkPortfolioBudget, type PortfolioBudgetPlan, type PortfolioBudgetRequest } from '@/api/quant/portfolio'
defineProps<{reports:LiveAdmissionSummary[]}>()
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
