package org.creatordesk.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.creatordesk.error.DeskException;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@DependsOnDatabaseInitialization
public final class DeskStore {
    private static final int LOCK_CLASS = 1_129_465_424;
    private static final int LOCK_OBJECT = 1;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final DataSource dataSource;
    private Connection advisoryConnection;

    public DeskStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                     ObjectMapper mapper, DataSource dataSource) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void lockAndRecover() {
        try {
            advisoryConnection = dataSource.getConnection();
            try (PreparedStatement statement = advisoryConnection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                statement.setInt(1, LOCK_CLASS);
                statement.setInt(2, LOCK_OBJECT);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException("Another CreatorDesk Java service owns this database.");
                    }
                }
            }
            recoverInterruptedOperations();
        } catch (SQLException error) {
            releaseLock();
            throw new IllegalStateException("CreatorDesk could not initialize its PostgreSQL store.");
        } catch (RuntimeException error) {
            releaseLock();
            throw error;
        }
    }

    public Map<String, Object> create(String owner, String title, String brief) {
        return transactions.execute(status -> {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM desk_tasks WHERE owner = ?", Integer.class, owner);
            if (count == null || count >= 1000) throw new DeskException(429, "task_limit");
            String now = Instant.now().toString();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("task_id", "task_" + UUID.randomUUID());
            record.put("title", title);
            record.put("brief", brief);
            record.put("current_version", null);
            record.put("revision", 1L);
            record.put("sources", new ArrayList<>());
            record.put("versions", new ArrayList<>());
            record.put("reviews", new ArrayList<>());
            record.put("generations", new ArrayList<>());
            record.put("publications", new ArrayList<>());
            record.put("created_at", now);
            record.put("updated_at", now);
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO desk_tasks(owner, task_id, revision, record_json, updated_at) VALUES(?, ?, ?, CAST(? AS jsonb), ?)");
                statement.setString(1, owner);
                statement.setString(2, (String) record.get("task_id"));
                statement.setLong(3, 1);
                statement.setString(4, encode(record));
                statement.setTimestamp(5, Timestamp.from(Instant.parse(now)));
                return statement;
            });
            event(owner, (String) record.get("task_id"), "task_created", now);
            return copy(record);
        });
    }

    public Map<String, Object> get(String owner, String taskId) {
        List<String> rows = jdbc.query("SELECT record_json::text FROM desk_tasks WHERE owner = ? AND task_id = ?",
                (rs, row) -> rs.getString(1), owner, taskId);
        if (rows.isEmpty()) throw new DeskException(404, "task_not_found");
        return decode(rows.getFirst());
    }

    public List<Map<String, Object>> list(String owner) {
        return jdbc.query("SELECT record_json::text FROM desk_tasks WHERE owner = ? ORDER BY updated_at DESC, task_id LIMIT 1000",
                (rs, row) -> decode(rs.getString(1)), owner).stream().map(task -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (String field : List.of("task_id", "title", "brief", "current_version", "revision", "updated_at")) {
                        item.put(field, task.get(field));
                    }
                    return item;
                }).toList();
    }

    public Object change(String owner, String taskId, Function<Map<String, Object>, Object> operation, String eventKind) {
        return change(owner, taskId, operation, eventKind, false);
    }

    public Object change(String owner, String taskId, Function<Map<String, Object>, Object> operation,
                         String eventKind, boolean skipWhenNull) {
        return transactions.execute(status -> {
            Map<String, Object> task = get(owner, taskId);
            long revision = number(task.get("revision"), "revision");
            Object result = operation.apply(task);
            if (skipWhenNull && result == null) return null;
            Instant now = Instant.now();
            task.put("revision", revision + 1);
            task.put("updated_at", now.toString());
            String encoded = encode(task);
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024) {
                throw new DeskException(413, "task_storage_limit");
            }
            int changed = jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("UPDATE desk_tasks SET revision = ?, record_json = CAST(? AS jsonb), updated_at = ? "
                        + "WHERE owner = ? AND task_id = ? AND revision = ?");
                statement.setLong(1, revision + 1);
                statement.setString(2, encoded);
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setString(4, owner);
                statement.setString(5, taskId);
                statement.setLong(6, revision);
                return statement;
            });
            if (changed != 1) throw new DeskException(409, "concurrent_update", "Another request changed this task. Reload and retry.");
            event(owner, taskId, eventKind, now.toString());
            return copy(result);
        });
    }

    public List<Map<String, Object>> events(String owner, String taskId, long after) {
        get(owner, taskId);
        return jdbc.query("SELECT seq, kind, created_at FROM desk_events WHERE owner = ? AND task_id = ? AND seq > ? ORDER BY seq LIMIT 100",
                (rs, row) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", rs.getLong("seq"));
                    event.put("kind", rs.getString("kind"));
                    event.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    return event;
                }, owner, taskId, after);
    }

    void recoverInterruptedOperations() {
        List<String[]> identities = jdbc.query("SELECT owner, task_id FROM desk_tasks ORDER BY owner, task_id",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)});
        for (String[] identity : identities) {
            change(identity[0], identity[1], task -> {
                boolean changed = false;
                for (Map<String, Object> publication : maps(task.get("publications"))) {
                    if ("dispatching".equals(publication.get("status"))) {
                        publication.put("status", "unknown");
                        publication.put("error_code", "restart_during_publish");
                        changed = true;
                    }
                }
                for (Map<String, Object> generation : maps(task.get("generations"))) {
                    if ("starting".equals(generation.get("status"))) {
                        generation.put("status", "unknown");
                        generation.put("error_code", "restart_during_generation_start");
                        changed = true;
                    }
                }
                return changed ? Map.of("recovered", true) : null;
            }, "restart_checked", true);
        }
    }

    private void event(String owner, String taskId, String kind, String at) {
        jdbc.update("INSERT INTO desk_events(owner, task_id, kind, created_at) VALUES(?, ?, ?, ?)",
                owner, taskId, kind, Timestamp.from(Instant.parse(at)));
    }

    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new DeskException(500, "storage_encoding_failed"); }
    }

    private Map<String, Object> decode(String json) {
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException error) { throw new DeskException(500, "storage_record_invalid"); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copy(Object value) {
        if (value == null) return null;
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return (List<Map<String, Object>>) list;
    }

    private static long number(Object value, String field) {
        if (!(value instanceof Number number)) throw new DeskException(500, "storage_record_invalid", "Invalid " + field + ".");
        return number.longValue();
    }

    @PreDestroy
    public void releaseLock() {
        if (advisoryConnection == null) return;
        try (PreparedStatement statement = advisoryConnection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_CLASS);
            statement.setInt(2, LOCK_OBJECT);
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the dedicated session releases the PostgreSQL advisory lock.
        } finally {
            try { advisoryConnection.close(); } catch (SQLException ignored) { }
            advisoryConnection = null;
        }
    }
}
