import request from '@/config/axios'
import type { StrategyConfiguration } from './backtest'

export interface SegmentsRequest {
  requestKey: string; strategyVersionId: string; parameterSetId: string; datasetId: string;
  startDate: string; endDate: string; segmentCount: number
}
export interface SegmentWindow { index: number; startDate: string; endDate: string; days: number }
export interface SegmentPlan {
  strategyVersionId: string; strategyHash: string; configuration?: StrategyConfiguration;
  parameterSetId: string; parametersJson: string; datasetId: string; datasetHash: string;
  exchange: string; pair: string; timeframe: string; startDate: string; endDate: string;
  segmentCount: number; windows: SegmentWindow[]
}
export interface SegmentBatch extends SegmentPlan { id: string; createdAt: number }
export interface SegmentResult {
  id: string; createdAt: number; plan: SegmentPlan; terminal: boolean; status: string;
  rows: Array<SegmentWindow & { taskId: string; status: string; available: boolean;
    noFills?: boolean; totalTrades?: number; returnRatio?: number; netProfit?: number; maxDrawdownRatio?: number }>;
  summary?: { positiveWindows: number; windowCount: number; noFillWindows: number;
    worstReturn: number; bestReturn: number; maxDrawdown: number; minTrades: number };
  limitations: string[]; evidenceHash: string; autoApplied: false; executionAllowed: false
}
export const previewSegments = (data: SegmentsRequest): Promise<SegmentPlan> => request.post({ url: '/quant/backtest/segments/preview', data })
export const createSegments = (data: SegmentsRequest): Promise<string> => request.post({ url: '/quant/backtest/segments/create', data })
export const listSegments = (): Promise<SegmentBatch[]> => request.get({ url: '/quant/backtest/segments/list' })
export const getSegments = (id: string): Promise<SegmentResult> => request.get({ url: '/quant/backtest/segments/get', params: { id } })
