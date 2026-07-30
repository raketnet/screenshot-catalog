package com.screenshots.catalog.security;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A minimal in-memory sliding-window rate limiter, keyed by client identity
 * (typically remote IP). No external dependency - fine for a single-instance
 * local/small-deployment tool; wouldn't be correct across multiple app
 * instances behind a load balancer, since each instance counts independently.
 *
 * Deliberately generous by default (see AppConfig.rateLimitPerMinute) so it
 * doesn't get in the way of normal local testing/browsing - it's meant to
 * stop abuse (e.g. hammering /api/diff, which does real pixel-by-pixel work),
 * not to throttle a single person clicking around the UI.
 */
public class RateLimiter {

    private final int limitPerMinute;
    private final ConcurrentHashMap<String, Deque<Long>> requestTimestamps = new ConcurrentHashMap<>();

    public RateLimiter(int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    /** @return true if this request is allowed; false if the caller is over the limit. */
    public boolean allow(String clientKey) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;

        Deque<Long> timestamps = requestTimestamps.computeIfAbsent(clientKey, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limitPerMinute) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
