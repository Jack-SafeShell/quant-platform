<template>
  <el-collapse class="mt-3">
    <el-collapse-item title="修改策略（可选，已有策略可直接使用）" name="configuration">
      <p>适用于所选 BTC、ETH 现货及1小时/4小时历史行情。周期按K线根数计算，例如14根4小时K线为56小时。保存新版本后重新回看，已有版本和结果保留原规则。</p>
      <p>{{ explanations[form.template] }} 止损是允许下跌的比例，止盈是达到后尝试卖出的上涨比例。</p>
      <p v-if="form.template === 'RSI_REVERSION'">RSI 是0～100的涨跌强弱指标。例如从30以下回升到30以上时买入，升到70以上时卖出。若一次跳过两条线，不买入；持续下跌时也可能继续亏损。RSI模板当前仅用于历史回看。</p>
      <p v-if="form.template === 'BOLLINGER_RSI'">布林带用平均价格和波动大小画出上、中、下三条线。前一根收盘在下轨外或线上，当前收盘回到下轨上方且仍低于中轨，同时RSI比前一根上升、未超过买入上限时买入；价格回到中轨或上方时卖出。不是跌到下轨就买，持续下跌仍可能亏损。此模板仅用于历史回看。</p>
      <el-form :inline="true" @submit.prevent="save">
        <el-form-item label="买卖规则"><el-select v-model="form.template" style="width: 240px"><el-option label="均线交叉（EMA）" value="EMA" /><el-option label="价格突破" value="CHANNEL_BREAKOUT" /><el-option label="低位回升（RSI）" value="RSI_REVERSION" /><el-option label="布林带＋RSI回升" value="BOLLINGER_RSI" /></el-select></el-form-item>
        <el-form-item v-if="form.template === 'CHANNEL_BREAKOUT'" label="买入参考K线数"><el-input-number v-model="form.entryPeriod" :min="2" :max="120" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'CHANNEL_BREAKOUT'" label="卖出参考K线数"><el-input-number v-model="form.exitPeriod" :min="2" :max="120" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'EMA'" label="短期均线K线数"><el-input-number v-model="form.fastPeriod" :min="2" :max="119" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'EMA'" label="长期均线K线数"><el-input-number v-model="form.slowPeriod" :min="3" :max="120" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'BOLLINGER_RSI'" label="布林参考K线数"><el-input-number v-model="form.bandPeriod" :min="5" :max="100" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'BOLLINGER_RSI'" label="轨道宽度（标准差倍数）"><el-input-number v-model="form.bandDeviation" :min="1" :max="4" :step="0.1" :precision="2" /></el-form-item>
        <el-form-item v-if="['RSI_REVERSION', 'BOLLINGER_RSI'].includes(form.template)" label="RSI参考K线数"><el-input-number v-model="form.rsiPeriod" :min="2" :max="50" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'BOLLINGER_RSI'" label="RSI买入上限（刻度）"><el-input-number v-model="form.rsiCeiling" :min="10" :max="70" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'RSI_REVERSION'" label="买入线（向上越过）"><el-input-number v-model="form.entryThreshold" :min="1" :max="98" :precision="0" /></el-form-item>
        <el-form-item v-if="form.template === 'RSI_REVERSION'" label="卖出线（向上越过）"><el-input-number v-model="form.exitThreshold" :min="2" :max="99" :precision="0" /></el-form-item>
        <el-form-item label="止损 %"><el-input-number v-model="form.stopLossPercent" :min="0.1" :max="20" :step="0.1" :precision="4" /></el-form-item>
        <el-form-item label="止盈 %"><el-input-number v-model="form.takeProfitPercent" :min="0.1" :max="50" :step="0.1" :precision="4" /></el-form-item>
        <el-form-item><el-button v-hasPermi="['quant:backtest:create']" type="primary" :loading="saving" @click="save">保存为新策略并选中</el-button></el-form-item>
      </el-form>
      <p>保存只会新增一份买卖规则，已有回看结果保留原规则。选择新策略后，点击“开始历史回看”重新计算即可。</p>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createStrategyVersion, createBreakoutVersion, createRsiVersion, createBollingerRsiVersion, type StrategyVersion } from '@/api/quant/backtest'

const props = defineProps<{ versions: StrategyVersion[]; selectedVersionId: string }>()
const emit = defineEmits<{ created: [id: string] }>()
const saving = ref(false)
const form = reactive({ template: 'EMA', entryPeriod: 20, exitPeriod: 10, fastPeriod: 20, slowPeriod: 60, rsiPeriod: 14, entryThreshold: 30, exitThreshold: 70, bandPeriod: 20, bandDeviation: 2, rsiCeiling: 50, stopLossPercent: 2, takeProfitPercent: 4 })
const explanations: Record<string, string> = {
  EMA: '均线交叉：短期平均价格向上穿过长期平均价格时买入，向下穿过时卖出。',
  CHANNEL_BREAKOUT: '价格突破：价格突破此前参考K线的高点时买入，跌破参考低点时卖出。',
  RSI_REVERSION: '低位回升：RSI从买入线以下向上越过买入线时买入，向上越过卖出线时卖出。',
  BOLLINGER_RSI: '布林带＋RSI回升：价格从下轨外回到轨道内，且RSI回升时买入，回到中轨时卖出。'
}
watch(() => props.versions.find((v) => v.id === props.selectedVersionId), (version) => {
  const config = version?.configuration
  if (config) Object.assign(form, {
    ...('bandPeriod' in config ? { template: 'BOLLINGER_RSI', bandPeriod: config.bandPeriod, bandDeviation: config.bandDeviation, rsiPeriod: config.rsiPeriod, rsiCeiling: config.rsiCeiling } : 'rsiPeriod' in config ? { template: 'RSI_REVERSION', rsiPeriod: config.rsiPeriod, entryThreshold: config.entryThreshold, exitThreshold: config.exitThreshold } : 'entryPeriod' in config ? { template: 'CHANNEL_BREAKOUT', entryPeriod: config.entryPeriod, exitPeriod: config.exitPeriod } : { template: 'EMA', fastPeriod: config.fastPeriod, slowPeriod: config.slowPeriod }),
    stopLossPercent: Number((config.stopLossRatio * 100).toFixed(4)),
    takeProfitPercent: Number((config.takeProfitRatio * 100).toFixed(4))
  })
}, { immediate: true })

async function save() {
  const validPeriods = form.template === 'EMA'
    ? Number.isInteger(form.fastPeriod) && Number.isInteger(form.slowPeriod) && form.fastPeriod >= 2 && form.slowPeriod <= 120 && form.fastPeriod < form.slowPeriod
    : form.template === 'BOLLINGER_RSI'
      ? Number.isInteger(form.bandPeriod) && form.bandPeriod >= 5 && form.bandPeriod <= 100 && form.bandDeviation >= 1 && form.bandDeviation <= 4 && Number.isInteger(form.rsiPeriod) && form.rsiPeriod >= 2 && form.rsiPeriod <= 50 && Number.isInteger(form.rsiCeiling) && form.rsiCeiling >= 10 && form.rsiCeiling <= 70
    : form.template === 'RSI_REVERSION'
      ? Number.isInteger(form.rsiPeriod) && form.rsiPeriod >= 2 && form.rsiPeriod <= 50 && [form.entryThreshold, form.exitThreshold].every(v => Number.isInteger(v) && v >= 1 && v <= 99) && form.entryThreshold < form.exitThreshold
      : [form.entryPeriod, form.exitPeriod].every(v => Number.isInteger(v) && v >= 2 && v <= 120)
  if (saving.value) return
  if (!validPeriods
    || !(form.stopLossPercent >= 0.1 && form.stopLossPercent <= 20)
    || !(form.takeProfitPercent >= 0.1 && form.takeProfitPercent <= 50)) {
    ElMessage.warning(form.template === 'BOLLINGER_RSI' ? '请检查布林周期、轨道宽度、RSI周期、买入上限及止损止盈范围' : '请填写有效参数；EMA短期须小于长期，RSI买入线须低于卖出线')
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
      : form.template === 'BOLLINGER_RSI'
        ? await createBollingerRsiVersion({ bandPeriod: form.bandPeriod, bandDeviation: form.bandDeviation, rsiPeriod: form.rsiPeriod, rsiCeiling: form.rsiCeiling, ...risk })
      : form.template === 'RSI_REVERSION'
        ? await createRsiVersion({ rsiPeriod: form.rsiPeriod, entryThreshold: form.entryThreshold, exitThreshold: form.exitThreshold, ...risk })
        : await createBreakoutVersion({ entryPeriod: form.entryPeriod, exitPeriod: form.exitPeriod, ...risk })
    emit('created', id)
  } finally { saving.value = false }
}
</script>
