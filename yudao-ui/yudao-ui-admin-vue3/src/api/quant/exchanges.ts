import request from '@/config/axios'
export type MarketExchange = 'okx' | 'binance'
export interface PublicMarketSnapshot {
  exchange: MarketExchange; pair: string; timeframe: string; bid: number; ask: number
  exchangeTime: number; observedAt: number; lastClosedCandleAt: number
  closedCandles: number[][]; publicOnly: boolean
}
export interface TradingAccountSummary {
  id: string; exchange: MarketExchange; identityVerified: boolean; privateExecutionSupported: boolean
}
export interface BinanceConnection {
  connected: boolean; ordersSent: number; canTrade: boolean; enableReading: boolean
  enableSpotAndMarginTrading: boolean; enableWithdrawals: boolean; enableMargin: boolean
  enableFutures: boolean; ipRestrict: boolean
}
export const verifyBinanceAccount = (): Promise<BinanceConnection> => request.post({ url: '/quant/backtest/exchange-account/binance-verify' })
export const getPublicMarket = (exchange: MarketExchange): Promise<PublicMarketSnapshot> => request.get({ url: '/quant/backtest/market/snapshot', params: { exchange } })
export const getTradingAccount = (): Promise<TradingAccountSummary> => request.get({ url: '/quant/backtest/exchange-account/current' })
