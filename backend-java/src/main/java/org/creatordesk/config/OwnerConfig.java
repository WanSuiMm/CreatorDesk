package org.creatordesk.config;

public final class OwnerConfig {
    private final String owner;
    private final String accessToken;
    private final String c1BaseUrl;
    private final String c1Token;
    private final String contentBaseUrl;
    private final String contentToken;
    private final String searchBaseUrl;
    private final String searchToken;
    private final String targetAccountId;

    public OwnerConfig(String owner, String accessToken, String c1BaseUrl, String c1Token,
                       String contentBaseUrl, String contentToken, String searchBaseUrl,
                       String searchToken, String targetAccountId) {
        this.owner = owner;
        this.accessToken = accessToken;
        this.c1BaseUrl = c1BaseUrl;
        this.c1Token = c1Token;
        this.contentBaseUrl = contentBaseUrl;
        this.contentToken = contentToken;
        this.searchBaseUrl = searchBaseUrl;
        this.searchToken = searchToken;
        this.targetAccountId = targetAccountId;
    }

    public String owner() { return owner; }
    public String accessToken() { return accessToken; }
    public String c1BaseUrl() { return c1BaseUrl; }
    public String c1Token() { return c1Token; }
    public String contentBaseUrl() { return contentBaseUrl; }
    public String contentToken() { return contentToken; }
    public String searchBaseUrl() { return searchBaseUrl; }
    public String searchToken() { return searchToken; }
    public String targetAccountId() { return targetAccountId; }

    @Override
    public String toString() { return "OwnerConfig[owner=" + owner + ", peers=configured]"; }
}
