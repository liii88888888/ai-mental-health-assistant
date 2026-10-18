package com.lqy.DTO.command;

import lombok.Data;

/**
 * 会话列表的分页查询参数。
 */
@Data
public class ConsultationSessionQueryDTO {

    private Long current = 1L;

    private Long size = 10L;
}
