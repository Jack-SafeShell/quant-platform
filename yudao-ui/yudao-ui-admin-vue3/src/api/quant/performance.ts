import request from '@/config/axios'
import type { LiveStrategyBinding } from '@/api/quant/backtest'
export interface ExecutionTrace { id:string;candleAt:number;signalType:string;signalReason:string;status:string;message?:string;closePrice:number;fastEma:number;slowEma:number;decision?:string;decisionId?:string;gateReason?:string;clientOrderId?:string;signalHash:string;linkState:string;order?:PerformanceOrder & {exchangeOrderId?:string} }
export interface PerformanceOrder { costEvidenceComplete?:boolean; instrumentId?:string;price?:number;amount?:number;notional?:number;submittedAt?:number;exchangeOrderId?:string; id: string; clientOrderId: string; side: string; status: string; filledAmount: number; averagePrice?: number; feeAmount?: number; feeCurrency?: string; rebateAmount?: number; rebateCurrency?: string; updatedAt: number }
export interface LivePerformance {
 settlementState?: 'INCOMPLETE_EVIDENCE' | 'ACTIVE_ORDERS' | 'NO_FILLS' | 'OPEN_POSITION' | 'CLOSED_POSITION';closedPositionNetPnl?:number | null;
 strategy?:LiveStrategyBinding; legacyStrategyVersionId?:string; admissionReportId?:string;admissionReportHash?:string;executionTrace:ExecutionTrace[];traceLimit:number;signalCount:number;generatedAt:number;
 sessionId: string; orderCount: number; filledOrderCount: number; activeOrderCount: number; missingCostOrderCount: number;
 buyTurnover: number; sellTurnover: number; grossCashFlow: number; grossPositionBtc: number; knownSignedCostsUsdt: number;
 netPositionBtc?: number; netCashFlow?: number; markPrice?: number; markCandleAt?: number; markedPositionValue?: number; netContribution?: number;
 accountEquityChange: number; valuationComplete: boolean; costsComplete: boolean; warnings: string[]; orders: PerformanceOrder[];
}
export const getLivePerformance = (sessionId: string): Promise<LivePerformance> => request.get({ url: '/quant/backtest/live-automation/performance', params: { sessionId } })
export const refreshPerformanceOrder = (orderId: string) => request.post({ url: '/quant/backtest/live-control/order/refresh', params: { orderId } })
