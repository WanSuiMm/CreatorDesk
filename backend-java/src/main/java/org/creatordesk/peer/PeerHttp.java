package org.creatordesk.peer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import org.springframework.http.MediaType;

final class PeerHttp {
    static final int MAX_BYTES = 256 * 1024;
    private static final ScheduledExecutorService SSE_TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "creator-desk-sse-idle");
        thread.setDaemon(true);
        return thread;
    });
    private final URI base;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client;

    PeerHttp(String baseUrl, String token, ObjectMapper mapper) {
        this.base = URI.create(baseUrl);
        this.token = token;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new DirectProxySelector())
                .build();
    }

    Map<String, Object> json(String path, String method, Object body, String idempotencyKey, boolean mutating,
                             int... acceptedStatuses) {
        byte[] payload = null;
        try {
            if (body != null) {
                payload = mapper.writeValueAsBytes(body);
                if (payload.length > MAX_BYTES) throw new PeerException("invalid_request", false);
            }
        } catch (JsonProcessingException error) {
            throw new PeerException("invalid_request", false);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3))
                .header("accept", MediaType.APPLICATION_JSON_VALUE)
                .header("accept-encoding", "identity");
        if (token != null) request.header("authorization", "Bearer " + token);
        if (payload != null) request.header("content-type", "application/json; charset=utf-8");
        if (idempotencyKey != null) request.header("idempotency-key", idempotencyKey);
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
        HttpResponse<byte[]> response = exchange(request.build(), mutating);
        if (!accepted(response.statusCode(), acceptedStatuses)) {
            boolean uncertain = mutating && (response.statusCode() < 400 || response.statusCode() >= 500);
            throw new PeerException("http_error", uncertain);
        }
        String contentType = response.headers().firstValue("content-type").orElse("").split(";", 2)[0].trim();
        if (!contentType.equalsIgnoreCase(MediaType.APPLICATION_JSON_VALUE)) throw new PeerException("invalid_response", mutating);
        try {
            Map<String, Object> value = mapper.readValue(response.body(), new TypeReference<>() {});
            if (value == null) throw new PeerException("invalid_response", mutating);
            return value;
        } catch (IOException error) {
            throw new PeerException("invalid_response", mutating);
        }
    }

    List<Map<String, Object>> sse(String path, long after) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3))
                .header("accept", "text/event-stream")
                .header("accept-encoding", "identity");
        if (token != null) requestBuilder.header("authorization", "Bearer " + token);
        HttpRequest request = requestBuilder.GET().build();
        HttpResponse<byte[]> response = exchange(request, false);
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new PeerException("http_error", false);
        String contentType = response.headers().firstValue("content-type").orElse("").split(";", 2)[0].trim();
        if (!contentType.equalsIgnoreCase("text/event-stream")) throw new PeerException("invalid_response", false);
        final String text;
        try { text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response.body())).toString(); }
        catch (Exception error) { throw new PeerException("invalid_response", false); }
        return parseEvents(text, after);
    }

    private HttpResponse<byte[]> exchange(HttpRequest request, boolean mutating) {
        try {
            boolean eventStream = request.headers().firstValue("accept").orElse("").equals("text/event-stream");
            return client.send(request, info -> new BoundedSubscriber(MAX_BYTES, eventStream ? 150 : 0));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PeerException("transport_error", mutating);
        } catch (java.net.http.HttpTimeoutException error) {
            throw new PeerException("timeout", mutating);
        } catch (BoundedSubscriber.TooLargeException error) {
            throw new PeerException("response_too_large", mutating);
        } catch (IOException error) {
            Throwable cause = error.getCause();
            if (cause instanceof BoundedSubscriber.TooLargeException) throw new PeerException("response_too_large", mutating);
            throw new PeerException("transport_error", mutating);
        } catch (RuntimeException error) {
            if (error instanceof PeerException peer) throw peer;
            throw new PeerException("transport_error", mutating);
        }
    }

    private URI uri(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) throw new PeerException("invalid_request", false);
        try { return URI.create(base.toString() + path); }
        catch (IllegalArgumentException error) { throw new PeerException("invalid_request", false); }
    }

    private static boolean accepted(int status, int[] statuses) {
        for (int accepted : statuses) if (status == accepted) return true;
        return false;
    }

    private List<Map<String, Object>> parseEvents(String text, long after) {
        List<Map<String, Object>> events = new ArrayList<>();
        String[] blocks = text.split("\\r?\\n\\r?\\n");
        for (String block : blocks) {
            String idText = null;
            String eventName = null;
            List<String> dataLines = new ArrayList<>();
            for (String line : block.split("\\r?\\n")) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if (field.equals("id")) idText = value;
                else if (field.equals("event")) eventName = value;
                else if (field.equals("data")) dataLines.add(value);
            }
            if (dataLines.isEmpty()) continue;
            try {
                long id = Long.parseLong(idText == null ? "" : idText);
                Map<String, Object> event = mapper.readValue(String.join("\n", dataLines), new TypeReference<>() {});
                Object eventId = event.get("id");
                if (!(eventId instanceof Number number) || number.longValue() != id || id < 0
                        || !(event.get("type") instanceof String type)
                        || (eventName != null && !eventName.equals(type))) throw new IllegalArgumentException();
                if (id > after) events.add(event);
                if (events.size() >= 1000) break;
            } catch (Exception error) { throw new PeerException("invalid_response", false); }
        }
        return events;
    }

    private static final class DirectProxySelector extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
    }

    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final int maxBytes;
        private final int idleMillis;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean tooLarge;
        private boolean completed;
        private ScheduledFuture<?> idleTimer;

        BoundedSubscriber(int maxBytes, int idleMillis) { this.maxBytes = maxBytes; this.idleMillis = idleMillis; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            subscription = value;
            armIdleTimer();
            subscription.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (completed) return;
            if (idleTimer != null) idleTimer.cancel(false);
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (bytes.size() + size > maxBytes) {
                    tooLarge = true;
                    completed = true;
                    subscription.cancel();
                    body.completeExceptionally(new TooLargeException());
                    return;
                }
                byte[] chunk = new byte[size];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            armIdleTimer();
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) {
            if (!completed) { completed = true; cancelTimer(); body.completeExceptionally(error); }
        }
        @Override public synchronized void onComplete() {
            if (!completed) { completed = true; cancelTimer(); body.complete(bytes.toByteArray()); }
        }

        private void armIdleTimer() {
            if (idleMillis <= 0 || completed) return;
            idleTimer = SSE_TIMER.schedule(this::finishIdle, idleMillis, TimeUnit.MILLISECONDS);
        }

        private synchronized void finishIdle() {
            if (completed) return;
            completed = true;
            subscription.cancel();
            body.complete(bytes.toByteArray());
        }

        private void cancelTimer() { if (idleTimer != null) idleTimer.cancel(false); }

        static final class TooLargeException extends IOException { }
    }
}
