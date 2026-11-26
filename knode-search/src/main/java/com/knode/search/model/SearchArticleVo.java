package com.knode.search.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.Date;
import java.util.List;
import java.util.Map;

public record SearchArticleVo(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String title,
        String summary,
        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        String categoryName,
        List<String> tagNames,
        String thumbnail,
        @JsonSerialize(using = ToStringSerializer.class) Long viewCount,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") Date createTime,
        Float score,
        Map<String, List<String>> highlights
) {
}
