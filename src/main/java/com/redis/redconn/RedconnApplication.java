package com.redis.redconn;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
@Slf4j
public class RedconnApplication implements CommandLineRunner {

	@Autowired
	private RedconnConfiguration config;

	@Autowired
	private ApplicationContext applicationContext;

	public static void main(String[] args) {
		SpringApplication.run(RedconnApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {

		switch (config.getDriver()) {
		case Lettuce:
			applicationContext.getBean(LettuceTest.class).run();
			break;
		default:
			applicationContext.getBean(JedisTest.class).run();
			break;
		}
	}




}
