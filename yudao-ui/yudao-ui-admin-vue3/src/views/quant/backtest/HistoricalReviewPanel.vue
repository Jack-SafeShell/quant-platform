<template>
  <section class="historical-review" v-loading="loading">
    <div class="review-title"><h3>历史复盘：策略比一直持币更好吗？</h3><el-button :loading="loading" @click="load">刷新复盘</el-button></div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <template v-if="review?.available">
      <p>复盘行情：{{ review.pair || 'BTC/USDT' }} 现货 · {{ periodLabel }}K线（UTC）。</p>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="策略合计赚亏">{{ money(review.summary.netProfit) }} USDT</el-descriptions-item>
        <el-descriptions-item label="一直持币赚亏">{{ money(review.summary.buyHoldNetProfit) }} USDT</el-descriptions-item>
        <el-descriptions-item label="策略比持币多赚 / 少赚">{{ percent(review.summary.excessReturnRatio) }} 个百分点</el-descriptions-item>
        <el-descriptions-item :label="`每${periodLabel}估值最大回撤`">{{ percent(review.summary.hourCloseMaxDrawdownRatio) }}%</el-descriptions-item>
      </el-descriptions>
      <p>持币对比：同样的本金，在区间第一根K线开盘买入，最后一根收盘卖出，两侧均扣所选手续费。不计额外滑点。</p>
      <h4>① 在行情图上查看买卖位置</h4>
      <p>蓝色三角是买入，橙色倒三角是卖出。点击标记或下方“看这笔”，即可定位交易。拖动底部滑条放大日期；时间统一为 UTC。</p>
      <div ref="priceEl" class="price-chart" role="img" :aria-label="`历史${periodLabel}K线与买卖点`" />
      <el-alert v-if="!review.trades.length" title="这段历史没有买卖；策略资金保持不变，持币曲线仅供比较。" type="info" :closable="false" />
      <el-table :data="review.trades" max-height="240" @row-click="selectTrade">
        <el-table-column prop="number" label="第几笔" width="70" />
        <el-table-column label="买入 UTC" min-width="150"><template #default="s">{{ utc(s.row.openedAt) }}</template></el-table-column>
        <el-table-column label="卖出 UTC" min-width="150"><template #default="s">{{ utc(s.row.closedAt) }}</template></el-table-column>
        <el-table-column label="净赚亏 USDT" width="130"><template #default="s">{{ money(s.row.netProfit) }}</template></el-table-column>
        <el-table-column label="卖出原因" min-width="140"><template #default="s">{{ exitLabel(s.row.exitReason) }}</template></el-table-column>
        <el-table-column width="85"><template #default="s"><el-button link type="primary" @click.stop="selectTrade(s.row)">看这笔</el-button></template></el-table-column>
      </el-table>
      <div v-if="selectedTrade" class="trade-explanation">
        <b>第 {{ selectedTrade.number }} 笔：{{ exitLabel(selectedTrade.exitReason) }}</b>
        <p>{{ utc(selectedTrade.openedAt) }} 以 {{ money(selectedTrade.openPrice) }} USDT 买入 {{ selectedTrade.amount }} {{ baseAsset }}，
          {{ utc(selectedTrade.closedAt) }} 以 {{ money(selectedTrade.closePrice) }} USDT 卖出；扣费后赚亏 {{ money(selectedTrade.netProfit) }} USDT。</p>
        <p>买入来自本次策略规则；旧导出未记录当时的指标值，无法逐项解释买入条件。K线不展示该周期内的完整价格路径。</p>
      </div>
      <h4>② 本金在这段历史中怎样变化</h4>
      <p>策略曲线＝本金＋已结赚亏＋当时持仓按K线收盘价估算的净赚亏；持仓估值预扣导出卖出费率。它是复盘估算，不是引擎原始资金曲线或实盘余额。</p>
      <Echart :options="equityOptions" height="280px" />
      <h4>③ 从高点回落了多少</h4>
      <p>下面按每根K线收盘估值计算回撤，可能漏掉周期内的低点，与上方原引擎最大回撤口径不同。回撤越深，历史上经历的资产回落越大。</p>
      <Echart :options="drawdownOptions" height="170px" />
      <h4>④ 每个月卖出结算了多少</h4>
      <p>按卖出月份汇总净赚亏，已包含导出手续费；比例以本次假设本金为分母。不是含未平持仓的月度资产收益，不能当作复利相加。</p>
      <el-table :data="review.months">
        <el-table-column prop="month" label="月份 UTC" />
        <el-table-column prop="trades" label="结算笔数" />
        <el-table-column label="已结赚亏 USDT"><template #default="s">{{ money(s.row.realizedNetProfit) }}</template></el-table-column>
        <el-table-column label="占初始本金"><template #default="s">{{ percent(s.row.realizedReturnRatio) }}%</template></el-table-column>
      </el-table>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Echart } from '@/components/Echart'
import echarts from '@/plugins/echarts'
import { CandlestickChart, ScatterChart } from 'echarts/charts'
import type { EChartsOption } from 'echarts'
import { getHistoricalReview, type HistoricalReview, type HistoricalTrade } from '@/api/quant/backtest'

echarts.use([CandlestickChart, ScatterChart])
const props = defineProps<{ taskId: string }>()
const loading = ref(false), error = ref(''), review = ref<HistoricalReview>(), selectedTrade = ref<HistoricalTrade>()
const priceEl = ref<HTMLElement>()
let priceChart: ReturnType<typeof echarts.init> | undefined, resizeObserver: ResizeObserver | undefined
let sequence = 0
const interval = computed(() => review.value?.intervalMillis || 3600000)
const periodLabel = computed(() => review.value?.timeframe === '4h' ? '4小时' : '1小时')
const baseAsset = computed(() => (review.value?.pair || 'BTC/USDT').split('/')[0])
const utc = (time: number) => new Date(time).toISOString().slice(0, 16).replace('T', ' ')
const money = (value: number) => value.toFixed(4)
const percent = (value: number) => (value * 100).toFixed(3)
const exitLabel = (reason: string) => ({ roi: '达到止盈条件', stop_loss: '触发止损', trailing_stop_loss: '触发移动止损', exit_signal: '策略发出卖出信号', rsi_upper_exit: 'RSI升破卖出线', bb_middle_exit: '价格回到布林中轨', breakout_atr_channel_exit: '价格跌破突破策略退出低点', ema_adx_down_exit: '短期均线向下穿过长期均线', force_exit: '历史区间结束结算' } as Record<string, string>)[reason] || reason
const unavailable = (reason?: string) => ({ TASK_NOT_COMPLETED: '任务尚未完成，请完成后查看复盘。', DATASET_CHANGED: '原行情摘要已变化，不能用新的行情替代这次回看的数据。', HISTORICAL_EVIDENCE_UNAVAILABLE: '原行情或回看证据不可用，暂时无法生成复盘。', MISSING_OR_UNSUPPORTED_TRADE_EVIDENCE: '这份旧结果缺少完整成交证据或口径不支持；保留原结果，不补造曲线。' } as Record<string,string>)[reason || ''] || '暂时无法生成这份结果的历史复盘。'
const equityOptions = computed<EChartsOption>(() => ({
  animation: false, tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { top: 0, data: ['策略估值 USDT', '一直持币 USDT'] },
  grid: { left: 70, right: 25, top: 40, bottom: 55 }, xAxis: { type: 'time', axisLabel: { formatter: value => utc(Number(value)).slice(0,10) } },
  yAxis: { type: 'value', scale: true }, dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 0, height: 18 }],
  series: [
    { name: '策略估值 USDT', type: 'line', showSymbol: false, data: review.value?.points.map(p => [p.time, Number(p.equity.toFixed(4))]) || [] },
    { name: '一直持币 USDT', type: 'line', showSymbol: false, data: review.value?.points.map(p => [p.time, Number(p.buyHold.toFixed(4))]) || [] }
  ]
}))
const drawdownOptions = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'axis', renderMode: 'richText' }, grid: { left: 70, right: 25, top: 15, bottom: 35 },
  xAxis: { type: 'time', axisLabel: { formatter: value => utc(Number(value)).slice(0,10) } }, yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
  series: [{ name: '每根K线估值回撤 %', type: 'line', showSymbol: false, areaStyle: {}, data: review.value?.points.map(p => [p.time, Number((p.drawdownRatio*100).toFixed(3))]) || [] }]
}))
function disposePrice() { resizeObserver?.disconnect(); resizeObserver = undefined; priceChart?.dispose(); priceChart = undefined }
function renderPrice() {
  if (!priceEl.value || !review.value?.available) return
  disposePrice()
  priceChart = echarts.init(priceEl.value)
  const data = review.value
  const labels = data.candles.map(c => utc(c[0]))
  const candleName = `${periodLabel.value}K线`
  const markerTime = (time: number) => utc(Math.min(Math.floor(time/interval.value)*interval.value, data.candles[data.candles.length-1][0]))
  priceChart.setOption({
    animation: false, tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { top: 0, data: [candleName, '买入', '卖出'] },
    grid: { left: 75, right: 30, top: 35, bottom: 55 }, xAxis: { type: 'category', data: labels }, yAxis: { type: 'value', scale: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 0, height: 18 }],
    series: [
      { name: candleName, type: 'candlestick', data: data.candles.map(c => [c[1],c[4],c[3],c[2]]), itemStyle: { color: '#16a085', color0: '#d9534f', borderColor: '#16a085', borderColor0: '#d9534f' } },
      { name: '买入', type: 'scatter', symbol: 'triangle', symbolSize: 13, itemStyle: { color: '#409eff' }, data: data.trades.map(t => ({ value: [markerTime(t.openedAt),t.openPrice], tradeNumber: t.number })) },
      { name: '卖出', type: 'scatter', symbol: 'triangle', symbolRotate: 180, symbolSize: 13, itemStyle: { color: '#e6a23c' }, data: data.trades.map(t => ({ value: [markerTime(t.closedAt),t.closePrice], tradeNumber: t.number })) }
    ]
  } as EChartsOption)
  priceChart.on('click', params => {
    const number = (params.data as { tradeNumber?: number })?.tradeNumber
    const trade = data.trades.find(t => t.number === number)
    if (trade) selectTrade(trade)
  })
  resizeObserver = new ResizeObserver(() => priceChart?.resize())
  resizeObserver.observe(priceEl.value)
}
function selectTrade(trade: HistoricalTrade) {
  selectedTrade.value = trade
  const candles = review.value?.candles
  if (!candles?.length) return
  const begin = candles[0][0]
  const from = Math.max(0, Math.floor((trade.openedAt-begin)/interval.value)-24)
  const to = Math.min(candles.length-1, Math.ceil((trade.closedAt-begin)/interval.value)+24)
  priceChart?.dispatchAction({ type: 'dataZoom', startValue: from, endValue: to })
}
async function load() {
  const request = ++sequence
  loading.value = true; error.value = ''; review.value = undefined; selectedTrade.value = undefined; disposePrice()
  try {
    const response = await getHistoricalReview(props.taskId)
    if (request !== sequence) return
    review.value = response
    if (!response.available) error.value = unavailable(response.reason)
    await nextTick()
    if (request === sequence) renderPrice()
  } catch { if (request === sequence) error.value = '复盘读取失败，请点击刷新重试；原回看结果仍可查看。' }
  finally { if (request === sequence) loading.value = false }
}
watch(() => props.taskId, load, { immediate: true })
onBeforeUnmount(() => { ++sequence; disposePrice() })
</script>
<style scoped>
.historical-review { margin-top: 24px; border-top: 1px solid var(--el-border-color); padding-top: 12px; min-height: 80px; }
.review-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.price-chart { height: 340px; width: 100%; }
.historical-review p { line-height: 1.7; color: var(--el-text-color-regular); }
.trade-explanation { background: var(--el-fill-color-light); padding: 12px 16px; margin-top: 12px; border-radius: 8px; }
</style>
