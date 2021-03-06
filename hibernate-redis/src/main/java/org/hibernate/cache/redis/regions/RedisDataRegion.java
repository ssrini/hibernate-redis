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

package org.hibernate.cache.redis.regions;

import org.hibernate.cache.CacheException;
import org.hibernate.cache.redis.jedis.JedisClient;
import org.hibernate.cache.redis.strategy.RedisAccessStrategyFactory;
import org.hibernate.cache.redis.timestamper.JedisCacheTimestamper;
import org.hibernate.cache.redis.util.JedisTool;
import org.hibernate.cache.spi.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;

/**
 * Base DataRegion using Redis
 *
 * @author sunghyouk.bae@gmail.com
 * @since 13. 4. 5. 오후 8:48
 */
public abstract class RedisDataRegion implements Region {

    private static final String CACHE_LOCK_TIMEOUT_PROPERTY = "io.redis.hibernate.cache_lock_timeout";
    private static final int DEFAULT_CACHE_LOCK_TIMEOUT = 60 * 1000; // 60 seconds
    private static final String EXPIRE_IN_SECONDS = "redis.expiryInSeconds";
    private static final Logger log = LoggerFactory.getLogger(RedisDataRegion.class);

    protected final RedisAccessStrategyFactory accessStrategyFactory;

    /**
     * Region name
     */
    private final String name;

    /**
     * Redis client instance deal hibernate data region.
     */
    protected final JedisClient redis;

    private final int cacheLockTimeout; // milliseconds

    private final int expireInSeconds;  // seconds

    protected boolean regionDeleted = false;

    private JedisCacheTimestamper timestamper;

    protected RedisDataRegion(RedisAccessStrategyFactory accessStrategyFactory,
                              JedisClient redis,
                              String regionName,
                              Properties props,
                              JedisCacheTimestamper timestamper) {
        this.accessStrategyFactory = accessStrategyFactory;
        this.redis = redis;
        this.name = regionName;
        this.timestamper = timestamper;

        this.cacheLockTimeout =
                Integer.decode(props.getProperty(CACHE_LOCK_TIMEOUT_PROPERTY,
                                                 String.valueOf(DEFAULT_CACHE_LOCK_TIMEOUT)));

        this.expireInSeconds = JedisTool.getExpireInSeconds(props, name);
    }

    public JedisClient getRedis() {
        return this.redis;
    }

    public int getCacheLockTimeout() {
        return this.cacheLockTimeout;
    }

    public int getExpireInSeconds() {
        return this.expireInSeconds;
    }

    public boolean isRegionDeleted() {
        return this.regionDeleted;
    }

    /**
     * Region name
     *
     * @return region name
     */
    public String getName() {

        return name;
    }

    /**
     * delete region
     *
     * @throws org.hibernate.cache.CacheException
     */
    @Override
    public void destroy() throws CacheException {
        // NOTE: HA 구성 시에는 region 을 삭제할 필요는 없습니다.
        // NOTE: 단 Cache 조회 시 expiration 되었는지 확인해야 합니다.
        log.info("destroy region... but not delete redis key. region=[{}]", name);
//        if (regionDeleted)
//            return;
//        log.info("destroy region. all cache items is deleted. region=[{}]", name);
//        try {
//            redis.deleteRegion(name);
//        } catch (Exception ignored) {
//            log.warn("Fail to delete all cache items... region=" + name, ignored);
//        } finally {
//            regionDeleted = true;
//        }
    }

    /**
     * confirm the specified key exists in current region
     *
     * @param key cache key
     * @return if cache key is exists in current region return true, else return false
     */
    @Override
    public boolean contains(Object key) {
        try {
            boolean exists = redis.exists(name, key);
            log.debug("cache contains items? region=[{}], key=[{}], contains=[{}]", name, key, exists);
            return exists;
        } catch (Throwable ignored) {
            log.warn("Fail to check contains key... region=" + name, ignored);
            return false;
        }
    }

    @Override
    public long getSizeInMemory() {
        try {
            return redis.dbSize();
        } catch (Throwable ignored) {
            log.warn("Fail to get count of cache items. region=" + name, ignored);
            return -1;
        }
    }

    @Override
    public long getElementCountInMemory() {
        try {
            return redis.keysInRegion(name).size();
        } catch (Throwable ignored) {
            log.warn("Fail to get count of cache items. region=" + name, ignored);
            return -1;
        }
    }

    @Override
    public long getElementCountOnDisk() {
        return -1;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map toMap() {
        try {
            return redis.hgetAll(name);
        } catch (Throwable e) {
            log.warn("Fail to build CacheEntry. return EmptyMap.", e);
            return Collections.emptyMap();
        }
    }

    @Override
    public long nextTimestamp() {
        return timestamper.next();
    }

    @Override
    public int getTimeout() {
        return cacheLockTimeout;
    }

    public RedisAccessStrategyFactory getAccessStrategyFactory() {
        return this.accessStrategyFactory;
    }
}
