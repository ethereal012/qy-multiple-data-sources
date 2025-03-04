package com.qy.data.work.mapper;

import com.qy.data.work.entity.SysConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.beans.factory.annotation.Qualifier;

/**
* @author ethereal
* @description 针对表【sys_config】的数据库操作Mapper
* @createDate 2025-03-01 15:35:02
* @Entity com.example.mine.entity.SysConfig
*/
@Mapper
@Qualifier("sourceTransactionManager")
public interface SysConfigMapper extends BaseMapper<SysConfig> {

}




