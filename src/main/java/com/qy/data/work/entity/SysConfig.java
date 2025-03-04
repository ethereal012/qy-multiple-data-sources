package com.qy.data.work.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 
 * @TableName sys_config
 */
@TableName(value ="sys_config")
@Data
public class SysConfig implements Serializable {
    /**
     * 
     */
    @TableId
    private String variable;

    /**
     * 
     */
    private String value;

    /**
     * 
     */
    private Date set_time;

    /**
     * 
     */
    private String set_by;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}