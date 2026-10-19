package cn.nobeladam.twin.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    @Test
    void firstIsNewSecondIsInProgressAndCompletedReplays() {
        IdempotencyService service = new IdempotencyService(Duration.ofMinutes(10));

        assertThat(service.begin("request-1").status()).isEqualTo(IdempotencyService.BeginStatus.NEW);
        assertThat(service.begin("request-1").status()).isEqualTo(IdempotencyService.BeginStatus.IN_PROGRESS);

        service.complete("request-1", "答案", List.of("晚霞猎人"), List.of());

        IdempotencyService.BeginResult replay = service.begin("request-1");
        assertThat(replay.status()).isEqualTo(IdempotencyService.BeginStatus.COMPLETED);
        assertThat(replay.cached().answer()).isEqualTo("答案");
        assertThat(replay.cached().tags()).containsExactly("晚霞猎人");
    }

    @Test
    void failedRequestCanRetry() {
        IdempotencyService service = new IdempotencyService(Duration.ofMinutes(10));

        service.begin("request-2");
        service.fail("request-2");

        assertThat(service.begin("request-2").status()).isEqualTo(IdempotencyService.BeginStatus.NEW);
    }
}
