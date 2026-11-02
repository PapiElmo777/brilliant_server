package com.brilliant.server;
import com.brilliant.server.auth.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RateLimitsTest {
    @Test void concurrentAttemptsAreBoundedAndRecover() throws Exception {
        ApiIntegrationTest.TestClock clock=new ApiIntegrationTest.TestClock();RateLimits limits=new RateLimits(clock);
        AtomicInteger accepted=new AtomicInteger();
        try(var threads=Executors.newFixedThreadPool(8)) {
            for(int i=0;i<30;i++) threads.submit(()->{try{limits.hit("ip",5,Duration.ofMinutes(1));accepted.incrementAndGet();}catch(ApiException ignored){}});
            threads.shutdown();assertTrue(threads.awaitTermination(5,TimeUnit.SECONDS));
        }
        assertEquals(5,accepted.get());assertThrows(ApiException.class,()->limits.hit("ip",5,Duration.ofMinutes(1)));
        clock.now=clock.now.plusSeconds(61);assertDoesNotThrow(()->limits.hit("ip",5,Duration.ofMinutes(1)));
    }
}
