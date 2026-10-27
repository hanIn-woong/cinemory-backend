package com.project.cinemory.domain.theater.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TheaterProperties.class)
public class TheaterConfig {
}
