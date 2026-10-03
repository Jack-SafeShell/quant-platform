<template>
  <el-card class="mt-3" shadow="never">
    <template #header>交易所行情与账户归属</template>
    <p v-if="account">当前交易账户：{{ account.exchange.toUpperCase() }} / {{ account.id }}；身份{{ account.identityVerified ? '已核对' : '待私有查询核对' }}</p>
    <el-select v-model="exchange" class="w-160px" @change="snapshot = undefined">
      <el-option label="Binance" value="binance" /><el-option label="OKX" value="okx" />
    </el-select>
    <el-button class="ml-2" :loading="loading" @click="loadMarket">查询公开行情</el-button>
    <p class="text-gray-500">行情选择不切换交易账户。BTC/USDT 现货，已收盘 1h K 线；Binance 当前只接入公开数据。</p>
    <el-descriptions v-if="snapshot" :column="3" border>
      <el-descriptions-item label="行情来源">{{ snapshot.exchange.toUpperCase() }}</el-descriptions-item>
      <el-descriptions-item label="买价 / 卖价">{{ snapshot.bid }} / {{ snapshot.ask }}</el-descriptions-item>
      <el-descriptions-item label="查询时间">{{ new Date(snapshot.observedAt).toLocaleString() }}</el-descriptions-item>
      <el-descriptions-item label="最新闭盘 K 线开盘时间">{{ new Date(snapshot.lastClosedCandleAt).toLocaleString() }}</el-descriptions-item>
      <el-descriptions-item label="闭盘 K 线数量">{{ snapshot.closedCandles.length }}</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>
<script setup lang="ts">
import { getPublicMarket, getTradingAccount } from '@/api/quant/exchanges'
import type { MarketExchange, PublicMarketSnapshot, TradingAccountSummary } from '@/api/quant/exchanges'
const exchange = ref<MarketExchange>('binance')
const account = ref<TradingAccountSummary>()
const snapshot = ref<PublicMarketSnapshot>()
const loading = ref(false)
async function loadMarket() {
  const selected = exchange.value
  loading.value = true
  snapshot.value = undefined
  try { const result = await getPublicMarket(selected); if (exchange.value === selected) snapshot.value = result }
  finally { loading.value = false }
}
onMounted(async () => { account.value = await getTradingAccount() })
</script>
