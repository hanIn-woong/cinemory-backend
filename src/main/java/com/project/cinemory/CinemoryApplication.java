package com.project.cinemory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class CinemoryApplication {

	public static void main(String[] args) {
		SpringApplication.run(CinemoryApplication.class, args);
	}

}
