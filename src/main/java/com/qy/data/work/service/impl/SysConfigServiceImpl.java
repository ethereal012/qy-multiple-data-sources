package com.qy.data.work.service.impl;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qy.data.work.entity.SysConfig;
import com.qy.data.work.service.SysConfigService;
import com.qy.data.work.mapper.SysConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
* @author ethereal
* @description 针对表【sys_config】的数据库操作Service实现
* @createDate 2025-03-01 15:35:02
*/
@Service
@DS("source")
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig>
    implements SysConfigService {

    @Autowired
    private SysConfigMapper sysConfigMapper;

    @Override
    public String getName0() {
        SysConfig sysConfig = sysConfigMapper.selectById("diagnostics.allow_i_s_tables");
        return sysConfig.getValue();
    }
}




