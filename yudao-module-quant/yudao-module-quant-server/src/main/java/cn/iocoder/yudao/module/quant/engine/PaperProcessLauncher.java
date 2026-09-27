package cn.iocoder.yudao.module.quant.engine;

import java.nio.file.Path;
import java.util.List;

public interface PaperProcessLauncher {
    Handle start(List<String> command, Path workDirectory, String containerName) throws Exception;
    void stop(String containerName) throws Exception;
    interface Handle { boolean isAlive(); int exitValue(); }
}
