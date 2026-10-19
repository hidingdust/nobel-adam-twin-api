package cn.nobeladam.twin.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import cn.nobeladam.twin.config.TwinProperties;
import cn.nobeladam.twin.retrieval.RetrievalService;

@Service
public class IdempotencyService {

    private final Cache<String, Entry> cache;

    @Autowired
    public IdempotencyService(TwinProperties twinProperties) {
        this(twinProperties.idempotency().ttl());
    }

    IdempotencyService(Duration ttl) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(10_000)
                .build();
    }

    public BeginResult begin(String requestId) {
        AtomicReference<BeginResult> result = new AtomicReference<>();
        cache.asMap().compute(requestId, (key, existing) -> {
            if (existing == null || existing.status == EntryStatus.FAILED) {
                result.set(new BeginResult(BeginStatus.NEW, null));
                return new Entry(EntryStatus.IN_PROGRESS, null, List.of(), List.of());
            }
            if (existing.status == EntryStatus.IN_PROGRESS) {
                result.set(new BeginResult(BeginStatus.IN_PROGRESS, null));
                return existing;
            }
            result.set(new BeginResult(BeginStatus.COMPLETED,
                    new CachedAnswer(existing.answer, existing.tags, existing.sources)));
            return existing;
        });
        return result.get();
    }

    public void complete(String requestId, String answer, List<String> tags, List<RetrievalService.Source> sources) {
        cache.put(requestId, new Entry(EntryStatus.COMPLETED, answer, List.copyOf(tags), List.copyOf(sources)));
    }

    public void fail(String requestId) {
        cache.put(requestId, new Entry(EntryStatus.FAILED, null, List.of(), List.of()));
    }

    public enum BeginStatus {
        NEW,
        IN_PROGRESS,
        COMPLETED
    }

    public record BeginResult(BeginStatus status, CachedAnswer cached) {
    }

    public record CachedAnswer(String answer, List<String> tags, List<RetrievalService.Source> sources) {
    }

    private enum EntryStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private static final class Entry {
        private final EntryStatus status;
        private final String answer;
        private final List<String> tags;
        private final List<RetrievalService.Source> sources;

        private Entry(EntryStatus status, String answer, List<String> tags, List<RetrievalService.Source> sources) {
            this.status = status;
            this.answer = answer;
            this.tags = tags;
            this.sources = sources;
        }
    }
}
