package com.qy.data.work.controller;

import com.alibaba.excel.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ethereal
 */
@RestController
@RequestMapping("/transfer-data")
@Slf4j
public class TransferDataController {

    @Autowired
    @Qualifier("jdbcTemplateSource")
    private JdbcTemplate source;

    @Autowired
    @Qualifier("jdbcTemplateTarget")
    private JdbcTemplate target;

    protected ThreadPoolExecutor executor = null;

    /**
     * 多线程迁移数据
     * @param tableName 标签
     * @return boolean
     */
    @PostMapping("/transferDataThread")
    public Boolean transferDataThread(@RequestParam String tableName) {
        long start = System.currentTimeMillis();
        // 线程共享的原子变量
        AtomicLong lastId = new AtomicLong(8663);
        AtomicInteger totalInserted = new AtomicInteger();
        AtomicBoolean hasMoreData = new AtomicBoolean(true);
        AtomicBoolean isShuttingDown = new AtomicBoolean(false);
        AtomicBoolean isCommit = new AtomicBoolean(false);
        // 每批次查询条数
        int batchSize = 10000;
        // 最大线程数
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        executor = new ThreadPoolExecutor(5, threadCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try (Connection connection = Objects.requireNonNull(target.getDataSource()).getConnection()) {
                        connection.setAutoCommit(false);

                        operateData(tableName, lastId, totalInserted, hasMoreData, batchSize, connection);

                        if (totalInserted.get() > 0 && isCommit.compareAndSet(false, true)) {
                            connection.commit();
                            log.info("线程：{}已提交剩余事务，迁移 {} 条数据",Thread.currentThread().getName(), totalInserted.get());
                        }

                    } catch (SQLException e) {
                        log.error("数据迁移过程中发生 SQL 错误：", e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Shutdown Hook，确保异常退出时提交事务
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.warn("程序异常中断，正在等待所有线程完成...");
                isShuttingDown.set(true);
                // 终止所有线程
                hasMoreData.set(false);

                try {
                    // 等待所有线程执行完毕
                    latch.await();
                    executor.shutdown();
                    log.warn("所有线程已结束，最终的 lastId: {}", lastId.get());

                    // 确保所有剩余数据提交
                    commitUnfinishedTransactions(totalInserted);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            // 等待所有线程完成
            latch.await();

        } catch (InterruptedException e) {
            log.error("主线程被中断，最后的 lastId: {}", lastId.get(), e);
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // 确保所有线程完成后，提交未提交的数据
            commitUnfinishedTransactions(totalInserted);

            log.info("数据迁移完成，最终的 lastId: {}", lastId.get());
            log.info("总耗时: {} 毫秒", (System.currentTimeMillis() - start));
        }

        return !hasMoreData.get();
    }

    private void operateData(String tableName, AtomicLong lastId, AtomicInteger totalInserted, AtomicBoolean hasMoreData, int batchSize, Connection connection) throws SQLException {
        while (hasMoreData.get()) {
            long startId = lastId.get();
            long endId = startId + batchSize;

            // 确保只有一个线程能成功更新 lastId
            if (lastId.compareAndSet(startId, endId)) {
                log.info("线程：{}已更新lastId为：{}", Thread.currentThread().getName(), lastId.get());
                long startSelect = System.currentTimeMillis();
                String selectDataSql = "SELECT * FROM " + tableName + " WHERE id > ? AND id <= ?";
                List<Map<String, Object>> rows = source.queryForList(selectDataSql, startId, endId);
                log.info("{}条数据查询用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startSelect));
                if (rows.isEmpty()) {
                    hasMoreData.set(false);
                    break;
                }

                // 构建批量插入SQL
                long startInsert = System.currentTimeMillis();
                StringBuilder batchInsertSql = new StringBuilder("INSERT INTO " + tableName + " VALUE ");
                for (Map<String, Object> row : rows) {
                    batchInsertSql.append("(");
                    for (Object value : row.values()) {
                        if (value == null) {
                            batchInsertSql.append("NULL,");
                        } else {
                            batchInsertSql.append("'").append(value.toString().replace("'", "''")).append("',");
                        }
                    }
                    batchInsertSql.setLength(batchInsertSql.length() - 1);
                    batchInsertSql.append("),");
                }
                batchInsertSql.setLength(batchInsertSql.length() - 1);

                target.execute(batchInsertSql.toString());
                log.info("{}条数据插入用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startInsert));

                totalInserted.addAndGet(rows.size());

                if (totalInserted.get() >= 300000) {
                    connection.commit();
                    log.info("线程：{}已提交事务，迁移 {} 条数据", Thread.currentThread().getName(), totalInserted.get());
                    totalInserted.set(0);
                }
            }
        }
    }

    private void commitUnfinishedTransactions(AtomicInteger totalInserted) {
        try (Connection connection = Objects.requireNonNull(target.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            if (totalInserted.get() > 0) {
                connection.commit();
                log.info("所有线程已结束，最终提交剩余数据: {} 条", totalInserted.get());
                totalInserted.set(0);
            }
        } catch (SQLException e) {
            log.error("提交剩余事务时发生错误：", e);
        }
    }


    /**
     * 较快 VALUE 多条数据 单线程
     * @param tableName 表名
     * @return boolean
     */
    @PostMapping("/transferData")
    public Boolean transferData(@RequestParam String tableName) {
        long start = System.currentTimeMillis();
        Connection connection = null;
        try {
            // 获取数据库连接
            connection = Objects.requireNonNull(target.getDataSource()).getConnection();
            connection.setAutoCommit(false);

            // 迁移表结构的
//            transferTableStructure(tableName);

            // 3. 分批次从source中获取数据并插入target
            int batchSize = 10000;
            // 记录上次查询的最大ID
            long lastId = 8663;
            long endId = lastId + batchSize;
            // 记录已插入的数据总数
            int totalInserted = 0;
            boolean hasMoreData = true;

            totalInserted = operateData(tableName, connection, batchSize, lastId, endId, totalInserted, hasMoreData);

            // 提交剩余未提交的数据
            if (totalInserted > 0) {
                connection.commit();
                log.info("已提交剩余事务，迁移 {} 条数据", totalInserted);
            }

            log.info("数据迁移完成，共耗时：{}毫秒", (System.currentTimeMillis() - start));
            return true;
        } catch (Exception e) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    log.error("事务回滚失败：", rollbackEx);
                }
            }
            log.error("数据迁移失败：", e);
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                } catch (SQLException closeEx) {
                    log.error("关闭连接失败：", closeEx);
                }
            }
        }
    }

    /**
     * 单线程处理数据时，只用返回一个totalInserted，判断是否有剩余数据未提交
     */
    private int operateData(String tableName, Connection connection, int batchSize, long lastId, long endId, int totalInserted, boolean hasMoreData) throws SQLException {
        while (hasMoreData) {
            long startSelect = System.currentTimeMillis();
            String selectDataSql = "SELECT * FROM " + tableName + " WHERE id > ? AND id <= ?";
            List<Map<String, Object>> rows = source.queryForList(selectDataSql, lastId, endId);
            log.info("{}条数据查询用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startSelect));
            if (rows.isEmpty()) {
                hasMoreData = false;
                continue;
            }

            // 更新
            lastId = (Long) rows.get(rows.size() - 1).get("id");
            endId = lastId + batchSize;

            // 构建批量插入SQL
            long startInsert = System.currentTimeMillis();
            StringBuilder batchInsertSql = new StringBuilder("INSERT INTO " + tableName + " VALUE ");
            for (Map<String, Object> row : rows) {
                batchInsertSql.append("(");
                for (Object value : row.values()) {
                    if (value == null) {
                        batchInsertSql.append("NULL,");
                    } else {
                        batchInsertSql.append("'").append(value.toString().replace("'", "''")).append("',");
                    }
                }
                batchInsertSql.setLength(batchInsertSql.length() - 1);
                batchInsertSql.append("),");
            }
            batchInsertSql.setLength(batchInsertSql.length() - 1);

            // 执行批量插入
            target.execute(batchInsertSql.toString());
            log.info("{}条数据插入用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startInsert));

            totalInserted += rows.size();
            if (totalInserted >= 300000) {
                connection.commit();
                log.info("已提交事务，迁移 {} 条数据", totalInserted);
                totalInserted = 0;
            }
        }
        return totalInserted;
    }

    /**
     * 较慢 单线程 单条数据插入
     * @param tableName 表名
     * @return boolean
     */
    @PostMapping("/transferData1")
    public boolean transferData1(@RequestParam String tableName) {
        long start = System.currentTimeMillis();
        Connection connection = null;
        PreparedStatement ps = null;
        try {
            // 获取数据库连接
            connection = Objects.requireNonNull(target.getDataSource()).getConnection();
            connection.setAutoCommit(false);

            transferTableStructure(tableName);

            // 3. 分批次从source中获取数据并插入target
            int batchSize = 100000;
            // 记录上次查询的最大ID
            long lastId = 8663;
            long endId = lastId + batchSize;
            boolean hasMoreData = true;
            int insertCount = 0;
            String insertSql = "INSERT INTO " + tableName + " VALUE (";
            while (hasMoreData) {
                // 分页查询数据
                long startSelect = System.currentTimeMillis();
                String selectDataSql = "SELECT * FROM " + tableName + " WHERE id > ? AND id <= ?";
                List<Map<String, Object>> rows = source.queryForList(selectDataSql, lastId, endId);
                log.info("{}条数据查询用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startSelect));
                if (rows.isEmpty()) {
                    hasMoreData = false;
                    continue;
                }

                // 更新
                lastId = (Long) rows.get(rows.size() - 1).get("id");
                endId = lastId + batchSize;

                // 准备批量插入
                long startInsert = System.currentTimeMillis();
                for (Map<String, Object> row : rows) {
                    StringBuilder singleInsertSql = new StringBuilder(insertSql);
                    for (Object value : row.values()) {
                        if (value == null) {
                            singleInsertSql.append("NULL,");
                        } else {
                            singleInsertSql.append("'").append(value.toString().replace("'", "''")).append("',");
                        }
                    }
                    singleInsertSql.setLength(singleInsertSql.length() - 1);
                    singleInsertSql.append(")");

                    if (ps == null) {
                        ps = connection.prepareStatement(singleInsertSql.toString());
                    }
                    ps.addBatch(singleInsertSql.toString());
                    insertCount++;

                    // 每5000次提交一次事务
                    if (insertCount % 100000 == 0) {
                        ps.executeBatch();
                        connection.commit();
                        ps.clearBatch();
                        log.info("已提交 {} 条数据", insertCount);
                    }
                }
                log.info("{}条数据插入用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startInsert));

                log.info("已完成迁移 {} 条数据", insertCount);
            }

            // 提交剩余未提交的数据
            if (insertCount % 100000 != 0) {
                ps.executeBatch();
                connection.commit();
                ps.clearBatch();
                log.info("已提交剩余 {} 条数据", insertCount % 100000);
            }

            log.info("数据迁移完成，共耗时：{}毫秒", (System.currentTimeMillis() - start));
            return true;
        } catch (Exception e) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    log.error("事务回滚失败：", rollbackEx);
                }
            }
            log.error("数据迁移失败：", e);
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                } catch (SQLException closeEx) {
                    log.error("关闭连接失败：", closeEx);
                }
            }
        }
    }

    private void transferTableStructure(String tableName) {
        // 1. 从source库中获取表结构
        String createTableSql = "SHOW CREATE TABLE " + tableName;
        Map<String, Object> createTableResult = source.query(createTableSql, rs -> {
            if (rs.next()) {
                return Map.of("Create Table", rs.getString(2));
            }
            return null;
        });

        String createTableStatement = null;
        if (createTableResult != null) {
            createTableStatement = (String) createTableResult.get("Create Table");
        } else {
            log.error("表{}未查询到建表语句", tableName);
        }

        if (StringUtils.isNotBlank(createTableStatement)) {
            // 2. 在target库中创建表
            target.execute(createTableStatement);
        }
    }

}
