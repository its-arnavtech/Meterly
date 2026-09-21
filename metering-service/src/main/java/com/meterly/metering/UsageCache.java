package com.meterly.metering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.billing.BillingPeriod;
import com.meterly.common.event.UsageUpdate;
import com.meterly.common.redis.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class UsageCache {
    // Compare decimal strings without Lua floating point truncation beyond 2^53.
    private static final DefaultRedisScript<Long> PROJECT=new DefaultRedisScript<>("""
        local previous=redis.call('GET',KEYS[2]) or '0'
        local incoming=ARGV[2]
        if #previous>#incoming or (#previous==#incoming and previous>incoming) then return 0 end
        redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[3])
        redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3])
        if ARGV[4]~='' then redis.call('PUBLISH',ARGV[5],ARGV[4]) end
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    UsageCache(StringRedisTemplate redis,ObjectMapper mapper,MeterRegistry metrics) { this.redis=redis; this.mapper=mapper; this.metrics=metrics; }
    Long read(UUID tenant,UUID resource,BillingPeriod period) {
        try { String v=redis.opsForValue().get(RedisKeys.usageCounter(tenant,resource,period)); return v==null ? null : Long.valueOf(v); }
        catch (org.springframework.dao.DataAccessException | NumberFormatException e) { unavailable(); return null; }
    }
    void project(UUID tenant,UUID resource,BillingPeriod period,UsageRepository.Snapshot state,UsageUpdate update) {
        String key=RedisKeys.usageCounter(tenant,resource,period);
        try {
            redis.execute(PROJECT,List.of(key,key+":version"),Long.toString(state.units()),Long.toString(state.version()),Long.toString(RedisKeys.USAGE_COUNTER_TTL.toSeconds()),update==null ? "" : mapper.writeValueAsString(update),RedisKeys.USAGE_UPDATES_CHANNEL);
        } catch (org.springframework.dao.DataAccessException | JsonProcessingException e) { unavailable(); }
    }
    private void unavailable() { metrics.counter("meterly_usage_cache_failures_total").increment(); }
}
