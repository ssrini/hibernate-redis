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

package org.hibernate.cache.redis;

import org.hibernate.cache.CacheException;
import org.hibernate.cache.redis.jedis.JedisClient;
import org.hibernate.cache.redis.regions.*;
import org.hibernate.cache.redis.strategy.RedisAccessStrategyFactory;
import org.hibernate.cache.redis.strategy.RedisAccessStrategyFactoryImpl;
import org.hibernate.cache.redis.timestamper.JedisCacheTimestamper;
import org.hibernate.cache.redis.util.JedisTool;
import org.hibernate.cache.spi.*;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cfg.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Region Factory for Redis
 *
 * @author sunghyouk.bae@gmail.com
 * @since 13. 4. 5. 오후 11:59
 */
abstract class AbstractRedisRegionFactory implements RegionFactory {

    private static final Logger log = LoggerFactory.getLogger(AbstractRedisRegionFactory.class);
    /**
     * Settings object for the Hibernate persistence unit.
     */
    protected Settings settings;

    protected final RedisAccessStrategyFactory accessStrategyFactory = new RedisAccessStrategyFactoryImpl();

    /**
     * Region names
     */
    protected final ConcurrentSkipListSet<String> regionNames = new ConcurrentSkipListSet<String>();

    /**
     * JedisClient instance.
     */
    protected JedisClient redis = null;

    /**
     * JedisCacheTimestamper instance.
     */
    protected JedisCacheTimestamper timestamper = null;

    /**
     * expiration management thread
     */
    protected static Thread expirationThread = null;

    /**
     * Whether to optimize for minimals puts or minimal gets.
     * <p/>
     * Indicates whether when operating in non-strict read/write or read-only mode
     * Hibernate should optimize the access patterns for minimal puts or minimal gets.
     * In Ehcache we default to minimal puts since this should have minimal to no
     * affect on unclustered users, and has great benefit for clustered users.
     * <p/>
     * This setting can be overridden by setting the "hibernate.cache.use_minimal_puts"
     * property in the Hibernate configuration.
     *
     * @return true, optimize for minimal puts
     */
    @Override
    public boolean isMinimalPutsEnabledByDefault() {
        return true;
    }

    protected void initializeRegionFactory(Settings settings, Properties properties) {
        if (redis != null) {
            throw new IllegalStateException("Jedis client already initialized!");
        }
        redis = JedisTool.createJedisClient(properties);
        timestamper = JedisTool.createTimestamper(settings, properties, redis);
        startExpirationThread(redis);
    }

    @Override
    public AccessType getDefaultAccessType() {
        return AccessType.READ_WRITE;
    }

    @Override
    public long nextTimestamp() {
        return timestamper.next();
    }

    private Properties loadCacheProperties(Properties properties) {
        return JedisTool.loadCacheProperties(properties);
    }

    @Override
    public EntityRegion buildEntityRegion(String regionName,
                                          Properties properties,
                                          CacheDataDescription metadata) throws CacheException {
        regionNames.add(regionName);
        return new RedisEntityRegion(accessStrategyFactory,
                                     redis,
                                     regionName,
                                     settings,
                                     metadata,
                                     loadCacheProperties(properties),
                                     timestamper);
    }

    @Override
    public NaturalIdRegion buildNaturalIdRegion(String regionName,
                                                Properties properties,
                                                CacheDataDescription metadata) throws CacheException {
        regionNames.add(regionName);
        return new RedisNaturalIdRegion(accessStrategyFactory,
                                        redis,
                                        regionName,
                                        settings,
                                        metadata,
                                        loadCacheProperties(properties),
                                        timestamper);
    }

    @Override
    public CollectionRegion buildCollectionRegion(String regionName,
                                                  Properties properties,
                                                  CacheDataDescription metadata) throws CacheException {
        regionNames.add(regionName);
        return new RedisCollectionRegion(accessStrategyFactory,
                                         redis,
                                         regionName,
                                         settings,
                                         metadata,
                                         loadCacheProperties(properties),
                                         timestamper);
    }

    @Override
    public QueryResultsRegion buildQueryResultsRegion(String regionName,
                                                      Properties properties) throws CacheException {
        regionNames.add(regionName);
        return new RedisQueryResultsRegion(accessStrategyFactory,
                                           redis,
                                           regionName,
                                           loadCacheProperties(properties),
                                           timestamper);
    }

    @Override
    public TimestampsRegion buildTimestampsRegion(String regionName,
                                                  Properties properties) throws CacheException {
        // regionNames.add(regionName);
        return new RedisTimestampsRegion(accessStrategyFactory,
                                         redis,
                                         regionName,
                                         loadCacheProperties(properties),
                                         timestamper);
    }

    /**
     * Cleanup any resources that the regionFactory might have references to.
     */
    protected void destroy() {
        if (expirationThread != null) {
            expirationThread.interrupt();
            expirationThread = null;
        }
        if (redis != null) {
            redis.destroy();
            redis = null;
        }
        timestamper = null;
    }

    protected synchronized void startExpirationThread(final JedisClient redis) {
        if (expirationThread != null && expirationThread.isAlive()) {
            return;
        }

        expirationThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000L);
                        Set<String> regions = regionNames.clone();
                        for (final String region : regions) {
                            if (redis != null) {
                                try {
                                    redis.expire(region);
                                } catch (Exception ignored) {
                                    log.warn("Error occurred in expiration management thread. but it was ignored", ignored);
                                }
                            }
                        }
                    } catch (InterruptedException ignored) {
                        break;
                    } catch (Exception ignored) {
                        log.warn("Error occurred in expiration management thread. but it was ignored", ignored);
                    }
                }
            }
        });
        expirationThread.setDaemon(true);
        expirationThread.start();
    }

    private static final long serialVersionUID = -5441842686229077097L;
}
