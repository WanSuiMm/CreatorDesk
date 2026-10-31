package org.creatordesk.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.creatordesk.error.DeskException;
import org.springframework.stereotype.Component;

@Component
public final class OwnerRegistry {
    private static final int MAX_CONFIG_BYTES = 65_536;
    private final List<Entry> entries;
    private final Map<String, OwnerConfig> byOwner;

    public OwnerRegistry(ObjectMapper mapper) {
        String raw = System.getenv("CREATOR_DESK_OWNERS_JSON");
        if (raw == null || raw.isBlank() || raw.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IllegalStateException("CREATOR_DESK_OWNERS_JSON must configure one or more owners.");
        }
        try {
            List<Map<String, Object>> rows = mapper.readValue(raw, new TypeReference<>() {});
            if (rows == null || rows.isEmpty() || rows.size() > 32) throw new IllegalArgumentException();
            List<Entry> parsed = new ArrayList<>();
            java.util.LinkedHashMap<String, OwnerConfig> owners = new java.util.LinkedHashMap<>();
            Set<String> allSecrets = new HashSet<>();
            for (Map<String, Object> row : rows) {
                Set<String> allowed = Set.of("owner", "access_token", "c1_base_url", "c1_token",
                        "content_base_url", "content_token", "search_base_url", "search_token", "target_account_id");
                if (row == null || !allowed.containsAll(row.keySet())) throw new IllegalArgumentException();
                String owner = text(row.get("owner"), 100);
                String access = token(row.get("access_token"), true);
                String c1Url = baseUrl(row.get("c1_base_url"));
                String c1 = token(row.get("c1_token"), true);
                String contentUrl = baseUrl(row.get("content_base_url"));
                String content = token(row.get("content_token"), true);
                String searchUrl = baseUrl(row.get("search_base_url"));
                String search = token(row.get("search_token"), false);
                String accountId = text(row.get("target_account_id"), 100);
                if (owners.containsKey(owner)) throw new IllegalArgumentException();
                for (String secret : List.of(access, c1, content)) {
                    if (!allSecrets.add(secret)) throw new IllegalArgumentException();
                }
                if (search != null && !allSecrets.add(search)) throw new IllegalArgumentException();
                OwnerConfig config = new OwnerConfig(owner, access, c1Url, c1, contentUrl, content,
                        searchUrl, search, accountId);
                owners.put(owner, config);
                parsed.add(new Entry(digest(access), config));
            }
            this.entries = List.copyOf(parsed);
            this.byOwner = Map.copyOf(owners);
        } catch (Exception error) {
            if (error instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("CREATOR_DESK_OWNERS_JSON is invalid; credential values are not logged.");
        }
    }

    public OwnerConfig authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.length() > 4096) return null;
        byte[] supplied = digest(bearerToken);
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(supplied, entry.digest())) return entry.config();
        }
        return null;
    }

    public OwnerConfig forOwner(String owner) {
        return byOwner.get(owner);
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String string) || string.isBlank() || string.length() > maximum
                || !string.equals(string.trim()) || string.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        return string;
    }

    private static String token(Object value, boolean required) {
        if (value == null && !required) return null;
        String token = text(value, 4096);
        if (!token.matches("[\\x21-\\x7e]+")) throw new IllegalArgumentException();
        return token;
    }

    private static String baseUrl(Object value) {
        String raw = text(value, 2048);
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            if (scheme.equals("https")) return new URI(scheme, null, host, uri.getPort(), null, null, null).toString();
            String normalizedHost = host.toLowerCase(java.util.Locale.ROOT).replace("[", "").replace("]", "");
            boolean loopback = normalizedHost.equals("localhost") || normalizedHost.equals("::1")
                    || normalizedHost.matches("127(?:\\.\\d{1,3}){3}");
            if (!scheme.equals("http") || !loopback) throw new IllegalArgumentException();
            return new URI(scheme, null, host, uri.getPort(), null, null, null).toString();
        } catch (Exception ignored) {
            throw new IllegalArgumentException();
        }
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Entry(byte[] digest, OwnerConfig config) {}
}
