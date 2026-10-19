package cn.nobeladam.twin.error;

public class AiException extends RuntimeException {

    private final int upstreamStatus;
    private final String upstreamCode;

    public AiException(String message, int upstreamStatus, String upstreamCode) {
        super(message);
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    public String upstreamCode() {
        return upstreamCode;
    }
}
