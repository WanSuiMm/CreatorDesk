package org.creatordesk.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import org.creatordesk.error.DeskException;

public final class BodyRules {
    private BodyRules() { }

    public static void fields(Map<String, Object> body, Set<String> required, Set<String> optional) {
        if (body == null || !body.keySet().containsAll(required)) throw new DeskException(400, "invalid_fields");
        for (String key : body.keySet()) if (!required.contains(key) && !optional.contains(key)) throw new DeskException(400, "invalid_fields");
    }

    public static String idempotencyKey(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Idempotency-Key");
        if (values == null || !values.hasMoreElements()) throw new DeskException(400, "invalid_idempotency_key");
        String key = values.nextElement();
        if (values.hasMoreElements() || key == null || key.isBlank() || key.length() > 128
                || !key.matches("[\\x21-\\x7e]+")) throw new DeskException(400, "invalid_idempotency_key");
        return key;
    }

    public static void queryKeys(HttpServletRequest request, Set<String> expectedSingleKey) {
        Map<String, String[]> params = request.getParameterMap();
        if (!params.keySet().equals(expectedSingleKey)
                || params.values().stream().anyMatch(values -> values == null || values.length != 1)) {
            throw new DeskException(400, "invalid_query");
        }
    }

    public static void emptyQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new DeskException(400, "invalid_query");
    }
}
