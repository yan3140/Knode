package com.yan.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class StatusDto {
    @JsonAlias({"userId", "roleId"})
    private Long id;
    private String status;
}
