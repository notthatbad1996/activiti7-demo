package com.yls.activiti7demo.pojo;

import lombok.Data;

/**
 * @author joe 2022-02-20 14:16
 */
@Data
public class BusinessTrip implements java.io.Serializable {

    /**
     * 流程定义id
     */
    private String processDefinitionId;
    private String user;
    private String location;
    private String reason;
    private String days;
}
