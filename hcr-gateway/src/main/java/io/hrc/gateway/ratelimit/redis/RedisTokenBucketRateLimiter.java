package io.hrc.gateway.ratelimit.redis;

import io.hrc.gateway.ratelimit.RateLimiter;

/**
 * Per-key token buckets with capacity + refill rate.
 * Lua script ensures atomicity. Config: permitsPerSecond, burstCapacity.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {}
