package cn.nobeladam.twin.error;

public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;
    private final String scope;

    public RateLimitException(String message, long retryAfterSeconds, String scope) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.scope = scope;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String scope() {
        return scope;
    }
}
