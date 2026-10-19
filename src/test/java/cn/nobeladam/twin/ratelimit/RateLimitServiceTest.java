package cn.nobeladam.twin.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.error.RateLimitException;

class RateLimitServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant START = Instant.parse("2026-10-19T02:00:00Z"); // 10:00 +08

    @Test
    void minuteWindowRejectsThirdRequest() {
        Clock clock = Clock.fixed(START, ZONE);
        RateLimitService service = new RateLimitService(new TwinProperties.RateLimit(2, 100, 500), clock);

        service.check("1.1.1.1");
        service.check("1.1.1.1");

        assertThatThrownBy(() -> service.check("1.1.1.1"))
                .isInstanceOf(RateLimitException.class)
                .satisfies(error -> {
                    RateLimitException limit = (RateLimitException) error;
                    assertThat(limit.scope()).isEqualTo("minute");
                    assertThat(limit.retryAfterSeconds()).isBetween(1L, 60L);
                });
    }

    @Test
    void differentIpHasItsOwnWindow() {
        RateLimitService service = new RateLimitService(
                new TwinProperties.RateLimit(1, 100, 500),
                Clock.fixed(START, ZONE));

        service.check("1.1.1.1");
        service.check("2.2.2.2");
    }

    @Test
    void dailyLimitAppliesAcrossMinutes() {
        RateLimitService service = new RateLimitService(
                new TwinProperties.RateLimit(10, 2, 500),
                Clock.fixed(START, ZONE));

        service.check("1.1.1.1");
        service.check("1.1.1.1");

        assertThatThrownBy(() -> service.check("1.1.1.1"))
                .isInstanceOf(RateLimitException.class)
                .satisfies(error -> assertThat(((RateLimitException) error).scope()).isEqualTo("day"));
    }

    @Test
    void globalLimitAppliesAcrossIps() {
        RateLimitService service = new RateLimitService(
                new TwinProperties.RateLimit(10, 100, 2),
                Clock.fixed(START, ZONE));

        service.check("1.1.1.1");
        service.check("2.2.2.2");

        assertThatThrownBy(() -> service.check("3.3.3.3"))
                .isInstanceOf(RateLimitException.class)
                .satisfies(error -> assertThat(((RateLimitException) error).scope()).isEqualTo("global"));
    }
}
