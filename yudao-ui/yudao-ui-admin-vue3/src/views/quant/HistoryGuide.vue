<template>
  <ContentWrap title="第一次使用？按这三步来">
    <div class="history-guide">
      <div><b>1. 选择策略和历史行情</b><p>选一个买卖规则，再选 Binance 或 OKX 的已有行情。现在支持 BTC/USDT 现货，每根 K 线代表 1 小时。</p></div>
      <div><b>2. 设置金额与回看时间</b><p>填入假设本金、每次买入金额和手续费。系统会计算这段过去的行情中，策略会怎样买卖。</p></div>
      <div><b>3. 查看赚亏和买卖记录</b><p>先看赚了多少、最多回落多少，再看具体买卖。需要比较多个策略时，进入“多策略比较”。</p></div>
    </div>
    <div class="guide-actions">
      <el-button v-if="route.path !== '/quant/backtest'" type="primary" @click="router.push('/quant/backtest')">开始历史回看</el-button>
      <el-button v-if="route.path !== '/quant/experiments'" @click="router.push('/quant/experiments')">比较多个策略</el-button>
      <span>使用历史数据计算，不需要交易所密钥。历史表现不代表未来收益。</span>
    </div>
    <el-collapse class="mt-3">
      <el-collapse-item title="看不懂这些词？点这里查看解释" name="words">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="回测 / 历史回看">把策略放回过去的行情中，计算当时按规则买卖会有什么结果。</el-descriptions-item>
          <el-descriptions-item label="收益率">相对假设本金的赚亏比例。例如本金 1000 USDT、净赚 10 USDT，收益率为 1%。</el-descriptions-item>
          <el-descriptions-item label="最大回撤">测试期间，资产从某次高点回落的最大比例；它不是最终亏损，也不是今后亏损的上限。</el-descriptions-item>
          <el-descriptions-item label="训练与验证">先用前一段历史比较方案，再用后一段历史检查表现是否还能保持；两段时间不混用。</el-descriptions-item>
          <el-descriptions-item label="手续费与滑点">手续费是交易成本；滑点是实际成交价格可能比预期更差，二者都会减少收益。</el-descriptions-item>
          <el-descriptions-item label="UTC 日期">本页日期统一按 UTC。UTC 的 00:00 是北京时间 08:00；结束日当天不计入回看。</el-descriptions-item>
        </el-descriptions>
      </el-collapse-item>
    </el-collapse>
  </ContentWrap>
</template>
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
const route = useRoute(), router = useRouter()
</script>
<style scoped>
.history-guide { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.history-guide > div { padding: 16px; border-radius: 8px; background: var(--el-fill-color-light); }
.history-guide p { margin-bottom: 0; line-height: 1.7; color: var(--el-text-color-regular); }
.guide-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 16px; }
.guide-actions span { color: var(--el-text-color-secondary); }
@media (max-width: 768px) { .history-guide { grid-template-columns: 1fr; } }
</style>
