package org.creatordesk.peer;

public final class PeerException extends RuntimeException {
    private final String code;
    private final boolean uncertain;

    public PeerException(String code, boolean uncertain) {
        super(code);
        this.code = code;
        this.uncertain = uncertain;
    }

    public String code() { return code; }
    public boolean uncertain() { return uncertain; }
}
