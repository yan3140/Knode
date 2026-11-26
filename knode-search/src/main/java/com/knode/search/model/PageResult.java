package com.knode.search.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

public record PageResult(
        List<SearchArticleVo> rows,
        @JsonSerialize(using = ToStringSerializer.class) Long total
) {
}
