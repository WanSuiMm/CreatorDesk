package org.creatordesk.error;

public final class DeskException extends RuntimeException {
    private final int status;
    private final String code;

    public DeskException(int status, String code) {
        this(status, code, code);
    }

    public DeskException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
