package com.cg.yangaiagent.model;

import lombok.Data;

@Data
public class LoveReport {
    private String userName;        // 用户名
    private String coreIssue;       // 核心症结
    private String advice;          // 总体建议
    private String riskWarning;     // 风险提醒
    // 你可以根据实际需求增加更多字段
}