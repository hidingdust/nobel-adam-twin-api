package cn.nobeladam.twin.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.error.RateLimitException;

@Service
public class RateLimitService {

    private final TwinProperties.RateLimit properties;
    private final Clock clock;
    private final Cache<String, IpWindow> windows;
    private final Object lock = new Object();

    private LocalDate globalDay;
    private int globalCount;

    @Autowired
    public RateLimitService(TwinProperties twinProperties, Clock clock) {
        this(twinProperties.rateLimit(), clock);
    }

    RateLimitService(TwinProperties.RateLimit properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.windows = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(2))
                .maximumSize(10_000)
                .build();
    }

    public void check(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        long now = clock.millis();
        LocalDate today = LocalDate.now(clock);

        synchronized (lock) {
            IpWindow window = windows.get(key, ignored -> new IpWindow());
            resetDayIfNeeded(window, today);

            while (!window.minuteWindow.isEmpty() && now - window.minuteWindow.peekFirst() >= 60_000) {
                window.minuteWindow.pollFirst();
            }

            if (window.minuteWindow.size() >= properties.perMinute()) {
                long retry = Math.max(1, 60 - TimeUnit.MILLISECONDS.toSeconds(now - window.minuteWindow.peekFirst()));
                throw new RateLimitException(
                        "聊得有点快，等我喘口气，" + retry + " 秒后再试。",
                        retry,
                        "minute");
            }

            if (window.dayCount >= properties.perDayPerIp()) {
                throw new RateLimitException(
                        "今天先聊到这，明天再来找我。",
                        secondsUntilTomorrow(today),
                        "day");
            }

            if (globalDay == null || !globalDay.equals(today)) {
                globalDay = today;
                globalCount = 0;
            }
            if (globalCount >= properties.globalPerDay()) {
                throw new RateLimitException(
                        "今天分身接待的人有点多，明天再来吧。",
                        secondsUntilTomorrow(today),
                        "global");
            }

            window.minuteWindow.addLast(now);
            window.dayCount++;
            globalCount++;
        }
    }

    private void resetDayIfNeeded(IpWindow window, LocalDate today) {
        if (!today.equals(window.day)) {
            window.day = today;
            window.dayCount = 0;
        }
    }

    private long secondsUntilTomorrow(LocalDate today) {
        return Math.max(1, Duration.between(clock.instant(), today.plusDays(1).atStartOfDay(clock.getZone()).toInstant()).getSeconds());
    }

    private static final class IpWindow {
        private LocalDate day;
        private int dayCount;
        private final Deque<Long> minuteWindow = new ArrayDeque<>();
    }
}
