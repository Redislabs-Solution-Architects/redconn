package com.redislabs.redconn;

import java.io.File;
import java.security.Security;
import java.time.Duration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.Builder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@SpringBootApplication
@Slf4j
public class RedconnApplication implements CommandLineRunner {

	private static final String DNS_CACHE_TTL = "networkaddress.cache.ttl";
	private static final String DNS_CACHE_NEGATIVE_TTL = "networkaddress.cache.negative.ttl";
	@Autowired
	private RedconnConfiguration config;

	public static void main(String[] args) {
		SpringApplication.run(RedconnApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		log.info("Setting {}={}", DNS_CACHE_TTL, config.getDnsTtl());
		Security.setProperty(DNS_CACHE_TTL, config.getDnsTtl());
		log.info("Setting {}={}", DNS_CACHE_NEGATIVE_TTL, config.getDnsNegativeTtl());
		Security.setProperty(DNS_CACHE_NEGATIVE_TTL, config.getDnsNegativeTtl());
		switch (config.getDriver()) {
		case Lettuce:
			runLettuce();
			break;
		default:
			runJedis();
			break;
		}
	}

	private void runJedis() throws InterruptedException {
		SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
		SSLParameters sslParameters = new SSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
		HostnameVerifier hostnameVerifier = null;
		GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
		log.info("Connecting using Jedis with {}", config);
		JedisPool jedisPool = new JedisPool(poolConfig, config.getHost(), config.getPort(),
				config.getConnectionTimeout(), config.getSocketTimeout(), config.getPassword(), config.getDatabase(),
				config.getClientName(), config.isSsl(), sslSocketFactory, sslParameters, hostnameVerifier);
		try {
			Jedis jedis = jedisPool.getResource();
			log.info("Connected to {}", jedis.getClient().getHost());
			int numKeys = config.getNumKeys();
			for (int index = 0; index < numKeys; index++) {
				jedis.set("key:" + index, "value" + index);
			}
			while (true) {
				try {
					for (int index = 0; index < numKeys; index++) {
						String value = jedis.get("key:" + index);
						if (value == null || !value.equals("value" + index)) {
							log.error("Incorrect value returned: " + value);
						}
					}
					if (log.isDebugEnabled()) {
						log.debug("Successfully performed GET on all {} keys", numKeys);
					}
					Thread.sleep(config.getSleep().getGet());
				} catch (Exception e) {
					jedis.close();
					jedis = null;
					log.info("Disconnected");
					long startTime = System.nanoTime();
					while (jedis == null) {
						try {
							jedis = jedisPool.getResource();
						} catch (Exception e2) {
							Thread.sleep(config.getSleep().getReconnect());
						}
					}
					long durationInNanos = System.nanoTime() - startTime;
					double durationInSec = (double) Duration.ofNanos(durationInNanos).toMillis() / 1000;
					log.info("Reconnected after {} seconds", String.format("%.3f", durationInSec));
				}
			}
		} finally {
			jedisPool.close();
			log.info("Closed");
		}
	}

	private void runLettuce() {
		RedisClient client = RedisClient.create(RedisURI.create(config.getHost(), config.getPort()));
		client.setOptions(getLettuceClientOptions());
		StatefulRedisConnection<String, String> connection = client.connect();
		log.info(connection.sync().info());
	}

	private ClientOptions getLettuceClientOptions() {
		Builder builder = ClientOptions.builder();
		if (config.isSsl()) {
			builder.sslOptions(getSslOptions());
		}
		return builder.build();
	}

	private SslOptions getSslOptions() {
		SslOptions.Builder builder = SslOptions.builder();
		switch (config.getSslProvider()) {
		case OpenSsl:
			builder.openSslProvider();
			break;
		default:
			builder.jdkSslProvider();
			break;
		}
		if (config.getKeystore() != null) {
			KeystoreConfiguration keystoreConfig = config.getKeystore();
			File file = new File(keystoreConfig.getFile());
			if (keystoreConfig.getPassword() == null) {
				builder.keystore(file);
			} else {
				builder.keystore(file, keystoreConfig.getPassword().toCharArray());
			}
		}
		if (config.getTruststore() != null) {
			TruststoreConfiguration truststoreConfig = config.getTruststore();
			File file = new File(truststoreConfig.getFile());
			if (truststoreConfig.getPassword() == null) {
				builder.truststore(file);
			} else {
				builder.truststore(file, truststoreConfig.getPassword());
			}
		}
		return builder.build();
	}
}
