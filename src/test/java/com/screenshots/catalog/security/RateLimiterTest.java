package com.screenshots.catalog.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsRequestsUpToTheLimit() {
        RateLimiter limiter = new RateLimiter(3);
        assertTrue(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-a"));
    }

    @Test
    void rejectsRequestsOverTheLimit() {
        RateLimiter limiter = new RateLimiter(3);
        limiter.allow("client-a");
        limiter.allow("client-a");
        limiter.allow("client-a");
        assertFalse(limiter.allow("client-a"));
    }

    @Test
    void tracksDifferentClientsIndependently() {
        RateLimiter limiter = new RateLimiter(1);
        assertTrue(limiter.allow("client-a"));
        assertFalse(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-b")); // separate client, separate budget
    }
}
