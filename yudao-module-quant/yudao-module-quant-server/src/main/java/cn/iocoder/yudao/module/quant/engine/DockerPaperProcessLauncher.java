package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component public class DockerPaperProcessLauncher implements PaperProcessLauncher {
 private final QuantProperties properties;public DockerPaperProcessLauncher(QuantProperties properties){this.properties=properties;}
 public Handle start(List<String> command,Path work,String container)throws Exception{Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("runtime.log").toFile())).start();return new Handle(){public boolean isAlive(){return process.isAlive();}public int exitValue(){return process.exitValue();}};}
 public void stop(String container)throws Exception{Process process=new ProcessBuilder(properties.getDockerExecutable(),"stop","--time","20",container).redirectErrorStream(true).start();if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("停止模拟盘容器超时");}String output=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);if(process.exitValue()!=0&&!output.contains("No such container"))throw new IllegalStateException("无法确认模拟盘容器已停止");}
}
