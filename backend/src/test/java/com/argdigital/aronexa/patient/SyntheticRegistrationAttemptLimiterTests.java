package com.argdigital.aronexa.patient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SyntheticRegistrationAttemptLimiterTests {
    private static final class AdjustableClock extends Clock {
        private final AtomicLong time = new AtomicLong(1_700_000_000_000L);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(time.get()); }
        void advance(long milliseconds) { time.addAndGet(milliseconds); }
    }

    private SyntheticRegistrationAttemptLimiter newLimiter(Clock clock, int staff, int tenant,
                                                            int staffCap, int tenantCap) {
        return new SyntheticRegistrationAttemptLimiter(
                clock, Duration.ofSeconds(60), staff, tenant, staffCap, tenantCap);
    }

    @Test void thirteenthStaffRequestIsDeniedAndWindowActuallyResets() {
        AdjustableClock clock=new AdjustableClock();
        var limiter=newLimiter(clock,12,120,100,100);
        UUID tenant=UUID.randomUUID(),staff=UUID.randomUUID();
        for(int i=0;i<12;i++) assertThat(limiter.allow(tenant,staff)).isTrue();
        assertThat(limiter.allow(tenant,staff)).isFalse();
        clock.advance(59_999);
        assertThat(limiter.allow(tenant,staff)).isFalse();
        clock.advance(1);
        assertThat(limiter.allow(tenant,staff)).isTrue();
        assertThat(limiter.trackedStaffScopes()).isEqualTo(1);
    }

    @Test void tenantQuotaSpansDifferentMembershipsAndNotOtherTenants() {
        AdjustableClock clock=new AdjustableClock();
        var limiter=newLimiter(clock,3,4,100,100);
        UUID tenant=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        for(int i=0;i<3;i++) assertThat(limiter.allow(tenant,a)).isTrue();
        assertThat(limiter.allow(tenant,a)).isFalse();
        assertThat(limiter.allow(tenant,b)).isTrue();
        assertThat(limiter.allow(tenant,b)).isFalse();
        assertThat(limiter.allow(UUID.randomUUID(),b)).isTrue();
    }

    @Test void capacityNeverEvictsActiveQuotasToPermitBypass() {
        AdjustableClock clock=new AdjustableClock();
        var limiter=newLimiter(clock,2,10,2,2);
        UUID t1=UUID.randomUUID(),t2=UUID.randomUUID();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertThat(limiter.allow(t1,a)).isTrue();
        assertThat(limiter.allow(t2,b)).isTrue();
        assertThat(limiter.allow(t1,UUID.randomUUID())).isFalse();
        assertThat(limiter.allow(UUID.randomUUID(),UUID.randomUUID())).isFalse();
        assertThat(limiter.trackedStaffScopes()).isEqualTo(2);
        assertThat(limiter.trackedTenantScopes()).isEqualTo(2);
        clock.advance(60_000);
        assertThat(limiter.allow(UUID.randomUUID(),UUID.randomUUID())).isTrue();
    }

    @Test void concurrentRequestsCountExactlyOnceWithoutOversubscription() throws Exception {
        AdjustableClock clock=new AdjustableClock();
        var limiter=newLimiter(clock,12,120,500,500);
        UUID t=UUID.randomUUID(),m=UUID.randomUUID();
        var executor=Executors.newFixedThreadPool(12);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.List<Callable<Boolean>> commands=new java.util.ArrayList<>();
            for(int i=0;i<48;i++) commands.add(()->{
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("start");
                return limiter.allow(t,m);
            });
            java.util.List<java.util.concurrent.Future<Boolean>> futures=new java.util.ArrayList<>();
            for(var command:commands) futures.add(executor.submit(command));
            start.countDown();
            int admitted=0;
            for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(12);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void anonymousScopeIsDeniedWithoutAllocatingState() {
        var limiter=SyntheticRegistrationAttemptLimiter.syntheticDefaults();
        assertThat(limiter.allow(null,UUID.randomUUID())).isFalse();
        assertThat(limiter.allow(UUID.randomUUID(),null)).isFalse();
        assertThat(limiter.trackedStaffScopes()).isZero();
    }
}
