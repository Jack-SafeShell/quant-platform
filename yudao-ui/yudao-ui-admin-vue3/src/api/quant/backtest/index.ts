import request from '@/config/axios'

export interface BacktestRequest {
  requestKey: string
  strategyVersionId: string
  parameterSetId: string
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
  strategyVersionId: string
  strategyName: string
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
export const exportBacktestReport = (id: string, format: 'md' | 'json'): Promise<Blob> =>
  request.download({ url: '/quant/backtest/report', params: { id, format } })
export const getCapabilities = (): Promise<{ enabled: boolean }> =>
  request.get({ url: '/quant/backtest/capabilities' })
export interface StrategyVersion {
  id: string
  strategyId: string
  strategyName: string
  sourceHash: string
}
export const listStrategyVersions = (): Promise<StrategyVersion[]> =>
  request.get({ url: '/quant/backtest/strategy-versions' })
export interface ParameterSet {
  id: string
  parametersJson: string
  parametersHash: string
}
export interface BacktestComparison {
  id: string
  strategyName: string
  datasetId: string
  totalTrades: number
  netProfit: number
  returnRatio: number
  maxDrawdownRatio: number
}
export interface DatasetQuality {
  id: string
  status: 'VALID' | 'INVALID'
  exchange: string
  pair: string
  timeframe: string
  tradingMode: string
  sha256: string
  source: string
  firstTimestamp: number
  lastTimestamp: number
  candles: number
  gaps: number
  error?: string
}
export const listParameterSets = (): Promise<ParameterSet[]> => request.get({ url: '/quant/backtest/parameter-sets' })
export const createParameterSet = (data: Pick<BacktestRequest, 'startingBalance' | 'stakeAmount' | 'fee'>): Promise<string> => request.post({ url: '/quant/backtest/parameter-set/create', data })
export const compareBacktests = (data: string[]): Promise<BacktestComparison[]> => request.post({ url: '/quant/backtest/compare', data })
export const listDatasets = (): Promise<DatasetQuality[]> => request.get({ url: '/quant/backtest/datasets' })
export interface DatasetDownloadTask {
  id: string; dataset_id: string; start_date: string; end_date: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'; candles?: number
  dataset_hash?: string; error_message?: string; created_at: number
}
export const createDatasetDownload = (data: { requestKey: string; datasetId: string; startDate: string; endDate: string }): Promise<string> => request.post({ url: '/quant/backtest/dataset-download/create', data })
export const listDatasetDownloads = (): Promise<DatasetDownloadTask[]> => request.get({ url: '/quant/backtest/dataset-download/list' })
export interface OptimizationBatch { id: string; dataset_id: string; train_start: string; split_date: string; validation_end: string; created_at: number }
export const createOptimization = (data: object): Promise<string> => request.post({ url: '/quant/backtest/optimization/create', data })
export const listOptimizations = (): Promise<OptimizationBatch[]> => request.get({ url: '/quant/backtest/optimization/list' })
export interface OptimizationResult extends OptimizationBatch { terminal: boolean; autoSelected: false; ranking: Array<{ parameterSetId:string; rank?:number; trainStatus:string; validationStatus:string; trainReturn?:number; validationReturn?:number; overfitGap?:number; validationDrawdown?:number; validationTrades?:number }> }
export const getOptimization = (id:string): Promise<OptimizationResult> => request.get({ url:'/quant/backtest/optimization/get', params:{id} })
