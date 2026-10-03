import request from '@/config/axios'
import type { LiveRunPlan } from '@/api/quant/backtest'
export interface PortfolioAllocation { reportId:string;capital:number;orderNotional:number;maxSessionLoss:number;dailyNotional:number }
export interface PortfolioBudgetRequest { totalCapital:number;feeBps:number;slippageBps:number;allocations:PortfolioAllocation[] }
export interface PortfolioBudgetPlan {
 totals:{totalCapital:number;allocatedCapital:number;unallocatedCapital:number;combinedLossBudget:number;combinedDailyNotional:number;maxTotalExposure:number;maxSessionLoss:number;maxDailyNotional:number};
 allocations:{capital:number;dailyNotional:number;runPlan:LiveRunPlan}[];budgetValid:boolean;readOnly:boolean;fundsReserved:boolean;multiStrategyExecutionSupported:boolean;readyForPortfolioStart:boolean;sharedInstrument:boolean;exchange:string;pair:string;evidenceHash:string;
}
export const checkPortfolioBudget = (data:PortfolioBudgetRequest):Promise<PortfolioBudgetPlan> => request.post({url:'/quant/backtest/live-control/portfolio-budget',data})

export interface LivePortfolioMember {reportId:string;policyId?:string;sessionId?:string;capital:number;dailyNotional:number;performance?:import('./performance').LivePerformance}
export interface LivePortfolio {id:string;status:string;totalCapital:number;lossBudget:number;combinedNetContribution?:number;filledOrderCount?:number;combinedLoss?:number;costsComplete?:boolean;evidenceHash:string;stopReason?:string;configuration:PortfolioBudgetRequest;members:LivePortfolioMember[]}
export const savePortfolio=(data:PortfolioBudgetRequest):Promise<string>=>request.post({url:'/quant/backtest/live-portfolio/create',data})
export const listPortfolios=():Promise<LivePortfolio[]>=>request.get({url:'/quant/backtest/live-portfolio/list'})
export const getPortfolio=(id:string):Promise<LivePortfolio>=>request.get({url:'/quant/backtest/live-portfolio/get',params:{id}})
export const startPortfolio=(id:string)=>request.post({url:'/quant/backtest/live-portfolio/start',params:{id},data:{confirmation:'CONFIRM_PORTFOLIO_LIVE_START',comment:'运行已保存组合配置'}})
export const stopPortfolio=(id:string)=>request.post({url:'/quant/backtest/live-portfolio/stop',params:{id},data:{comment:'面板停止组合'}})
export const exitOwnedSession=(sessionId:string,requestId:string)=>request.post({url:'/quant/backtest/live-control/automation/exit',params:{sessionId},data:{requestId,confirmation:'CONFIRM_OWNED_SESSION_EXIT',comment:'退出原会话持仓，保留原账本归属'}})
