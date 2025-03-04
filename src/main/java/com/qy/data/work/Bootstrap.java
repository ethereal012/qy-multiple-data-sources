package com.qy.data.work;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;

/**
 * @author ethereal
 */
@SpringBootApplication
@Configuration
@MapperScan(
        basePackages = "com.qy.data.work.mapper",
        sqlSessionFactoryRef = "sourceSqlSessionFactory"
)
public class Bootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args) {
        SpringApplication.run(Bootstrap.class, args);
        LOGGER.info("项目启动成功");
    }

}
