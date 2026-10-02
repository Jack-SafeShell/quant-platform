import request from '@/config/axios'
import type { EmaStrategyConfiguration } from './backtest'

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
  configuration?: EmaStrategyConfiguration | null
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
