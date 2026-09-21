package com.meterly.tenant;

import com.meterly.common.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Atomic token bucket, using Redis server time so different instances cannot skew refill. Fail closed. */
@Component
public class AuthThrottle {
    private static final DefaultRedisScript<Long> BUCKET=new DefaultRedisScript<>("""
            local now=redis.call('TIME')
            local t=tonumber(now[1])+tonumber(now[2])/1000000
            local capacity=tonumber(ARGV[1])
            local refill=tonumber(ARGV[2])
            local old=redis.call('HMGET',KEYS[1],'tokens','time')
            local tokens=math.min(capacity,tonumber(old[1] or capacity)+math.max(0,t-tonumber(old[2] or t))*refill)
            local wait=0
            if tokens>=1 then tokens=tokens-1 else wait=math.ceil((1-tokens)/refill) end
            redis.call('HSET',KEYS[1],'tokens',tokens,'time',t)
            redis.call('EXPIRE',KEYS[1],math.ceil(capacity/refill)+1)
            return wait
            """,Long.class);
    private final StringRedisTemplate redis;
    public AuthThrottle(StringRedisTemplate redis) { this.redis=redis; }
    public void signup(String ip,HttpServletResponse response) { check("signup:"+Secrets.hash(ip),5,1.0/120,"TOO_MANY_REQUESTS",response); }
    public void login(String ip,String email,HttpServletResponse response) {
        check("login-ip:"+Secrets.hash(ip),30,1.0/10,"TOO_MANY_ATTEMPTS",response);
        check("login-email:"+Secrets.hash(Secrets.email(email)),10,1.0/60,"TOO_MANY_ATTEMPTS",response);
    }
    public void refresh(String ip,HttpServletResponse response) { check("refresh:"+Secrets.hash(ip),60,1,"TOO_MANY_ATTEMPTS",response); }
    private void check(String key,int capacity,double refill,String code,HttpServletResponse response) {
        Long wait;
        try { wait=redis.execute(BUCKET,List.of("meterly:auth:"+key),String.valueOf(capacity),String.valueOf(refill)); }
        catch (RuntimeException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_UNAVAILABLE","Authentication is temporarily unavailable"); }
        if(wait==null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_UNAVAILABLE","Authentication is temporarily unavailable");
        if(wait>0) {
            response.setHeader("Retry-After",wait.toString());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,code,"Try again later");
        }
    }
}
