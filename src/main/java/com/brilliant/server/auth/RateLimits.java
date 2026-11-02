package com.brilliant.server.auth;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class RateLimits {
    private record Window(int count, Instant until) {}
    private final Map<String,Window> buckets = new HashMap<>();
    private final Clock clock;
    public RateLimits(Clock clock) { this.clock=clock; }
    public synchronized void check(String key, int max) {
        Window w=buckets.get(key);
        if(w!=null && w.until.isAfter(clock.instant()) && w.count>=max)
            throw new ApiException(429,"RATE_LIMITED",Map.of(),Math.max(1,Duration.between(clock.instant(),w.until).toSeconds()+1));
    }
    public synchronized void refund(String key) {
        Window w=buckets.get(key);
        if(w!=null) { if(w.count<=1) buckets.remove(key); else buckets.put(key,new Window(w.count-1,w.until)); }
    }
    public synchronized void hit(String key, int max, Duration duration) {
        check(key,max);
        Instant now=clock.instant();
        buckets.entrySet().removeIf(e -> !e.getValue().until.isAfter(now));
        Window w=buckets.get(key);
        if(w==null && buckets.size()>=100000) throw new ApiException(503,"SERVICE_UNAVAILABLE");
        buckets.put(key,w==null ? new Window(1,now.plus(duration)) : new Window(w.count+1,w.until));
    }
}
