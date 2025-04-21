package com.redis.redconn;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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


        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        HostnameVerifier hostnameVerifier = null;
        GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
        log.info("Connecting using Jedis with {}", config);
        JedisPool jedisPool = new JedisPool(poolConfig, config.getHost(), config.getPort(),
                config.getConnectionTimeout(), config.getSocketTimeout(), config.getPassword(), config.getDatabase(),
                config.getClientName(), config.isSsl(), sslSocketFactory, sslParameters, hostnameVerifier);

        int numKeys = config.getNumKeys();
        try (Jedis jedis = jedisPool.getResource()) {
            log.info("Connected to {}", jedis.getClient().toString());//getHost(),jedis.getClient().getPort());

            //populate redis with some data
            log.debug("Adding {} keys...", numKeys);
            for (int index = 0; index < numKeys; index++) {
                jedis.set("redconn:" + index, "value" + index);
            }
        }

        long lastSuccessTime = 0;
        boolean failed = false;
        while (true) {
            //get Jedis connection from the pool and try to read data
            try (Jedis jedis = jedisPool.getResource()) {
                for (int index = 0; index < numKeys; index++) {
                    long ns1 = System.nanoTime();
                    String value = jedis.get("redconn:" + index);
                    if (value == null || !value.equals("value" + index)) {
                        log.error("Incorrect value returned: " + value);
                    }
                    if (System.nanoTime()>ns1+10_000_000) {
                        log.error("higher than 10ms");
                    }
                }
                log.debug("Successfully performed GET on all {} keys", numKeys);

                if (failed) {
                    log.error("Reconnected  in {} seconds to {}", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastSuccessTime), jedis.getClient().toString());//getHost(), jedis.getClient().getPort());
                    failed = false;
                }
                //we ran successfully , save the last successful time
                lastSuccessTime = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("Disconnected {}", e.getMessage());
                failed = true;
            } finally {
                Thread.sleep(failed ? config.getSleep().getReconnect() : config.getSleep().getGet());
            }
        }
    }
}
