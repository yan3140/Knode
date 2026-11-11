package com.knode.content.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown=true)
public record TagInput(@JsonDeserialize(using=MetadataIdDeserializer.class) Long id,String name,String remark) {}
