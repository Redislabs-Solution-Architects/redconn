package com.redis.redconn;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.MultiClusterClientConfig;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.Connection;
import redis.clients.jedis.MultiClusterClientConfig.ClusterConfig;
import redis.clients.jedis.providers.ConnectionProvider;
import redis.clients.jedis.providers.MultiClusterPooledConnectionProvider;
import redis.clients.jedis.providers.PooledConnectionProvider;
import redis.clients.jedis.HostAndPort;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import java.security.Security;
import java.util.concurrent.TimeUnit;

@Lazy
@Component
@Slf4j
public class JedisTest {

    private static final String DNS_CACHE_TTL = "networkaddress.cache.ttl";
    private static final String DNS_CACHE_NEGATIVE_TTL = "networkaddress.cache.negative.ttl";

    @Autowired
    private RedconnConfiguration config;

    void run() throws InterruptedException {
        //disable JVM DNS Cache
        log.info("Setting {}={}", DNS_CACHE_TTL, config.getDnsTtl());
        Security.setProperty(DNS_CACHE_TTL, config.getDnsTtl());
        log.info("Setting {}={}", DNS_CACHE_NEGATIVE_TTL, config.getDnsNegativeTtl());
        Security.setProperty(DNS_CACHE_NEGATIVE_TTL, config.getDnsNegativeTtl());

        // TLS (optional)
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        HostnameVerifier hostnameVerifier = null;

         // Pool configuration
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(50);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);

        log.info("Connecting using Jedis with {}", config);
        JedisClientConfig genericConfig = DefaultJedisClientConfig.builder()
            .password(config.getPassword())
            .connectionTimeoutMillis(config.getConnectionTimeout())
            .socketTimeoutMillis(config.getSocketTimeout())
            .database(config.getDatabase())
            .clientName(config.getClientName())
            .ssl(config.isSsl())
            .sslSocketFactory(sslSocketFactory)
            .sslParameters(sslParameters)
            .hostnameVerifier(hostnameVerifier)
            .build();

        boolean isMultiCluster = false;
        ConnectionProvider jedisConnectionProvider = null;    
        if (config.getFailoverHost()==null) {
            log.info("Connecting using Jedis with {}:{}", config.getHost(), config.getPort());
            jedisConnectionProvider = new PooledConnectionProvider(new HostAndPort(config.getHost(), config.getPort()), genericConfig, poolConfig);
        } else {
            isMultiCluster = true;
            log.info("Connecting using Jedis with {}:{}", config.getHost(), config.getPort());
            log.info("Connecting using Jedis failover with {}:{}", config.getFailoverHost(), config.getFailoverPort());
            ClusterConfig[] clientConfigs = new ClusterConfig[2];
            clientConfigs[0] = new ClusterConfig(new HostAndPort(config.getHost(), config.getPort()), genericConfig, poolConfig);
            clientConfigs[1] = new ClusterConfig(new HostAndPort(config.getFailoverHost(), config.getFailoverPort()), genericConfig, poolConfig);
            MultiClusterClientConfig.Builder builder = new MultiClusterClientConfig.Builder(clientConfigs);
            builder.circuitBreakerSlidingWindowSize(1);
            builder.circuitBreakerSlidingWindowMinCalls(1);
            builder.circuitBreakerFailureRateThreshold(50.0f);
            jedisConnectionProvider = new MultiClusterPooledConnectionProvider(builder.build());
        }

        //get Jedis connection from the pool and populate Redis with some data
        int numKeys = config.getNumKeys();
        try (Connection conn = jedisConnectionProvider.getConnection()) {
            UnifiedJedis jedis = new UnifiedJedis(conn);
            log.info("Connected to {}", conn.toString());
            log.debug("Adding {} keys...", numKeys);
            for (int index = 0; index < numKeys; index++) {
                jedis.set("redconn:" + index, "value" + index);
            }
        }

        //get Jedis connection from the pool and try to read data in a loop until disconnect/reconnect
        long lastSuccessTime = 0;
        boolean failed = false;
        UnifiedJedis jedis = null;
        // force cast to use the multi cluster provider
        while (true) {
            try {
                if (isMultiCluster) jedis = new UnifiedJedis((MultiClusterPooledConnectionProvider)jedisConnectionProvider);
                else jedis = new UnifiedJedis(jedisConnectionProvider);

                if (failed) {
                    jedis.ping();
                    log.error("Reconnected in {} seconds", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastSuccessTime));
                    failed = false;
                }

                for (int index = 0; index < numKeys; index++) {
                    long ns1 = System.nanoTime();
                    String value = jedis.get("redconn:" + index);
                    if (value == null || !value.equals("value" + index)) {
                        log.error("Incorrect value returned for redconn:{} : {}", index, value);
                    }
                    if (System.nanoTime()>ns1+10_000_000) {
                        log.error("Read took higher than 10ms");
                    }
                }
                log.debug("Successfully performed GET on all {} keys", numKeys);

                //we ran successfully , save the last successful time
                lastSuccessTime = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Disconnected - {}", e.getMessage());
                failed = true;
            } finally {
                Thread.sleep(failed ? config.getSleep().getReconnect() : config.getSleep().getGet());
            }
        }
    }
}
