<template>
  <el-collapse class="mt-3">
    <el-collapse-item title="配置策略并保存新版本" name="configuration">
      <p>BTC/USDT 现货 · 1 小时。突破策略使用此前 N 根 K 线最高/最低价，排除当前 K 线；周期 2～120。保存后选中新版本，再提交回测；已有任务与版本保留原配置。</p>
      <el-form :inline="true" @submit.prevent="save">
        <el-form-item label="模板"><el-select v-model="form.template" style="width: 180px"><el-option label="EMA 交叉" value="EMA" /><el-option label="通道突破" value="CHANNEL_BREAKOUT" /></el-select></el-form-item>
        <el-form-item v-if="form.template === 'CHANNEL_BREAKOUT'" label="入场通道"><el-input-number v-model="form.entryPeriod" :min="2" :max="120" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'CHANNEL_BREAKOUT'" label="退出通道"><el-input-number v-model="form.exitPeriod" :min="2" :max="120" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'EMA'" label="快 EMA"><el-input-number v-model="form.fastPeriod" :min="2" :max="119" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'EMA'" label="慢 EMA"><el-input-number v-model="form.slowPeriod" :min="3" :max="120" :precision="0" /></el-form-item>
        <el-form-item label="止损 %"><el-input-number v-model="form.stopLossPercent" :min="0.1" :max="20" :step="0.1" :precision="4" /></el-form-item>
        <el-form-item label="止盈 %"><el-input-number v-model="form.takeProfitPercent" :min="0.1" :max="50" :step="0.1" :precision="4" /></el-form-item>
        <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="saving" @click="save">保存并选择版本</el-button></el-form-item>
      </el-form>
      <p>模拟盘沿用评审批次绑定的策略版本与参数集；EMA 和突破实盘均须绑定同版本回测与模拟证据、确认准入报告并通过资金门禁。保存配置不会改变已有会话。实盘止损止盈按已收盘 1h K 线判断，可能延迟至下一小时，不等同于引擎盘中撮合。</p>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createStrategyVersion, createBreakoutVersion, type StrategyVersion } from '@/api/quant/backtest'

const props = defineProps<{ versions: StrategyVersion[]; selectedVersionId: string }>()
const emit = defineEmits<{ created: [id: string] }>()
const saving = ref(false)
const form = reactive({ template: 'EMA', entryPeriod: 20, exitPeriod: 10, fastPeriod: 20, slowPeriod: 60, stopLossPercent: 2, takeProfitPercent: 4 })
watch(() => props.versions.find((v) => v.id === props.selectedVersionId), (version) => {
  const config = version?.configuration
  if (config) Object.assign(form, {
    ...('entryPeriod' in config ? { template: 'CHANNEL_BREAKOUT', entryPeriod: config.entryPeriod, exitPeriod: config.exitPeriod } : { template: 'EMA', fastPeriod: config.fastPeriod, slowPeriod: config.slowPeriod }),
    stopLossPercent: Number((config.stopLossRatio * 100).toFixed(4)),
    takeProfitPercent: Number((config.takeProfitRatio * 100).toFixed(4))
  })
}, { immediate: true })

async function save() {
  const validPeriods = form.template === 'EMA'
    ? Number.isInteger(form.fastPeriod) && Number.isInteger(form.slowPeriod) && form.fastPeriod >= 2 && form.slowPeriod <= 120 && form.fastPeriod < form.slowPeriod
    : [form.entryPeriod, form.exitPeriod].every(v => Number.isInteger(v) && v >= 2 && v <= 120)
  if (!validPeriods
    || !(form.stopLossPercent >= 0.1 && form.stopLossPercent <= 20)
    || !(form.takeProfitPercent >= 0.1 && form.takeProfitPercent <= 50)) {
    ElMessage.warning('请填写有效参数；EMA 快周期须小于慢周期')
    return
  }
  saving.value = true
  try {
    const risk = {
      stopLossRatio: Number((form.stopLossPercent / 100).toFixed(6)),
      takeProfitRatio: Number((form.takeProfitPercent / 100).toFixed(6))
    }
    const id = form.template === 'EMA'
      ? await createStrategyVersion({ fastPeriod: form.fastPeriod, slowPeriod: form.slowPeriod, ...risk })
      : await createBreakoutVersion({ entryPeriod: form.entryPeriod, exitPeriod: form.exitPeriod, ...risk })
    emit('created', id)
  } finally { saving.value = false }
}
</script>
