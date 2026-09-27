package com.example.server.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiRateLimitService {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local userCount = tonumber(redis.call('GET', KEYS[1]) or '0')
            local globalCount = tonumber(redis.call('GET', KEYS[2]) or '0')
            if userCount >= tonumber(ARGV[1]) or globalCount >= tonumber(ARGV[2]) then
                return 0
            end
            userCount = redis.call('INCR', KEYS[1])
            globalCount = redis.call('INCR', KEYS[2])
            if userCount == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
            if globalCount == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public AiRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String scope, Long userId, int userLimit, int globalLimit) {
        if (userId == null) return false;
        Long result = redisTemplate.execute(
                SCRIPT,
                List.of("limit:" + scope + ":user:" + userId, "limit:" + scope + ":global"),
                String.valueOf(userLimit), String.valueOf(globalLimit), "60000");
        return Long.valueOf(1).equals(result);
    }
}
