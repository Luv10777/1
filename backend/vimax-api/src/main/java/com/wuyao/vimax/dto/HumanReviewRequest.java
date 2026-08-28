package com.wuyao.vimax.dto;

import lombok.Data;

/**
 * 人工审核请求
 */
@Data
public class HumanReviewRequest {
    private Boolean approved;
    private String comment;
}
