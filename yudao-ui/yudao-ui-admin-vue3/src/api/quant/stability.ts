import request from '@/config/axios'
import type { StrategyConfiguration } from './backtest'
import type { SegmentPlan } from './segments'

export const parameterLabels: Record<string, string> = { fastPeriod: 'EMA短期K线数', slowPeriod: 'EMA长期K线数', entryPeriod: '突破买入参考K线数', exitPeriod: '突破卖出参考K线数', rsiPeriod: 'RSI参考K线数', entryThreshold: 'RSI买入线', exitThreshold: 'RSI卖出线' }
export const stabilityParameters = (c?: StrategyConfiguration | null): string[] => !c ? [] : 'rsiPeriod' in c ? ['rsiPeriod', 'entryThreshold', 'exitThreshold'] : 'entryPeriod' in c ? ['entryPeriod', 'exitPeriod'] : ['fastPeriod', 'slowPeriod']
export interface StabilityRequest {
  requestKey: string; strategyVersionId: string; parameterSetId: string; datasetId: string;
  startDate: string; endDate: string; parameter: string; step: number
}
export interface StabilityVariant { role: 'LOWER' | 'BASE' | 'UPPER'; value: number; configuration: StrategyConfiguration; strategyHash: string; strategyVersionId?: string }
export interface StabilityPlan extends Omit<SegmentPlan, 'windows' | 'segmentCount'> { parameter: string; step: number; variants: StabilityVariant[]; minTrades: number }
export interface StabilityBatch extends StabilityPlan { id: string; createdAt: number }
export interface StabilityResult {
  id: string; plan: StabilityPlan; terminal: boolean; status: string;
  rows: Array<StabilityVariant & { taskId: string; status: string; available: boolean; totalTrades?: number; noFills?: boolean; returnRatio?: number; returnDelta?: number; netProfit?: number; maxDrawdownRatio?: number }>;
  summary?: { finding: string; minTrades: number; worstReturn: number; bestReturn: number; returnSpread: number; maxDrawdown: number; noFillVariants: number };
  evidenceHash: string; limitations: string[]; autoApplied: false; executionAllowed: false
}
export const previewStability = (data: StabilityRequest): Promise<StabilityPlan> => request.post({ url: '/quant/backtest/stability/preview', data })
export const createStability = (data: StabilityRequest): Promise<string> => request.post({ url: '/quant/backtest/stability/create', data })
export const listStability = (): Promise<StabilityBatch[]> => request.get({ url: '/quant/backtest/stability/list' })
export const getStability = (id: string): Promise<StabilityResult> => request.get({ url: '/quant/backtest/stability/get', params: { id } })
