package com.macro.mall.search.config;

import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.RestClients;
import org.springframework.data.elasticsearch.config.AbstractElasticsearchConfiguration;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

@Configuration
public class ElasticsearchConfig extends AbstractElasticsearchConfiguration {

    @Override
    @Bean
    @SuppressWarnings("deprecation")//抑制弃用警告
    public RestHighLevelClient elasticsearchClient() {
        // 定义兼容模式的 Header
        HttpHeaders compatibilityHeaders = new HttpHeaders();
        compatibilityHeaders.add("Accept", "application/vnd.elasticsearch+json; compatible-with=7");
        compatibilityHeaders.add("Content-Type", "application/vnd.elasticsearch+json; compatible-with=7");

        ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectedTo("localhost:9200") // 你的 ES 地址
                .withConnectTimeout(Duration.ofSeconds(10))
                .withSocketTimeout(Duration.ofSeconds(30))
                .withDefaultHeaders(compatibilityHeaders) // 核心：注入兼容头
                .build();

        return RestClients.create(clientConfiguration).rest();
    }
}