import request from '@/config/axios'
import type { LiveRunPlan } from '@/api/quant/backtest'
export interface PortfolioAllocation { reportId:string;capital:number;orderNotional:number;maxSessionLoss:number;dailyNotional:number }
export interface PortfolioBudgetRequest { totalCapital:number;feeBps:number;slippageBps:number;allocations:PortfolioAllocation[] }
export interface PortfolioBudgetPlan {
 totals:{totalCapital:number;allocatedCapital:number;unallocatedCapital:number;combinedLossBudget:number;combinedDailyNotional:number;maxTotalExposure:number;maxSessionLoss:number;maxDailyNotional:number};
 allocations:{capital:number;dailyNotional:number;runPlan:LiveRunPlan}[];budgetValid:boolean;readOnly:boolean;fundsReserved:boolean;multiStrategyExecutionSupported:boolean;readyForPortfolioStart:boolean;sharedInstrument:boolean;exchange:string;pair:string;evidenceHash:string;
}
export const checkPortfolioBudget = (data:PortfolioBudgetRequest):Promise<PortfolioBudgetPlan> => request.post({url:'/quant/backtest/live-control/portfolio-budget',data})
