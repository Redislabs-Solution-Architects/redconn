package com.redis.redconn;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolver;
import io.lettuce.core.resource.NettyCustomizer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.epoll.EpollChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.security.Security;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Lazy
@Component
@Slf4j
public class LettuceTest {

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

        log.info("Connecting using Lettuce with {}", config);
        DefaultClientResources defaultClientResources = DefaultClientResources.builder()
                .dnsResolver(DnsResolver.unresolved()) //disable dns caching
                .nettyCustomizer(getNettyCustomizer()) //set
                .build();
        RedisURI.Builder redisURIBuilder = RedisURI.builder();
        if (config.isSsl()) redisURIBuilder.withSsl(true);
        RedisClient client = RedisClient.create(
            defaultClientResources,
            redisURIBuilder.withHost(config.getHost())
                            .withPort(config.getPort())
                            .withPassword(config.getPassword().toCharArray())
                            .build());

        client.setOptions(getLettuceClientOptions());
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> syncCommands = connection.sync();

        //populate Redis with some data
        int numKeys = config.getNumKeys();
        log.info("Connected to {}:{}", config.getHost(), config.getPort());
        log.debug("Adding {} keys...", numKeys);
        for (int index = 0; index < numKeys; index++) {
            syncCommands.set("redconn:" + index, "value" + index);
        }

        long lastSuccessTime = 0;
        boolean failed = false;
        while (true) {
            try {
                if (failed) {
                    syncCommands.ping();
                    log.error("Reconnected  in {} seconds", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastSuccessTime));
                    failed = false;
                }

                for (int index = 0; index < numKeys; index++) {
                    long ns1 = System.nanoTime();
                    String value = syncCommands.get("redconn:" + index);
                    if (System.nanoTime()>ns1+10_000_000) {
                        log.error("Read took higher than 10ms");
                    }
                    if (value == null || !value.equals("value" + index)) {
                        log.error("Incorrect value returned for redconn:{} : {}", index, value);
                    }
                }
                log.debug("Successfully performed GET on all {} keys", numKeys);

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


    private ClientOptions getLettuceClientOptions() {
        ClientOptions.Builder builder = ClientOptions.builder().timeoutOptions(TimeoutOptions
                .builder().fixedTimeout(Duration.ofMillis(config.getSocketTimeout())).build()) //ToDo: review is this same is socket timeout
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);//ToDo: review
        builder.socketOptions(getSocketOptions());

        if (config.isSsl()) {
            builder.sslOptions(getSslOptions());
        }
        return builder.build();
    }

    private SocketOptions getSocketOptions() {
        return SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(config.getConnectionTimeout()))
                .keepAlive(SocketOptions.KeepAliveOptions.builder()
                        .enable()
                        .idle(Duration.ofSeconds(config.getTcpKeepIdle()))
                        .interval(Duration.ofSeconds(config.getTcpKeepIntvl()))
                        .count(config.getTcpKeepCnt())
                        .build())
                .build();
    }

    private NettyCustomizer getNettyCustomizer() {
        //change retransmission settings only for linux
        //https://man7.org/linux/man-pages/man7/tcp.7.html
        //
        return new NettyCustomizer() {
            public void afterBootstrapInitialized(Bootstrap bootstrap) {
                bootstrap.option(EpollChannelOption.TCP_USER_TIMEOUT, config.getTcpUserTimeout());
            }
        };
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
