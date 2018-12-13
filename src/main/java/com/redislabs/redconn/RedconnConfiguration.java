package com.redislabs.redconn;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "")
@EnableAutoConfiguration
public class RedconnConfiguration {

	public static enum Driver {
		Jedis, Lettuce
	}

	private Driver driver = Driver.Jedis;
	private SslConfiguration ssl;
	private DnsConfiguration dns = new DnsConfiguration();
	private String key = "foo";
	private String value = "bar";
	private SleepConfiguration sleep = new SleepConfiguration();
	private String clientName = "redconn";
	private int numKeys = 100;
	private int timeout = 300;
}
