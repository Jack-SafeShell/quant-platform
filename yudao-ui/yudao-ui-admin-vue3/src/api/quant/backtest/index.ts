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
export const getCapabilities = (): Promise<{ enabled: boolean; executionEnabled: boolean; riskPolicyVersion: string; snapshotRetentionDays: number }> =>
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
export interface PaperExecution { id:string;session_id:string;readiness_hash:string;status:'WAITING_ENABLE'|'STARTING'|'RUNNING'|'STOP_REQUESTED'|'STOPPED'|'FAILED';container_name:string;created_at:number;executionEnabled:boolean;activationAllowed:false;containerStarted:boolean }
export const createPaperExecution=(sessionId:string):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/create',params:{sessionId}})
export const listPaperExecutions=():Promise<PaperExecution[]>=>request.get({url:'/quant/backtest/paper-execution/list'})
export const stopPaperExecution=(id:string,comment:string):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/stop',params:{id},data:{comment}})
export interface PaperCommandPreview { id:string;previewJson:string;previewHash:string;workDirectory:string;createdAt:number }
export const createPaperCommandPreview=(id:string):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/preview/create',params:{id}})
export const getPaperCommandPreview=(id:string):Promise<PaperCommandPreview>=>request.get({url:'/quant/backtest/paper-execution/preview/get',params:{id}})
export interface PaperStartToken { id:string;executionId:string;previewHash:string;token:string;status:'ISSUED';issuedAt:number;expiresAt:number;singleDisplay:true;executionStarted:false }
export const issuePaperStartToken=(id:string,data:{previewHash:string;confirmation:'CONFIRM_DRY_RUN_START';comment:string}):Promise<PaperStartToken>=>request.post({url:'/quant/backtest/paper-execution/start-token/issue',params:{id},data})
export const startPaperExecution=(id:string,data:{previewHash:string;token:string}):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/start',params:{id},data})
export interface PaperExecutionObservation { executionId:string;status:string;containerName:string;executionEnabled:boolean;preflightPassed:boolean;checks:Array<{id:string;passed:boolean;evidence:string}>;logExists:boolean;logTail:string;logTruncated:boolean;runtime:{engineRunningSeen:boolean;runningStartedAt?:number;lastHeartbeatAt?:number;lastMarketDataAt?:number;networkErrorCount:number;fatalErrorCount:number;soakSeconds:number;heartbeatAgeSeconds:number;soakPassed:boolean;currentHealthy:boolean};portfolio:{available:boolean;databasePresent:boolean;databaseBytes:number;initialBalance:number;estimatedAvailableBalance:number;openPositions:number;closedTrades:number;openOrders:number;totalOrders:number;realizedProfit:number;investedStake:number;latestTradeAt?:string;latestOrderAt?:string;latestWalletAt?:string;walletBalance?:number;portfolioValue?:number;positionValue?:number;error?:string};observedAt:number }
export const observePaperExecution=(id:string,lines=200):Promise<PaperExecutionObservation>=>request.get({url:'/quant/backtest/paper-execution/observation',params:{id,lines}})
export interface PaperObservationSnapshot { id:string;executionStatus:string;heartbeatAgeSeconds:number;networkErrorCount:number;fatalErrorCount:number;soakSeconds:number;soakPassed:boolean;estimatedAvailableBalance:number;openPositions:number;openOrders:number;evidenceHash:string;observedAt:number }
export interface PaperAlert { id:string;alertType:'STALE_HEARTBEAT'|'NETWORK_ERROR'|'FATAL_ERROR'|'PROCESS_EXITED'|'RISK_LIMIT_BREACH'|'ORDER_RECONCILIATION_FAILED';severity:'WARN'|'HIGH';status:'OPEN'|'ACKNOWLEDGED'|'RESOLVED';evidence:string;firstObservedAt:number;lastObservedAt:number;acknowledgedAt?:number;acknowledgedBy?:number;resolvedAt?:number;resolutionComment?:string }
export interface PaperAlertAction { id:string;alertId:string;actionType:'ACKNOWLEDGE'|'RESOLVE';fromStatus:string;toStatus:string;actorId:number;comment:string;createdAt:number }
export interface PaperOrder { id:string;idempotencyKey:string;sourceOrderId:string;tradeId?:string;pairSymbol:string;orderSide:string;orderType:string;orderStatus:'OPEN'|'FILLED'|'CANCELED'|'UNKNOWN';price:number;amount:number;filled:number;cost:number;reconciliationStatus:'MATCHED'|'UNKNOWN';firstSeenAt:number;lastSeenAt:number }
export interface PaperOrderReconciliation { id:string;sourceOrderCount:number;ledgerOrderCount:number;unknownOrderCount:number;reconciliationStatus:'PASSED'|'FAILED';evidenceHash:string;errorMessage?:string;reconciledAt:number }
export const listPaperObservationSnapshots=(id:string):Promise<PaperObservationSnapshot[]>=>request.get({url:'/quant/backtest/paper-execution/observation-snapshots',params:{id}})
export const listPaperAlerts=(id:string):Promise<PaperAlert[]>=>request.get({url:'/quant/backtest/paper-execution/alerts',params:{id}})
export const actPaperAlert=(alertId:string,data:{action:'ACKNOWLEDGE'|'RESOLVE';comment:string}):Promise<string>=>request.post({url:'/quant/backtest/paper-execution/alert/action',params:{alertId},data})
export const listPaperAlertActions=(id:string):Promise<PaperAlertAction[]>=>request.get({url:'/quant/backtest/paper-execution/alert-actions',params:{id}})
export const listPaperOrders=(id:string):Promise<PaperOrder[]>=>request.get({url:'/quant/backtest/paper-execution/orders',params:{id}})
export const listPaperOrderReconciliations=(id:string):Promise<PaperOrderReconciliation[]>=>request.get({url:'/quant/backtest/paper-execution/reconciliations',params:{id}})
