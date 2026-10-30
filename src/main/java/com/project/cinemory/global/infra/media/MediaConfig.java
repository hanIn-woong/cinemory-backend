package com.project.cinemory.global.infra.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

    @Bean
    public MediaStorage s3MediaStorage(MediaProperties properties) {
        return new S3MediaStorage(properties);
    }
}
