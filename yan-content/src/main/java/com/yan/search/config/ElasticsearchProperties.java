package com.yan.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blog.search.elasticsearch")
public class ElasticsearchProperties {
    private String url = "http://192.168.25.129:9200";
    private String indexName = "yan_blog_articles";

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }
}
