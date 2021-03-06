/*
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hibernate.cache.redis.jedis;

import org.hibernate.cache.redis.serializer.RedisSerializer;
import org.hibernate.cache.redis.serializer.SerializationTool;
import org.hibernate.cache.redis.serializer.SnappyRedisSerializer;
import org.hibernate.cache.redis.serializer.StringRedisSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Transaction;
import redis.clients.util.Pool;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * RedisClient implements using Jedis library
 * <p/>
 * 참고 : https://github.com/xetorthio/org.hibernate.cache.redis.jedis/wiki/AdvancedUsage
 *
 * @author 배성혁 ( sunghyouk.bae@gmail.com )
 * @since 13. 4. 9 오후 10:20
 */
public class JedisClient {

    public static final int DEFAULT_EXPIRY_IN_SECONDS = 120;
    public static final String DEFAULT_REGION_NAME = "hibernate";
    private static final int MAX_TIMESTAMP_UPDATE_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(JedisClient.class);

    private final Pool<Jedis> jedisPool;

    private int expiryInSeconds;

    private final StringRedisSerializer regionSerializer = new StringRedisSerializer();
    private final StringRedisSerializer keySerializer = new StringRedisSerializer();
    private final RedisSerializer<Object> valueSerializer = new SnappyRedisSerializer<Object>();

    public JedisClient() {
        this(new JedisPool("localhost"), DEFAULT_EXPIRY_IN_SECONDS);
    }

    public JedisClient(Pool<Jedis> jedisPool) {
        this(jedisPool, DEFAULT_EXPIRY_IN_SECONDS);
    }

    /**
     * initialize JedisClient instance
     *
     * @param jedisPool       JedisPool instance
     * @param expiryInSeconds expiration in seconds
     */
    public JedisClient(Pool<Jedis> jedisPool, int expiryInSeconds) {
        log.debug("JedisClient created. jedisPool=[{}], expiryInSeconds=[{}]", jedisPool, expiryInSeconds);

        this.jedisPool = jedisPool;
        this.expiryInSeconds = expiryInSeconds;
    }

    public Pool<Jedis> getJedisPool() {
        return this.jedisPool;
    }

    public int getExpiryInSeconds() {
        return this.expiryInSeconds;
    }

    public void setExpiryInSeconds(int expiryInSeconds) {
        this.expiryInSeconds = expiryInSeconds;
    }

    /**
     * ping test for server alive
     */
    public String ping() {
        return run(new JedisCallback<String>() {
            @Override
            public String execute(Jedis jedis) {
                return jedis.ping();
            }
        });
    }

    /**
     * get Redis db size
     */
    public Long dbSize() {
        return run(new JedisCallback<Long>() {
            @Override
            public Long execute(Jedis jedis) {
                return jedis.dbSize();
            }
        });
    }

    /**
     * confirm the specified cache item in specfied region
     *
     * @param region region name
     * @param key    cache key
     */
    public boolean exists(final String region, final Object key) {
        final byte[] rawRegion = rawRegion(region);
        final byte[] rawKey = rawKey(key);

        return run(new JedisCallback<Boolean>() {
            @Override
            public Boolean execute(Jedis jedis) {
                return jedis.hexists(rawRegion, rawKey);
            }
        });
    }

    /**
     * Get cache
     *
     * @param region region name
     * @param key    cache key
     * @return return cached entity, if not exists return null.
     */
    public Object get(final String region, final Object key) {
        return get(region, key, 0);
    }

    /**
     * Get cache
     *
     * @param region              region name
     * @param key                 cache key
     * @param expirationInSeconds expiration timeout in seconds
     * @return return cached entity, if not exists return null.
     */
    public Object get(final String region, final Object key, final int expirationInSeconds) {
        final byte[] rawRegion = rawRegion(region);
        final byte[] rawKey = rawKey(key);

        // NOTE: expire 된 캐시 정보라면 삭제하고, null 값을 반환합니다.
        if (expirationInSeconds > 0 && isExpired(region, key)) {
            runWithPipeline(new JedisPipelinedCallback() {
                @Override
                public void execute(Pipeline pipeline) {
                    final byte[] rawZkey = rawZkey(region);
                    pipeline.zrem(rawZkey, rawKey);
                    pipeline.hdel(rawRegion, rawKey);
                }
            });
            return null;
        }

        byte[] rawValue = run(new JedisCallback<byte[]>() {
            @Override
            public byte[] execute(Jedis jedis) {
                return jedis.hget(rawRegion, rawKey);
            }
        });

        // after get, update expiration time
        if (rawValue != null && rawValue.length > 0) {
            if (expirationInSeconds > 0 && !region.contains("UpdateTimestampsCache")) {
                run(new JedisCallback<Object>() {
                    @Override
                    public Object execute(Jedis jedis) {
                        final byte[] rawZkey = rawZkey(region);
                        final long score = System.currentTimeMillis() + expirationInSeconds * 1000L;
                        return jedis.zadd(rawZkey, score, rawKey);
                    }
                });
            }
        }

        return deserializeValue(rawValue);
    }

    private Boolean isExpired(final String region, final Object key) {
        final byte[] rawZkey = rawZkey(region);
        final byte[] rawKey = rawKey(key);

        Double timestamp = run(new JedisCallback<Double>() {
            @Override
            public Double execute(Jedis jedis) {
                return jedis.zscore(rawZkey, rawKey);
            }
        });
        return timestamp != null && System.currentTimeMillis() > timestamp.longValue();
    }

    /**
     * retrieve all cached items in specified region
     *
     * @param region region
     * @return collection of cached items
     */
    public Set<Object> keysInRegion(String region) {
        try {
            final byte[] rawRegion = rawRegion(region);
            Set<byte[]> rawKeys = run(new JedisCallback<Set<byte[]>>() {
                @Override
                public Set<byte[]> execute(Jedis jedis) {
                    return jedis.hkeys(rawRegion);
                }
            });

            if (rawKeys != null)
                return deserializeKeys(rawKeys);
        } catch (Exception ignored) { }
        return new HashSet<Object>();
    }

    /**
     * get cache count in region
     *
     * @param region region
     * @return cache item count in region
     */
    public Long keySizeInRegion(final String region) {
        final byte[] rawRegion = rawRegion(region);
        return run(new JedisCallback<Long>() {
            @Override
            public Long execute(Jedis jedis) {
                return jedis.hlen(rawRegion);
            }
        });
    }

    /**
     * get all cached items in specified region
     *
     * @param region region name
     * @return map of keys and all cached items in specified region
     */
    public Map<Object, Object> hgetAll(String region) {
        final byte[] rawRegion = rawRegion(region);
        Map<byte[], byte[]> rawMap = run(new JedisCallback<Map<byte[], byte[]>>() {
            @Override
            public Map<byte[], byte[]> execute(Jedis jedis) {
                return jedis.hgetAll(rawRegion);
            }
        });

        Map<Object, Object> map = new HashMap<Object, Object>();
        for (Map.Entry<byte[], byte[]> entry : rawMap.entrySet()) {
            Object key = deserializeKey(entry.getKey());
            Object value = deserializeValue(entry.getValue());
            map.put(key, value);
        }
        return map;
    }

    /**
     * multiple get cache items in specified region
     *
     * @param region region name
     * @param keys   cache key collection to retrieve
     * @return cache items
     */
    public List<Object> mget(final String region, final Collection<?> keys) {
        final byte[] rawRegion = rawRegion(region);
        final byte[][] rawKeys = rawKeys(keys);

        List<byte[]> rawValues = run(new JedisCallback<List<byte[]>>() {
            @Override
            public List<byte[]> execute(Jedis jedis) {
                return jedis.hmget(rawRegion, rawKeys);
            }
        });
        return deserializeValues(rawValues);
    }

    /**
     * save cache
     *
     * @param region region name
     * @param key    cache key to save
     * @param value  cache value to save
     */
    public void set(String region, Object key, Object value) {
        set(region, key, value, expiryInSeconds, TimeUnit.SECONDS);
    }

    /**
     * save cache item
     *
     * @param region           region name
     * @param key              cache key to save
     * @param value            cache value to save
     * @param timeoutInSeconds expire timeout in seconds
     */
    public void set(String region, Object key, Object value, long timeoutInSeconds) {
        set(region, key, value, timeoutInSeconds, TimeUnit.SECONDS);
    }

    /**
     * save cache item
     *
     * @param region  region name
     * @param key     cache key to save
     * @param value   cache value to save
     * @param timeout expire timeout
     * @param unit    expire timeout unit
     */
    public void set(final String region, final Object key, final Object value, long timeout, TimeUnit unit) {
        final byte[] rawRegion = rawRegion(region);
        final byte[] rawKey = rawKey(key);
        final byte[] rawValue = rawValue(value);
        final int seconds = (int) unit.toSeconds(timeout);

        runWithTx(new JedisTransactionalCallback() {
            @Override
            public void execute(Transaction tx) {
                tx.hset(rawRegion, rawKey, rawValue);
                if (seconds > 0 && !region.contains("UpdateTimestampsCache")) {
                    final byte[] rawZkey = rawZkey(region);
                    final long score = System.currentTimeMillis() + seconds * 1000L;
                    tx.zadd(rawZkey, score, rawKey);
                }
            }
        });
    }

    /**
     * delete cache item which is expired in region
     *
     * @param region region name
     */
    public void expire(final String region) {

        try {
            final byte[] rawZkey = rawZkey(region);
            final byte[] rawRegion = rawRegion(region);
            final long score = System.currentTimeMillis();

            // get key which score is less than current time
            final Set<byte[]> rawKeys = run(new JedisCallback<Set<byte[]>>() {
                @Override
                public Set<byte[]> execute(Jedis jedis) {
                    return jedis.zrangeByScore(rawZkey, 0, score);
                }
            });

            if (rawKeys != null && rawKeys.size() > 0) {
                log.debug("delete expired cache item in region[{}] expire time=[{}]", region, score);

                runWithPipeline(new JedisPipelinedCallback() {
                    @Override
                    public void execute(Pipeline pipeline) {
                        // delete cache item
                        for (final byte[] rawKey : rawKeys) {
                            pipeline.hdel(rawRegion, rawKey);
                        }
                        pipeline.zremrangeByScore(rawZkey, 0, score);
                    }
                });

            }
        } catch (Exception ignored) {
            log.warn("Error in Cache Expiration Method.", ignored);
        }
    }

    /**
     * delete cache item in specified region.
     *
     * @param region region name
     * @param key    cache key to delete
     * @return count of deleted key
     */
    public Long del(final String region, final Object key) {
        final byte[] rawRegion = rawRegion(region);
        final byte[] rawKey = rawKey(key);
        final byte[] rawZkey = rawZkey(region);

        runWithTx(new JedisTransactionalCallback() {
            @Override
            public void execute(Transaction tx) {
                tx.hdel(rawRegion, rawKey);
                tx.zrem(rawZkey, rawKey);
            }
        });

        return 1L;
    }

    /**
     * multiplu delete cache items in specified region
     *
     * @param keys key collection to delete
     */
    public void mdel(final String region, final Collection<?> keys) {

        final byte[] rawRegion = rawRegion(region);
        final byte[] rawZkey = rawZkey(region);
        final byte[][] rawKeys = rawKeys(keys);

        runWithTx(new JedisTransactionalCallback() {
            @Override
            public void execute(Transaction tx) {
                for (byte[] rawKey : rawKeys) {
                    tx.hdel(rawRegion, rawKey);
                    tx.zrem(rawZkey, rawKey);
                }
            }
        });
    }

    /**
     * delete region
     *
     * @param region region name to delete
     */
    public void deleteRegion(final String region) throws JedisCacheException {
        log.debug("delete region region=[{}]", region);

        final byte[] rawRegion = rawRegion(region);
        final byte[] rawZkey = rawZkey(region);

        runWithTx(new JedisTransactionalCallback() {
            @Override
            public void execute(Transaction tx) {
                tx.del(rawRegion);
                tx.del(rawZkey);
            }
        });
    }

    /**
     * We want nextTimestamp to return a long that is greater than previous calls to nextTimestamp.
     *
     * The simplest algorithm would be to ignore Redis and return currentTimeMillis. However, different VMs can have small drifts
     * in their currentTimeMillis and this algorithm isn't safe in multi VM clusters.
     *
     * A better algorithm would be:
     *   SETNX rawKey currentTimeMillis
     *   INCR rawKey
     *
     * However, there are two problems.
     * 1. Clients can have small drift in their currentTimeMillis.
     *    If rawKey had been set but later evicted then a client which is behind other clients in currentTimeMillis
     *    might be the first to call SETNX and issue set to a value that had previously been issued by another client.
     * 2. Master-slave replication is not synchronous.
     *    Therefore a slave promoted to master might have missed some calls to INCR. As a result
     *    we might create timestamps that have already been issued.
     *
     * Instead we do the following:
     *    WATCH rawKey
     *    current = GET rawKey
     *    new = max(current, currentTimeMillis) + 1
     *    MULTI
     *    SET rawKey $new
     *    EXEC
     *
     * If a client has drifted behind in currentTimeMillis then the current timestamp will just be incremented. This solves 1.
     * If current value is behind we set to currentTimeMillis +1. This fixes 2.
     *
     * If the exec fails because another client changed the value we retry the update.
     * To avoid deadlocking attempting the update we cap the number of retries. After max retries we fall back to just
     * incrementing the current value which is a single Redis operation and will always succeed.
     * This does not fix problem 2. but gives us path to eventually recover.
     */
    public long nextTimestamp(final Object key) {
        final byte[] rawKey = rawKey(key);

        Long updatedTimestamp = null;
        int updateAttempts = 0;
        while (updatedTimestamp == null && updateAttempts < MAX_TIMESTAMP_UPDATE_ATTEMPTS) {
            updatedTimestamp = updateOrIncrementTimestamp(rawKey);
            updateAttempts++;
        }

        if ( updatedTimestamp != null) {
            log.debug("updated timestamp: key=[{}], attempt=[{}], timestamp=[{}]", key, updateAttempts, updatedTimestamp);
            return updatedTimestamp;
        } else {
            // If we can't update the timestamp at least fall back to incrementing
            Long incrementedTimestamp = incrementTimestamp(rawKey);
            log.warn("updated timestamp failed {} times. Fall back to incrementing: key=[{}], timestamp=[{}]", updateAttempts, key, incrementedTimestamp);
            return incrementedTimestamp;
        }
    }

    /**
     * Cleanup any resources thathe JedisClient might have references to.
     */
    public void destroy() {
        if (jedisPool != null) {
            jedisPool.destroy();
        }
    }

    private Long updateOrIncrementTimestamp(final byte[] rawKey) {
        return run(new JedisCallback<Long>() {
            @Override
            public Long execute(Jedis jedis) {
                jedis.watch(rawKey);
                Long currentTimestamp = (Long)deserializeValue(jedis.get(rawKey));
                if (currentTimestamp == null) {
                    currentTimestamp = 0L;
                }
                Long newTimestamp = Math.max(System.currentTimeMillis(), currentTimestamp) + 1;
                Transaction tx = jedis.multi();
                tx.set(rawKey, rawValue(newTimestamp));
                List<Object> result = tx.exec(); // it the watch fails exec returns null
                return result != null ? newTimestamp : null;
            }
        });
    }

    private Long incrementTimestamp(final byte[] rawKey) {
        return run(new JedisCallback<Long>() {
            @Override
            public Long execute(Jedis jedis) {
                return jedis.incr(rawKey);
            }
        });
    }

    /**
     * flush db
     */
    public String flushDb() {
        log.info("Flush DB...");

        return run(new JedisCallback<String>() {
            @Override
            public String execute(Jedis jedis) {
                return jedis.flushDB();
            }
        });
    }

    /**
     * serialize cache key
     */
    private byte[] rawKey(final Object key) {
        return keySerializer.serialize(key.toString());
    }

    @SuppressWarnings("unchecked")
    private byte[][] rawKeys(final Collection<?> keys) {
        byte[][] rawKeys = new byte[keys.size()][];
        int i = 0;
        for (Object key : keys) {
            rawKeys[i++] = rawKey(key);
        }
        return rawKeys;
    }

    /**
     * Serialize expiration region name
     */
    private byte[] rawZkey(final String region) {
        return rawRegion("z:" + region);
    }

    /**
     * serializer region name
     */
    private byte[] rawRegion(final String region) {
        return regionSerializer.serialize(region);
    }

    /**
     * deserialize key
     */
    private Object deserializeKey(final byte[] rawKey) {
        return keySerializer.deserialize(rawKey);
    }

    /**
     * serializer cache value
     */
    private byte[] rawValue(final Object value) {
        try {
            return valueSerializer.serialize(value);
        } catch (Exception e) {
            log.warn("value를 직렬화하는데 실패했습니다. value=" + value, e);
            return null;
        }
    }

    /**
     * deserialize raw value
     */
    private Object deserializeValue(final byte[] rawValue) {
        return valueSerializer.deserialize(rawValue);
    }

    /**
     * execute the specified callback
     */
    private <T> T run(final JedisCallback<T> callback) {

        Jedis jedis = jedisPool.getResource();
        try {
            return callback.execute(jedis);
        } finally {
            jedisPool.returnResource(jedis);
        }
    }

    /**
     * execute the specified callback under transaction
     * HINT: https://github.com/xetorthio/org.hibernate.cache.redis.jedis/wiki/AdvancedUsage
     *
     * @param callback executable instance under transaction
     */
    private List<Object> runWithTx(final JedisTransactionalCallback callback) {

        Jedis jedis = jedisPool.getResource();
        try {
            Transaction tx = jedis.multi();
            callback.execute(tx);
            return tx.exec();
        } finally {
            jedisPool.returnResource(jedis);
        }
    }

    /**
     * execute the specified callback under Redis Pipeline
     * HINT: https://github.com/xetorthio/org.hibernate.cache.redis.jedis/wiki/AdvancedUsage
     *
     * @param callback executable instance unider Pipeline
     */
    private void runWithPipeline(final JedisPipelinedCallback callback) {
        final Jedis jedis = jedisPool.getResource();
        try {
            final Pipeline pipeline = jedis.pipelined();
            callback.execute(pipeline);
            // use #sync(), not #exec()
            pipeline.sync();
        } finally {
            jedisPool.returnResource(jedis);
        }
    }

    /**
     * deserialize the specified raw key set.
     *
     * @return original key set.
     */
    private Set<Object> deserializeKeys(final Set<byte[]> rawKeys) {
        Set<Object> keys = new HashSet<Object>();
        for (byte[] rawKey : rawKeys) {
            keys.add(deserializeKey(rawKey));
        }
        return keys;
    }

    /**
     * deserialize the specified raw value collection
     *
     * @return collection of original value
     */
    private List<Object> deserializeValues(final List<byte[]> rawValues) {
        return SerializationTool.deserialize(rawValues, valueSerializer);
    }
}
