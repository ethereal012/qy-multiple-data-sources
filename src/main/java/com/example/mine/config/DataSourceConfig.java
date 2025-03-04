package com.example.mine.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * @author ethereal
 */
@Configuration
@MapperScan(
        basePackages = "com.example.mine.mapper",
        sqlSessionFactoryRef = "sourceSqlSessionFactory"
)
public class DataSourceConfig {
    @Bean(name = "source")
    @ConfigurationProperties(prefix = "spring.datasource.source")
    public DataSource source() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "target")
    @ConfigurationProperties(prefix = "spring.datasource.target")
    public DataSource target() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "jdbcTemplateSource")
    public JdbcTemplate jdbcTemplateSource(@Qualifier("source") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean(name = "jdbcTemplateTarget")
    public JdbcTemplate jdbcTemplateTarget(@Qualifier("target") DataSource target) {
        return new JdbcTemplate(target);
    }

    @Bean(name = "sourceSqlSessionFactory")
    public SqlSessionFactory sourceSqlSessionFactory(@Qualifier("source") DataSource source) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/source/*.xml"));
        return bean.getObject();
    }

    @Bean(name = "targetSqlSessionFactory")
    public SqlSessionFactory targetSqlSessionFactory(@Qualifier("target") DataSource target) throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(target);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/target/*.xml"));
        return bean.getObject();
    }

    @Bean(name = "sourceTransactionManager")
    public PlatformTransactionManager sourceTransactionManager(@Qualifier("source") DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean(name = "targetTransactionManager")
    public PlatformTransactionManager targetTransactionManager(@Qualifier("target") DataSource target) {
        return new DataSourceTransactionManager(target);
    }
}
