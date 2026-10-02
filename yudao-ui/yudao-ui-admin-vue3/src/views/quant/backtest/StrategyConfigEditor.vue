<template>
  <el-collapse class="mt-3">
    <el-collapse-item title="配置 EMA 策略并保存新版本" name="configuration">
      <p>BTC/USDT 现货 · 1 小时；快周期 2～119、慢周期 3～120，快周期必须小于慢周期。保存后选中新版本，再提交回测；已有任务与版本保留原配置。</p>
      <el-form :inline="true" @submit.prevent="save">
        <el-form-item label="快 EMA"><el-input-number v-model="form.fastPeriod" :min="2" :max="119" :precision="0" /></el-form-item>
        <el-form-item label="慢 EMA"><el-input-number v-model="form.slowPeriod" :min="3" :max="120" :precision="0" /></el-form-item>
        <el-form-item label="止损 %"><el-input-number v-model="form.stopLossPercent" :min="0.1" :max="20" :step="0.1" :precision="4" /></el-form-item>
        <el-form-item label="止盈 %"><el-input-number v-model="form.takeProfitPercent" :min="0.1" :max="50" :step="0.1" :precision="4" /></el-form-item>
        <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="saving" @click="save">保存并选择版本</el-button></el-form-item>
      </el-form>
      <p>模拟盘沿用评审批次绑定的策略版本与参数集；实盘自动会话目前使用固定 EMA20/60，保存此处配置不会改变实盘运行。</p>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createStrategyVersion, type StrategyVersion } from '@/api/quant/backtest'

const props = defineProps<{ versions: StrategyVersion[]; selectedVersionId: string }>()
const emit = defineEmits<{ created: [id: string] }>()
const saving = ref(false)
const form = reactive({ fastPeriod: 20, slowPeriod: 60, stopLossPercent: 2, takeProfitPercent: 4 })
watch(() => props.versions.find((v) => v.id === props.selectedVersionId), (version) => {
  const config = version?.configuration
  if (config) Object.assign(form, {
    fastPeriod: config.fastPeriod, slowPeriod: config.slowPeriod,
    stopLossPercent: Number((config.stopLossRatio * 100).toFixed(4)),
    takeProfitPercent: Number((config.takeProfitRatio * 100).toFixed(4))
  })
}, { immediate: true })

async function save() {
  if (!Number.isInteger(form.fastPeriod) || !Number.isInteger(form.slowPeriod) || form.fastPeriod >= form.slowPeriod
    || !(form.stopLossPercent >= 0.1 && form.stopLossPercent <= 20)
    || !(form.takeProfitPercent >= 0.1 && form.takeProfitPercent <= 50)) {
    ElMessage.warning('请填写有效参数，快 EMA 周期必须小于慢 EMA 周期')
    return
  }
  saving.value = true
  try {
    const id = await createStrategyVersion({
      fastPeriod: form.fastPeriod, slowPeriod: form.slowPeriod,
      stopLossRatio: Number((form.stopLossPercent / 100).toFixed(6)),
      takeProfitRatio: Number((form.takeProfitPercent / 100).toFixed(6))
    })
    emit('created', id)
  } finally { saving.value = false }
}
</script>
