package cn.iocoder.yudao.module.quant.engine;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Component
public class DatasetRegistry {
    public record Dataset(String id, String exchange, String sha256, String source,
                          Path directory, long firstTimestamp, long lastTimestamp, int candles) { }
    private final QuantProperties properties;
    public DatasetRegistry(QuantProperties properties) { this.properties = properties; }
    public static String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public Dataset load(String id, LocalDate start, LocalDate end) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("非法数据集编号");
        Path root = Path.of(properties.getWorkspace()).toAbsolutePath().resolve("datasets").toRealPath();
        Path dir = root.resolve(id).toRealPath();
        if (!dir.startsWith(root)) throw new IllegalArgumentException("数据集路径越界");
        Path manifest = dir.resolve("manifest.json").toRealPath();
        Path data = dir.resolve("BTC_USDT-1h.json").toRealPath();
        if (!manifest.startsWith(dir) || !data.startsWith(dir)) throw new IllegalArgumentException("数据文件路径越界");
        if (Files.size(data) > 10_000_000 || Files.size(manifest) > 16_384) throw new IllegalArgumentException("数据集过大");
        JsonNode meta = JsonUtils.getObjectMapper().readTree(Files.readString(manifest));
        String exchange = meta.path("exchange").asText();
        if (!Set.of("okx", "binance").contains(exchange) || !"BTC/USDT".equals(meta.path("pair").asText())
                || !"1h".equals(meta.path("timeframe").asText()) || !"spot".equals(meta.path("tradingMode").asText()))
            throw new IllegalArgumentException("仅支持 OKX/Binance BTC/USDT 现货 1h 数据");
        byte[] content = Files.readAllBytes(data);
        String digest = hash(content);
        if (!digest.equals(meta.path("sha256").asText())) throw new IllegalArgumentException("数据摘要不一致");
        JsonNode rows = JsonUtils.getObjectMapper().readTree(content);
        if (!rows.isArray() || rows.size() < 241) throw new IllegalArgumentException("行情不足，须包含 240 根预热");
        long previous = -1;
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != 6 || !row.get(0).isIntegralNumber()) throw new IllegalArgumentException("OHLCV 格式错误");
            long ts = row.get(0).asLong();
            if (ts % 3600000 != 0 || (previous >= 0 && ts != previous + 3600000)) throw new IllegalArgumentException("行情时间缺口或重复");
            for (int i = 1; i < 6; i++) if (!row.get(i).isNumber() || !Double.isFinite(row.get(i).asDouble())
                    || row.get(i).asDouble() < 0 || (i < 5 && row.get(i).asDouble() == 0)) throw new IllegalArgumentException("OHLCV 数值无效");
            double high = row.get(2).asDouble(), low = row.get(3).asDouble();
            if (high < low || high < row.get(1).asDouble() || high < row.get(4).asDouble()
                    || low > row.get(1).asDouble() || low > row.get(4).asDouble()) throw new IllegalArgumentException("OHLC 关系无效");
            previous = ts;
        }
        long first = rows.get(0).get(0).asLong();
        long from = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long to = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        if (first > from - 240L * 3600000 || previous < to - 3600000 || to > Instant.now().toEpochMilli())
            throw new IllegalArgumentException("数据未覆盖预热或完整回测区间");
        return new Dataset(id, exchange, digest, meta.path("source").asText(), dir, first, previous, rows.size());
    }
}
