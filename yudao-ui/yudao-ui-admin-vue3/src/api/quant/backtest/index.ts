import request from '@/config/axios'

export interface BacktestRequest {
  requestKey: string
  datasetId: string
  startDate: string
  endDate: string
  startingBalance: number
  stakeAmount: number
  fee: number
}

export interface BacktestTask {
  id: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  datasetId: string
  datasetHash: string
  datasetSource: string
  exchangeName: string
  strategyHash: string
  engineImage: string
  engineVersion?: string
  artifactHash?: string
  createdAt: number
  errorMessage?: string
  parametersJson: string
  resultJson?: string
}

export const createBacktest = (data: BacktestRequest): Promise<string> =>
  request.post({ url: '/quant/backtest/create', data })
export const listBacktests = (): Promise<BacktestTask[]> =>
  request.get({ url: '/quant/backtest/list' })
export const getBacktest = (id: string): Promise<BacktestTask> =>
  request.get({ url: '/quant/backtest/get', params: { id } })
export const getCapabilities = (): Promise<{ enabled: boolean }> =>
  request.get({ url: '/quant/backtest/capabilities' })
