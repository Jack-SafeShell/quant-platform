import request from '@/config/axios'
import type { StrategyConfiguration } from './backtest'

export interface StrategyExperimentRequest {
  requestKey: string
  strategyVersionIds: string[]
  parameterSetId: string
  datasetId: string
  trainStart: string
  splitDate: string
  validationEnd: string
}
export interface StrategyExperiment {
  id: string
  datasetId: string
  parameterSetId: string
  trainStart: string
  splitDate: string
  validationEnd: string
  createdAt: number
}
export interface ExperimentRow {
  strategyVersionId: string
  batchId: string
  configuration?: StrategyConfiguration | null
  trainStatus: string
  validationStatus: string
  trainReturn?: number | null
  validationReturn?: number | null
  validationDrawdown?: number | null
  validationTrades?: number | null
  overfitGap?: number | null
  rank?: number | null
  researchDecision: string
  paperEligible: boolean
  paperSessionId?: string
  paperSessionStatus?: string
  paperExecutionId?: string
  paperExecutionStatus?: string
}
export interface StrategyExperimentResult extends StrategyExperiment {
  parametersJson: string
  terminal: boolean
  autoSelected: false
  rows: ExperimentRow[]
}
export const createStrategyExperiment = (data: StrategyExperimentRequest): Promise<string> => request.post({ url: '/quant/backtest/strategy-experiment/create', data })
export const listStrategyExperiments = (): Promise<StrategyExperiment[]> => request.get({ url: '/quant/backtest/strategy-experiment/list' })
export const getStrategyExperiment = (id: string): Promise<StrategyExperimentResult> => request.get({ url: '/quant/backtest/strategy-experiment/get', params: { id } })

export interface PaperReviewRow {
 strategyVersionId: string; batchId: string; configuration?: StrategyConfiguration | null;
 sessionId?: string; sessionStatus?: string; executionId?: string; executionStatus?: string;
 telemetryAvailable: boolean; sampleState: string; snapshotCount: number; firstObservedAt?: number; lastObservedAt?: number; observedAt?: number;
 realizedProfit?: number; realizedReturnRatio?: number; closedTrades?: number; openPositions?: number; openOrders?: number; investedStake?: number;
 unresolvedAlerts: number; reconciliationStatus?: string; unknownOrders?: number; networkErrors?: number; fatalErrors?: number;
}
export interface PaperExperimentReview { experimentId: string; startingBalance: number; rows: PaperReviewRow[]; generatedAt: number; autoSelected: false; basis: string }
export const getPaperExperimentReview = (id: string): Promise<PaperExperimentReview> => request.get({ url: '/quant/backtest/strategy-experiment/paper-review', params: { id } })
