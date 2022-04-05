package com.redis.redconn;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;
import lombok.ToString;
import redis.clients.jedis.Protocol;

@Data
@Configuration
@ConfigurationProperties(prefix = "")
@EnableAutoConfiguration
@ToString
public class RedconnConfiguration {

	public static enum Driver {
		Jedis, Lettuce
	}

	public enum SslProvider {
		Jdk, OpenSsl
	}

	private Driver driver = Driver.Jedis;
	private SleepConfiguration sleep = new SleepConfiguration();
	private String clientName = "redconn";
	private int numKeys = 100;
	private String dnsTtl = "0";
	private String dnsNegativeTtl = "0";
	private int database = 0;
	private String host = "localhost";
	private String password;
	private int port = 6379;
	private int connectionTimeout = 2000; //milliseconds
	private int socketTimeout = 2000; //milliseconds
	private int tcpKeepIdle = 10;
	private int	tcpKeepIntvl =1;
	private int  tcpKeepCnt =2;
	private int tcpUserTimeout = 10;
	private boolean ssl;
	private SslProvider sslProvider = SslProvider.Jdk;
	private KeystoreConfiguration keystore;
	private TruststoreConfiguration truststore;
}