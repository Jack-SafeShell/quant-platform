package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.api.backtest.PaperExecutionStartRequest;
import cn.iocoder.yudao.module.quant.dal.PaperExecutionRepository;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;

@Service public class PaperExecutionGateService {
 private final PaperExecutionRepository repo;private final PaperStartTokenService tokens;private final QuantProperties properties;
 public PaperExecutionGateService(PaperExecutionRepository repo,PaperStartTokenService tokens,QuantProperties properties){this.repo=repo;this.tokens=tokens;this.properties=properties;}
 @Transactional public LaunchSpec authorize(long tenant,long owner,String id,PaperExecutionStartRequest request)throws Exception{var task=repo.getForUpdate(tenant,owner,id);if(task==null||!"WAITING_ENABLE".equals(task.get("status")))throw new IllegalArgumentException("仅待启用任务可启动");var preview=repo.preview(id);if(preview==null||!Objects.equals(preview.get("previewHash"),request.previewHash())||!Objects.equals(preview.get("previewHash"),DatasetRegistry.hash(((String)preview.get("previewJson")).getBytes(java.nio.charset.StandardCharsets.UTF_8))))throw new IllegalArgumentException("命令预览摘要不一致");Path root=Path.of(properties.getWorkspace()).toAbsolutePath().normalize(),work=Path.of((String)preview.get("workDirectory")).toAbsolutePath().normalize(),expected=root.resolve("paper").resolve(id).normalize();if(!work.equals(expected)||!work.startsWith(root.resolve("paper")))throw new IllegalArgumentException("执行工作目录不匹配");var json=JsonUtils.getObjectMapper().readTree((String)preview.get("previewJson"));String configHash=DatasetRegistry.hash(Files.readAllBytes(work.resolve("config.json"))),strategyHash=DatasetRegistry.hash(Files.readAllBytes(work.resolve("strategies/QuantEmaBaseline.py")));if(!configHash.equals(json.path("configHash").asText())||!strategyHash.equals(json.path("strategyHash").asText())||json.path("executed").asBoolean(true)||!Objects.equals(task.get("container_name"),json.path("containerName").asText()))throw new IllegalArgumentException("执行工作文件或命令清单已变化");List<String> command=new ArrayList<>();json.path("command").forEach(node->command.add(node.asText()));if(command.size()<10||!properties.getDockerExecutable().equals(command.getFirst())||!"run".equals(command.get(1))||!command.contains(properties.getImage())||!command.contains("trade")||command.stream().anyMatch(value->value.equals("--privileged")||value.contains("docker.sock")||value.equals("-p")||value.equals("--publish")))throw new IllegalArgumentException("命令清单不符合受控 dry-run 约束");tokens.consumeForStart(tenant,owner,id,request.previewHash(),request.token());if(!repo.starting(id))throw new IllegalArgumentException("任务状态已变化，请刷新");repo.audit(id,owner,"STARTING","WAITING_ENABLE","STARTING","令牌已原子消费，工作文件摘要复核通过");return new LaunchSpec(command,work,(String)task.get("container_name"));}
 public record LaunchSpec(List<String> command,Path workDirectory,String containerName){}
}
