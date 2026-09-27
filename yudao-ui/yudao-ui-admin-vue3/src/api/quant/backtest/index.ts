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
interface AuditReview { id:string;decision:string;comment:string;evidenceHash:string;createdAt:number }
export interface OptimizationResult extends OptimizationBatch { terminal: boolean; autoSelected: false; ranking: Array<{ parameterSetId:string; rank?:number; trainStatus:string; validationStatus:string; trainReturn?:number; validationReturn?:number; overfitGap?:number; validationDrawdown?:number; validationTrades?:number }>; researchDraft:{ruleVersion:string;evidenceSha256:string;conclusion:string;risks:string[];manualChecks:string[];autoApplied:false}; reviews:AuditReview[]; paperAdmission:{mode:'READ_ONLY';eligible:boolean;activationAllowed:false;evidenceSha256:string;checks:Array<{id:string;label:string;passed:boolean;evidence:string}>;reviews:AuditReview[]} }
export const getOptimization = (id:string): Promise<OptimizationResult> => request.get({ url:'/quant/backtest/optimization/get', params:{id} })
export const reviewOptimization=(id:string,data:{decision:'ACCEPTED'|'REJECTED';comment:string;evidenceHash:string}):Promise<string>=>request.post({url:'/quant/backtest/optimization/review',params:{id},data})
export const reviewPaperAdmission=(id:string,data:{decision:'READY'|'NOT_READY';comment:string;evidenceHash:string}):Promise<string>=>request.post({url:'/quant/backtest/optimization/admission/review',params:{id},data})
export const exportResearchReport=(id:string):Promise<Blob>=>request.download({url:'/quant/backtest/optimization/research-report',params:{id}})
export interface PaperSession { id:string;batch_id:string;parameter_set_id:string;admission_evidence_hash:string;status:'PENDING_APPROVAL'|'APPROVED'|'REJECTED';created_at:number;executionEnabled:false;activationAllowed:false;nextAction:string;reviews?:Array<{id:string;decision:string;comment:string;admissionEvidenceHash:string;createdAt:number}> }
export const createPaperSession=(data:{batchId:string;parameterSetId:string}):Promise<string>=>request.post({url:'/quant/backtest/paper-session/create',data})
export const listPaperSessions=():Promise<PaperSession[]>=>request.get({url:'/quant/backtest/paper-session/list'})
export const getPaperSession=(id:string):Promise<PaperSession>=>request.get({url:'/quant/backtest/paper-session/get',params:{id}})
export const reviewPaperSession=(id:string,data:{decision:'APPROVED'|'REJECTED';comment:string;admissionEvidenceHash:string}):Promise<string>=>request.post({url:'/quant/backtest/paper-session/review',params:{id},data})
export interface PaperReadinessSnapshot { id:string;manifestJson:string;manifestHash:string;ready:boolean;createdAt:number }
export const createPaperReadiness=(id:string):Promise<string>=>request.post({url:'/quant/backtest/paper-session/readiness/create',params:{id}})
export const listPaperReadiness=(id:string):Promise<PaperReadinessSnapshot[]>=>request.get({url:'/quant/backtest/paper-session/readiness/list',params:{id}})
export interface PaperExecution { id:string;session_id:string;readiness_hash:string;status:'WAITING_ENABLE'|'STARTING'|'RUNNING'|'STOP_REQUESTED'|'STOPPED'|'FAILED';container_name:string;created_at:number;executionEnabled:boolean;activationAllowed:false;containerStarted:false }
export const createPaperExecution=(sessionId:string):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/create',params:{sessionId}})
export const listPaperExecutions=():Promise<PaperExecution[]>=>request.get({url:'/quant/backtest/paper-execution/list'})
export const stopPaperExecution=(id:string,comment:string):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/stop',params:{id},data:{comment}})
export interface PaperCommandPreview { id:string;previewJson:string;previewHash:string;workDirectory:string;createdAt:number }
export const createPaperCommandPreview=(id:string):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/preview/create',params:{id}})
export const getPaperCommandPreview=(id:string):Promise<PaperCommandPreview>=>request.get({url:'/quant/backtest/paper-execution/preview/get',params:{id}})
