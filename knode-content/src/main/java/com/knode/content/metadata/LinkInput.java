package com.knode.content.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown=true)
public record LinkInput(@JsonDeserialize(using=MetadataIdDeserializer.class) Long id,String name,String logo,String description,String address,String status) {}
