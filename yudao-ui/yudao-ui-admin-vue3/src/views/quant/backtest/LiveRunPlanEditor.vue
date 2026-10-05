<template>
  <p>每单金额与亏损预算受系统和门禁上限约束。成本仅为研究假设，不改变订单报价或真实费用。</p>
  <el-form :inline="true" @submit.prevent="load(false)">
    <el-form-item label="每单 USDT"><el-input-number v-model="form.orderNotional" :min="0.01" :max="plan?.limits.maxOrderNotional" :precision="2" /></el-form-item>
    <el-form-item label="会话亏损上限 USDT"><el-input-number v-model="form.maxSessionLoss" :min="0.01" :max="plan?.limits.maxSessionLoss" :precision="2" /></el-form-item>
    <el-form-item label="单边手续费基点"><el-input-number v-model="form.feeBps" :min="0" :max="100" :precision="0" /></el-form-item>
    <el-form-item label="每边滑点基点"><el-input-number v-model="form.slippageBps" :min="0" :max="100" :precision="0" /></el-form-item>
    <el-button :loading="loading" :disabled="!reportId" @click="load(false)">检查证据与预算</el-button>
    <el-button type="primary" :loading="loading" :disabled="!reportId" @click="load(false, true)">检查资金与交易规则</el-button>
    <el-button :disabled="loading || !plan || dirty" @click="download">导出准备清单</el-button>
  </el-form>
  <el-alert v-if="error" :title="error" type="warning" :closable="false" />
  <template v-if="plan">
    <el-alert v-if="dirty" title="预算已修改，请重新检查；以下为上次检查结果。" type="warning" :closable="false" />
    <p>{{ strategyConfigurationLabel(plan.strategy?.configuration) }} · 版本 {{ plan.strategy?.strategyVersionId || '-' }}</p>
    <p>候选来源 {{ plan.candidateExchange || '未核实' }} · 当前执行账户 {{ plan.executionAccount.exchange }} / {{ plan.executionAccount.id }}。选择候选不会切换交易所或密钥。</p>
    <el-descriptions v-if="plan.preflightPerformed" :column="3" border>
      <el-descriptions-item label="资金检查时间">{{ plan.checkedAt ? new Date(plan.checkedAt).toLocaleString() : '-' }}</el-descriptions-item>
      <el-descriptions-item label="可用 USDT">{{ plan.funds?.availableUsdt ?? '未核实' }}</el-descriptions-item>
      <el-descriptions-item label="可用 BTC">{{ plan.funds?.availableBtc ?? '未核实' }}</el-descriptions-item>
      <el-descriptions-item label="BTC 敞口 USDT">{{ plan.funds?.btcExposureUsdt ?? '未核实' }}</el-descriptions-item>
      <el-descriptions-item label="UTC 日预约 / 剩余额度">{{ plan.accountBudget?.dailyReservedNotional ?? '-' }} / {{ plan.accountBudget?.dailyRemainingNotional ?? '-' }}</el-descriptions-item>
      <el-descriptions-item label="首次买入预估 BTC / USDT">{{ plan.orderPreview?.amount ?? '-' }} / {{ plan.orderPreview?.notional ?? '-' }}</el-descriptions-item>
    </el-descriptions>
    <p>单笔上限 {{ plan.limits.maxOrderNotional }} / 单日上限 {{ plan.limits.maxDailyNotional }} / 总仓位上限 {{ plan.limits.maxTotalExposure }} / 亏损预算上限 {{ plan.limits.maxSessionLoss }} USDT</p>
    <el-table :data="plan.checks"><el-table-column prop="id" label="检查项" min-width="210" /><el-table-column label="准备情况" width="100"><template #default="s">{{ s.row.passed ? '已满足' : '待满足' }}</template></el-table-column><el-table-column prop="evidence" label="证据与缺口" min-width="320" /></el-table>
    <p>{{ plan.preflightPerformed ? (plan.readyForStartRequest ? '当前检查通过，可提交启动请求；后端仍须实时复核。' : '当前运行准备有缺口，详见检查项。') : '以上仅为本地证据与预算检查；启动前请检查资金与交易规则。' }} 检查不启动会话、下单或预约资金；资金检查使用当前部署的加密凭据只读查询交易所。余额、报价和额度可能变化，已有会话预算不会随编辑变化。</p>
  </template>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { getLiveRunPlan, checkLiveRunFunds, strategyConfigurationLabel, type LiveRunBudget, type LiveRunPlan } from '@/api/quant/backtest'
const props=defineProps<{reportId?:string}>()
const emit=defineEmits<{budget:[value:LiveRunBudget | undefined]}>()
const form=reactive<LiveRunBudget>({orderNotional:5,maxSessionLoss:5,feeBps:10,slippageBps:5})
const plan=ref<LiveRunPlan>(),loading=ref(false),error=ref('')
let sequence=0
const dirty=computed(()=>!plan.value || (Object.keys(form) as (keyof LiveRunBudget)[]).some(key=>form[key]!==plan.value?.budget[key]))
watch(()=>[form.orderNotional,form.maxSessionLoss,form.feeBps,form.slippageBps],()=>emit('budget',!dirty.value&&plan.value?.preflightPerformed&&plan.value.readyForStartRequest?{...form}:undefined))
watch(()=>props.reportId,()=>{plan.value=undefined;emit('budget',undefined);void load(true)}, {immediate:true})
async function load(defaults:boolean, exchange=false){
  const report=props.reportId, request=++sequence
  error.value=''; if(!report){loading.value=false;return}
  loading.value=true; emit('budget',undefined)
  try{
    const result=exchange?await checkLiveRunFunds(report,{...form}):await getLiveRunPlan(report,defaults?undefined:{...form})
    if(request!==sequence || report!==props.reportId)return
    plan.value=result;Object.assign(form,result.budget);emit('budget',result.preflightPerformed&&result.readyForStartRequest?{...result.budget}:undefined)
  }catch{if(request===sequence){plan.value=undefined;error.value='准备检查失败，请确认报告和预算范围后重试。'}}
  finally{if(request===sequence)loading.value=false}
}
function download(){
  if(!plan.value || dirty.value)return
  const url=URL.createObjectURL(new Blob([JSON.stringify(plan.value,null,2)],{type:'application/json'})),a=document.createElement('a')
  a.href=url;a.download=`run-plan-${plan.value.reportId}.json`;a.click();URL.revokeObjectURL(url)
}
</script>
