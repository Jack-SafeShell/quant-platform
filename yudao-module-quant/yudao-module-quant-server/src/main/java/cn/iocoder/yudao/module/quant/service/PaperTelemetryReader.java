package cn.iocoder.yudao.module.quant.service;

import org.sqlite.SQLiteConfig;

import java.nio.file.*;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

final class PaperTelemetryReader {
    private PaperTelemetryReader() {}

    static Map<String, Object> read(Path work, double initialBalance) {
        Map<String, Object> result = empty(initialBalance);
        Path database = work.resolve("tradesv3.dryrun.sqlite").normalize();
        if (!database.startsWith(work) || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(database)) return result;
        result.put("databasePresent", true);
        try {
            result.put("databaseBytes", Files.size(database));
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setBusyTimeout(2_000);
            try (Connection connection = config.createConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                int openPositions = integer(connection, "SELECT COUNT(*) FROM trades WHERE is_open=1");
                int closedTrades = integer(connection, "SELECT COUNT(*) FROM trades WHERE is_open=0");
                int openOrders = integer(connection, "SELECT COUNT(*) FROM orders WHERE ft_is_open=1");
                int totalOrders = integer(connection, "SELECT COUNT(*) FROM orders");
                double realizedProfit = decimal(connection, "SELECT COALESCE(SUM(realized_profit),0) FROM trades WHERE is_open=0");
                double investedStake = decimal(connection, "SELECT COALESCE(SUM(stake_amount),0) FROM trades WHERE is_open=1");
                result.put("openPositions", openPositions);
                result.put("closedTrades", closedTrades);
                result.put("openOrders", openOrders);
                result.put("totalOrders", totalOrders);
                result.put("realizedProfit", realizedProfit);
                result.put("investedStake", investedStake);
                result.put("estimatedAvailableBalance", initialBalance + realizedProfit - investedStake);
                result.put("latestTradeAt", text(connection, "SELECT MAX(COALESCE(close_date,open_date)) FROM trades"));
                result.put("latestOrderAt", text(connection, "SELECT MAX(COALESCE(order_update_date,order_filled_date,order_date)) FROM orders"));
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT timestamp,balance,total_quote,total_position_value FROM wallet_history ORDER BY timestamp DESC LIMIT 1")) {
                    if (rows.next()) {
                        result.put("latestWalletAt", rows.getString(1));
                        result.put("walletBalance", nullableDouble(rows, 2));
                        result.put("portfolioValue", nullableDouble(rows, 3));
                        result.put("positionValue", nullableDouble(rows, 4));
                    }
                }
                result.put("available", true);
            }
        } catch (Exception e) {
            result.put("error", "模拟盘数据库暂时不可读");
        }
        return result;
    }

    private static Map<String, Object> empty(double initialBalance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("databasePresent", false);
        result.put("databaseBytes", 0L);
        result.put("initialBalance", initialBalance);
        result.put("estimatedAvailableBalance", initialBalance);
        result.put("openPositions", 0);
        result.put("closedTrades", 0);
        result.put("openOrders", 0);
        result.put("totalOrders", 0);
        result.put("realizedProfit", 0.0);
        result.put("investedStake", 0.0);
        result.put("latestTradeAt", null);
        result.put("latestOrderAt", null);
        result.put("latestWalletAt", null);
        result.put("walletBalance", null);
        result.put("portfolioValue", null);
        result.put("positionValue", null);
        return result;
    }

    private static int integer(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static double decimal(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getDouble(1) : 0.0;
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static Double nullableDouble(ResultSet rows, int index) throws SQLException {
        double value = rows.getDouble(index);
        return rows.wasNull() ? null : value;
    }
}
