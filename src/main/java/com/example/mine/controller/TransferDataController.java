package com.example.mine.controller;

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

    /**
     * 较快 VALUES
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

//            // 1. 从source库中获取表结构
//            String createTableSql = "SHOW CREATE TABLE " + tableName;
//            Map<String, Object> createTableResult = source.query(createTableSql, rs -> {
//                if (rs.next()) {
//                    return Map.of("Create Table", rs.getString(2));
//                }
//                return null;
//            });
//
//            String createTableStatement = null;
//            if (createTableResult != null) {
//                createTableStatement = (String) createTableResult.get("Create Table");
//            } else {
//                log.error("表{}未查询到建表语句", tableName);
//            }
//
//            if (StringUtils.isNotBlank(createTableStatement)) {
//                // 2. 在target库中创建表
//                target.execute(createTableStatement);
//            }

            // 3. 分批次从source中获取数据并插入target
            int batchSize = 10000;
            boolean hasMoreData = true;
            // 记录上次查询的最大ID
            long lastId = 0;
            // 记录已插入的数据总数
            int totalInserted = 0;
            while (hasMoreData) {
                long startSelect = System.currentTimeMillis();
                String selectDataSql = "SELECT * FROM " + tableName +
                        " WHERE id > " + lastId +
                        " LIMIT " + batchSize;
                List<Map<String, Object>> rows = source.queryForList(selectDataSql);
                log.info("{}条数据查询用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startSelect));
                if (rows.isEmpty()) {
                    hasMoreData = false;
                    continue;
                }

                // 更新
                lastId = (Long) rows.get(rows.size() - 1).get("id");

                // 构建批量插入SQL
                long startInsert = System.currentTimeMillis();
                StringBuilder batchInsertSql = new StringBuilder("INSERT INTO " + tableName + " VALUES ");
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
     * 较慢
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

            // 3. 分批次从source中获取数据并插入target
            int batchSize = 100000;
            long offset = 0;
            boolean hasMoreData = true;
            int insertCount = 0;
            String insertSql = "INSERT INTO " + tableName + " VALUES (";
            while (hasMoreData) {
                // 分页查询数据
                long startSelect = System.currentTimeMillis();
                String selectDataSql = "SELECT * FROM " + tableName +
                        " LIMIT " + batchSize + " OFFSET " + offset;
                List<Map<String, Object>> rows = source.queryForList(selectDataSql);
                log.info("{}条数据查询用时：{}毫秒", rows.size(), (System.currentTimeMillis() - startSelect));
                if (rows.isEmpty()) {
                    hasMoreData = false;
                    continue;
                }

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

                offset += batchSize;
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


}
