package com.project.cinemory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// JPA Auditing 활성화는 global.config.JpaAuditingConfig가 전담한다.
// 여기에 @EnableJpaAuditing을 중복으로 붙이면 jpaAuditingHandler 빈이 두 번 등록돼
// BeanDefinitionOverrideException으로 컨텍스트 로딩이 실패한다.
@SpringBootApplication
public class CinemoryApplication {

	public static void main(String[] args) {
		SpringApplication.run(CinemoryApplication.class, args);
	}

}
