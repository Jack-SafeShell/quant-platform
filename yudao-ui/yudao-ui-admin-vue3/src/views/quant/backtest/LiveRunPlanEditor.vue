<template>
  <p>每单金额与亏损预算受系统和门禁上限约束。成本仅为研究假设，不改变订单报价或真实费用。</p>
  <el-form :inline="true" @submit.prevent="load(false)">
    <el-form-item label="每单 USDT"><el-input-number v-model="form.orderNotional" :min="0.01" :max="plan?.limits.maxOrderNotional" :precision="2" /></el-form-item>
    <el-form-item label="会话亏损上限 USDT"><el-input-number v-model="form.maxSessionLoss" :min="0.01" :max="plan?.limits.maxSessionLoss" :precision="2" /></el-form-item>
    <el-form-item label="单边手续费基点"><el-input-number v-model="form.feeBps" :min="0" :max="100" :precision="0" /></el-form-item>
    <el-form-item label="每边滑点基点"><el-input-number v-model="form.slippageBps" :min="0" :max="100" :precision="0" /></el-form-item>
    <el-button :loading="loading" :disabled="!reportId" @click="load(false)">检查运行准备</el-button>
    <el-button :disabled="!plan || dirty" @click="download">导出准备清单</el-button>
  </el-form>
  <el-alert v-if="error" :title="error" type="warning" :closable="false" />
  <template v-if="plan">
    <el-alert v-if="dirty" title="预算已修改，请重新检查；以下为上次检查结果。" type="warning" :closable="false" />
    <p>{{ strategyConfigurationLabel(plan.strategy?.configuration) }} · 版本 {{ plan.strategy?.strategyVersionId || '-' }}</p>
    <p>单笔上限 {{ plan.limits.maxOrderNotional }} / 单日上限 {{ plan.limits.maxDailyNotional }} / 总仓位上限 {{ plan.limits.maxTotalExposure }} / 亏损预算上限 {{ plan.limits.maxSessionLoss }} USDT</p>
    <el-table :data="plan.checks"><el-table-column prop="id" label="检查项" min-width="210" /><el-table-column label="准备情况" width="100"><template #default="s">{{ s.row.passed ? '已满足' : '待满足' }}</template></el-table-column><el-table-column prop="evidence" label="证据与缺口" min-width="320" /></el-table>
    <p>检查结果 {{ plan.readyForStartRequest ? '允许提交启动请求，仍须后端实时复核' : '运行准备尚未齐备' }}。只读检查不读取密钥、不请求交易所、不启动会话。已有会话的预算不会随编辑变化。</p>
  </template>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { getLiveRunPlan, strategyConfigurationLabel, type LiveRunBudget, type LiveRunPlan } from '@/api/quant/backtest'
const props=defineProps<{reportId?:string}>()
const emit=defineEmits<{budget:[value:LiveRunBudget | undefined]}>()
const form=reactive<LiveRunBudget>({orderNotional:5,maxSessionLoss:5,feeBps:10,slippageBps:5})
const plan=ref<LiveRunPlan>(),loading=ref(false),error=ref('')
let sequence=0
const dirty=computed(()=>!plan.value || (Object.keys(form) as (keyof LiveRunBudget)[]).some(key=>form[key]!==plan.value?.budget[key]))
watch(()=>[form.orderNotional,form.maxSessionLoss,form.feeBps,form.slippageBps],()=>emit('budget',dirty.value?undefined:{...form}))
watch(()=>props.reportId,()=>{plan.value=undefined;emit('budget',undefined);void load(true)}, {immediate:true})
async function load(defaults:boolean){
  const report=props.reportId, request=++sequence
  error.value=''; if(!report){loading.value=false;return}
  loading.value=true; emit('budget',undefined)
  try{
    const result=await getLiveRunPlan(report,defaults?undefined:{...form})
    if(request!==sequence || report!==props.reportId)return
    plan.value=result;Object.assign(form,result.budget);emit('budget',{...result.budget})
  }catch{if(request===sequence){plan.value=undefined;error.value='准备检查失败，请确认报告和预算范围后重试。'}}
  finally{if(request===sequence)loading.value=false}
}
function download(){
  if(!plan.value || dirty.value)return
  const url=URL.createObjectURL(new Blob([JSON.stringify(plan.value,null,2)],{type:'application/json'})),a=document.createElement('a')
  a.href=url;a.download=`run-plan-${plan.value.reportId}.json`;a.click();URL.revokeObjectURL(url)
}
</script>
