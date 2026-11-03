package com.jobradar.config;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One request per host per second by default (constitution #4). Deliberately the
 * simplest thing that works: a single user scanning ~130 hosts needs politeness,
 * not a token-bucket library.
 */
@Component
public class RateLimiter {

    private final Map<String, Long> lastCallAt = new ConcurrentHashMap<>();
    private final long minGapMillis;

    public RateLimiter(HttpClients.RadarProperties props) {
        this.minGapMillis = (long) (1000.0 / props.perHostRps());
    }

    public void acquire(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            return;
        }
        if (host == null) return;
        synchronized (host.intern()) {
            long now = System.currentTimeMillis();
            long last = lastCallAt.getOrDefault(host, 0L);
            long wait = minGapMillis - (now - last);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCallAt.put(host, System.currentTimeMillis());
        }
    }
}
