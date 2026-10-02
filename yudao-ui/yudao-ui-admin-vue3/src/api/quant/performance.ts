import request from '@/config/axios'
export interface PerformanceOrder { id: string; clientOrderId: string; side: string; status: string; filledAmount: number; averagePrice?: number; feeAmount?: number; feeCurrency?: string; rebateAmount?: number; rebateCurrency?: string; updatedAt: number }
export interface LivePerformance {
 sessionId: string; orderCount: number; filledOrderCount: number; activeOrderCount: number; missingCostOrderCount: number;
 buyTurnover: number; sellTurnover: number; grossCashFlow: number; grossPositionBtc: number; knownSignedCostsUsdt: number;
 netPositionBtc?: number; netCashFlow?: number; markPrice?: number; markCandleAt?: number; markedPositionValue?: number; netContribution?: number;
 accountEquityChange: number; valuationComplete: boolean; costsComplete: boolean; warnings: string[]; orders: PerformanceOrder[];
}
export const getLivePerformance = (sessionId: string): Promise<LivePerformance> => request.get({ url: '/quant/backtest/live-automation/performance', params: { sessionId } })
