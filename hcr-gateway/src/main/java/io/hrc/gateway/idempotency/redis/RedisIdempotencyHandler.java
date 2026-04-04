package io.hrc.gateway.idempotency.redis;

import io.hrc.gateway.idempotency.IdempotencyHandler;

/** Redis SET/EXPIRE -> GET -> return cached result if duplicate. Default TTL: 24h. */
public class RedisIdempotencyHandler implements IdempotencyHandler {}
