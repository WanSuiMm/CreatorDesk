package org.creatordesk.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.creatordesk.config.OwnerConfig;

public final class PeerClients {
    private final PeerHttp c1;
    private final PeerHttp content;
    private final PeerHttp search;
    private final String accountId;
    private final String contentOrigin;

    public PeerClients(OwnerConfig config, ObjectMapper mapper) {
        this.c1 = new PeerHttp(config.c1BaseUrl(), config.c1Token(), mapper);
        this.content = new PeerHttp(config.contentBaseUrl(), config.contentToken(), mapper);
        this.search = new PeerHttp(config.searchBaseUrl(), config.searchToken(), mapper);
        this.accountId = config.targetAccountId();
        this.contentOrigin = config.contentBaseUrl();
    }

    public String accountId() { return accountId; }
    public String contentOrigin() { return contentOrigin; }

    public Map<String, Object> createSession(String title) {
        if (title == null || title.length() > 200) throw new PeerException("invalid_request", false);
        Map<String, Object> result = c1.json("/v1/sessions", "POST", Map.of("title", title), null, true, 201);
        if (!(result.get("id") instanceof String id) || id.isBlank()) throw new PeerException("invalid_response", true);
        return result;
    }

    public Map<String, Object> startRun(String sessionId, Map<String, Object> input, String key) {
        String path = "/v1/sessions/" + encodeId(sessionId) + "/runs";
        Map<String, Object> result = c1.json(path, "POST", input, key, true, 200, 202);
        Map<String, Object> run = object(result.get("run"));
        if (run == null || !(run.get("id") instanceof String id) || id.isBlank()
                || !(result.get("replayed") instanceof Boolean)) throw new PeerException("invalid_response", true);
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("run", run);
        started.put("replayed", result.get("replayed"));
        return started;
    }

    public Map<String, Object> getRun(String runId) {
        Map<String, Object> run = c1.json("/v1/runs/" + encodeId(runId), "GET", null, null, false, 200);
        if (!runId.equals(run.get("id"))) throw new PeerException("invalid_response", false);
        return run;
    }

    public List<Map<String, Object>> getEvents(String runId, long after) {
        if (after < 0) throw new PeerException("invalid_request", false);
        return c1.sse("/v1/runs/" + encodeId(runId) + "/events?after=" + after, after);
    }

    public Map<String, Object> account() {
        Map<String, Object> account = content.json("/v1/me", "GET", null, null, false, 200);
        if (!accountId.equals(account.get("user_id"))) throw new PeerException("invalid_response", false);
        return account;
    }

    public Map<String, Object> publish(String body, String key) {
        if (body == null || body.trim().isEmpty() || body.length() > 20_000) throw new PeerException("invalid_request", false);
        String normalized = body.trim();
        Map<String, Object> result = content.json("/v1/content", "POST", Map.of("body", normalized), key, true, 200, 201);
        Map<String, Object> article = object(result.get("content"));
        if (article == null || !(article.get("content_id") instanceof String id) || id.isBlank()
                || !accountId.equals(article.get("author_id")) || !normalized.equals(article.get("body"))
                || !(result.get("replayed") instanceof Boolean)) throw new PeerException("invalid_response", true);
        return Map.of("content", article, "replayed", result.get("replayed"));
    }

    public Map<String, Object> search(String query, int limit) {
        if (query == null || query.length() > 1800 || limit < 1 || limit > 20) throw new PeerException("invalid_request", false);
        String path = "/v1/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=" + limit;
        Map<String, Object> result = search.json(path, "GET", null, null, false, 200);
        Object rawItems = result.get("items");
        if (!(rawItems instanceof List<?> items)) throw new PeerException("invalid_response", false);
        for (Object raw : items) validatePublicContent(object(raw), null);
        return result;
    }

    public Map<String, Object> detail(String contentId) {
        Map<String, Object> article = search.json("/v1/content/" + encodeId(contentId), "GET", null, null, false, 200);
        validatePublicContent(article, contentId);
        return article;
    }

    private void validatePublicContent(Map<String, Object> article, String expectedId) {
        if (article == null || !(article.get("content_id") instanceof String id) || id.isBlank()
                || (expectedId != null && !expectedId.equals(id))
                || !(article.get("author_id") instanceof String)
                || !(article.get("body") instanceof String)
                || !(article.get("published_at") instanceof String)
                || !(article.get("version") instanceof Number version) || version.intValue() < 1
                || !"public".equals(article.get("visibility"))
                || !("/v1/content/" + id).equals(article.get("source_uri"))) {
            throw new PeerException("invalid_response", false);
        }
    }

    private static String encodeId(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || value.contains("/")) throw new PeerException("invalid_request", false);
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }
}
