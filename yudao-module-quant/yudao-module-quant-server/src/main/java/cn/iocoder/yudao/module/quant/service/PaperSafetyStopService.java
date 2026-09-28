package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.engine.PaperProcessLauncher;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
public class PaperSafetyStopService {
    private final PaperExecutionRepository executions;private final PaperProcessLauncher launcher;
    public PaperSafetyStopService(PaperExecutionRepository executions,PaperProcessLauncher launcher){this.executions=executions;this.launcher=launcher;}
    public boolean stopForRisk(long tenant,long owner,String execution,String evidence)throws Exception{
        var task=executions.get(tenant,owner,execution);if(task==null)throw new IllegalArgumentException("模拟盘执行任务不存在");String from=(String)task.get("status");
        if(!Set.of("STARTING","RUNNING","STOP_REQUESTED").contains(from))return false;
        try{launcher.stop((String)task.get("container_name"));}catch(Exception e){if(executions.failActive(execution,"风险越界且专属容器停止结果未确认"))executions.audit(execution,owner,"RISK_STOP_FAILED",from,"FAILED",evidence+"；容器停止结果未确认");throw e;}
        if(!executions.stop(execution))return false;executions.revokeTokens(execution);executions.audit(execution,owner,"RISK_STOPPED",from,"STOPPED",evidence+"；已按任务绑定容器名精确停止");return true;
    }
}
