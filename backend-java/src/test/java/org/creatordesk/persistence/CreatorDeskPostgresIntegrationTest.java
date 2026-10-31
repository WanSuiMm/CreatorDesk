package org.creatordesk.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.creatordesk.CreatorDeskApplication;
import org.creatordesk.config.OwnerRegistry;
import org.creatordesk.error.DeskException;
import org.creatordesk.peer.PeerClients;
import org.creatordesk.peer.PeerDirectory;
import org.creatordesk.peer.PeerException;
import org.creatordesk.service.CreatorWorkflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = CreatorDeskApplication.class, properties = "server.port=0")
@AutoConfigureMockMvc
class CreatorDeskPostgresIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<OwnerTask> cleanup = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Autowired DeskStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired OwnerRegistry ownerRegistry;

    @AfterEach
    void cleanupRows() {
        for (OwnerTask row : cleanup) jdbc.update("DELETE FROM desk_tasks WHERE owner = ? AND task_id = ?", row.owner(), row.taskId());
        cleanup.clear();
    }

    @Test
    void restRequiresBearerAndKeepsTasksOwnerScopedWhileSavingImmutableVersions() throws Exception {
        MvcResult health = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/healthz")
                        .header("Host", "127.0.0.1:8814"))
                .andReturn();
        assertThat(health.getResponse().getStatus()).isEqualTo(200);
        MvcResult missingAuth = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks")
                        .header("Host", "127.0.0.1:8814"))
                .andReturn();
        assertThat(missingAuth.getResponse().getStatus()).isEqualTo(401);

        String token = configuredToken();
        String taskId = createTaskThroughApi(token);
        MvcResult sourceResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tasks/{taskId}/sources", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Reference", "url", "https://example.org/paper", "excerpt", "Evidence."))))
                .andReturn();
        assertThat(sourceResponse.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> source = read(sourceResponse);
        String sourceId = (String) source.get("source_id");

        Map<String, Object> firstBody = new LinkedHashMap<>();
        firstBody.put("base_version", null);
        firstBody.put("body", "Draft [" + sourceId + "]");
        firstBody.put("source_ids", List.of(sourceId));
        MvcResult versionResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tasks/{taskId}/versions", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(firstBody)))
                .andReturn();
        assertThat(versionResponse.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> firstVersion = read(versionResponse);
        assertThat(firstVersion.get("version")).isEqualTo(1);

        MvcResult unconfirmedReview = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tasks/{taskId}/reviews", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "decision", "approved", "note", "reviewed"))))
                .andReturn();
        assertThat(unconfirmedReview.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(unconfirmedReview).get("error")).extracting("code").isEqualTo("source_review_confirmation_required");

        MvcResult approved = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tasks/{taskId}/reviews", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1, "decision", "approved", "note", "reviewed", "source_check_confirmed", true))))
                .andReturn();
        assertThat(approved.getResponse().getStatus()).isEqualTo(201);

        MvcResult foreignTask = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/{taskId}", "task_foreign_" + UUID.randomUUID())
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(foreignTask.getResponse().getStatus()).isEqualTo(404);

        Map<String, Object> secondBody = new LinkedHashMap<>();
        secondBody.put("base_version", 1);
        secondBody.put("body", "Revised [" + sourceId + "]");
        secondBody.put("source_ids", List.of(sourceId));
        MvcResult second = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tasks/{taskId}/versions", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(secondBody)))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(201);

        MvcResult stale = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tasks/{taskId}/versions", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(secondBody)))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(stale).get("error")).extracting("code").isEqualTo("stale_version");

        Map<String, Object> fullTask = read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/{taskId}", taskId)
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)).andReturn());
        assertThat(fullTask.get("current_version")).isEqualTo(2);
        assertThat((List<Map<String, Object>>) fullTask.get("versions")).hasSize(2);
        assertThat(((List<Map<String, Object>>) fullTask.get("versions")).getFirst().get("body")).isEqualTo("Draft [" + sourceId + "]");

        String foreignOwner = owner("foreign");
        Map<String, Object> foreign = store.create(foreignOwner, "private", "private brief");
        track(foreignOwner, foreign.get("task_id"));
        MvcResult foreignOwned = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tasks/{taskId}", foreign.get("task_id"))
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(foreignOwned.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void staticHomeLoadsWithoutBearerWhileApiRemainsProtected() throws Exception {
        MvcResult rootPage = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                        .header("Host", "127.0.0.1:8814"))
                .andReturn();
        assertThat(rootPage.getResponse().getStatus()).isEqualTo(200);
        assertThat(rootPage.getResponse().getForwardedUrl()).isEqualTo("index.html");
        assertThat(rootPage.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'self'");

        MvcResult indexPage = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/index.html")
                        .header("Host", "127.0.0.1:8814"))
                .andReturn();
        assertThat(indexPage.getResponse().getStatus()).isEqualTo(200);
        assertThat(indexPage.getResponse().getContentAsString()).contains("id=\"token-input\"");
        assertThat(indexPage.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'self'");

        MvcResult apiWithoutAuth = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/tasks")
                        .header("Host", "127.0.0.1:8814"))
                .andReturn();
        assertThat(apiWithoutAuth.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void actuatorMetricsRequireTheConfiguredBearerAndPrometheusEndpointIsEnabled() throws Exception {
        MvcResult missingAuth = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")
                        .header("Host", "127.0.0.1:8814"))
                .andReturn();
        assertThat(missingAuth.getResponse().getStatus()).isEqualTo(401);
        MvcResult authenticated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + configuredToken()))
                .andReturn();
        assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
        assertThat(authenticated.getResponse().getContentAsString()).contains("jvm_memory_used_bytes");
    }

    @Test
    void compareAndSetAllowsOnlyOneConcurrentTaskMutation() throws Exception {
        String owner = owner("race");
        Map<String, Object> task = store.create(owner, "Race", "initial");
        String taskId = (String) task.get("task_id");
        track(owner, taskId);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var operation = (java.util.concurrent.Callable<String>) () -> {
                try {
                    store.change(owner, taskId, current -> {
                        bothRead.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        current.put("brief", "race update");
                        return Map.of("saved", true);
                    }, "race_test");
                    return "saved";
                } catch (DeskException error) { return error.code(); }
            };
            Future<String> first = executor.submit(operation);
            Future<String> second = executor.submit(operation);
            assertThat(bothRead.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            List<String> outcomes = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("saved", "concurrent_update");
            assertThat(((Number) store.get(owner, taskId).get("revision")).longValue()).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void restartRecoveryMakesInFlightGenerationAndPublicationUnknownWithoutDispatch() {
        String owner = owner("restart");
        Map<String, Object> task = store.create(owner, "Restart", "brief");
        String taskId = (String) task.get("task_id");
        track(owner, taskId);
        store.change(owner, taskId, current -> {
            ((List<Map<String, Object>>) current.get("generations")).add(new LinkedHashMap<>(Map.of(
                    "generation_id", "gen_restart", "status", "starting", "run_id", "run_restart")));
            ((List<Map<String, Object>>) current.get("publications")).add(new LinkedHashMap<>(Map.of(
                    "publication_id", "pub_restart", "status", "dispatching")));
            return Map.of("prepared", true);
        }, "test_prepare_restart");

        store.recoverInterruptedOperations();

        Map<String, Object> recovered = store.get(owner, taskId);
        assertThat(((List<Map<String, Object>>) recovered.get("generations")).getFirst())
                .containsEntry("status", "unknown").containsEntry("error_code", "restart_during_generation_start");
        assertThat(((List<Map<String, Object>>) recovered.get("publications")).getFirst())
                .containsEntry("status", "unknown").containsEntry("error_code", "restart_during_publish");
    }

    @Test
    void generationIdempotencyAndLateCompletionCannotReplaceAnEditedDraft() {
        String owner = owner("generation");
        Map<String, Object> task = store.create(owner, "Generate", "brief");
        String taskId = (String) task.get("task_id");
        track(owner, taskId);
        PeerDirectory directory = mock(PeerDirectory.class);
        PeerClients client = mock(PeerClients.class);
        when(directory.forOwner(owner)).thenReturn(client);
        when(client.createSession(anyString())).thenReturn(Map.of("id", "session-one"));
        when(client.startRun(eq("session-one"), any(), anyString()))
                .thenThrow(new PeerException("transport_error", true))
                .thenReturn(Map.of("run", Map.of("id", "run-one"), "replayed", true));
        CreatorWorkflow workflow = new CreatorWorkflow(store, directory);
        Map<String, Object> source = workflow.addSource(owner, taskId,
                Map.of("title", "Ref", "url", "https://example.org/ref", "excerpt", "Source"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("base_version", null);
        Map<String, Object> generation = workflow.generate(owner, taskId, request, "gen_request");
        Map<String, Object> replay = workflow.generate(owner, taskId, request, "gen_request");
        assertThat(generation.get("status")).isEqualTo("unknown");
        assertThat(replay.get("replayed")).isEqualTo(true);
        verify(client, times(1)).createSession(anyString());
        Map<String, Object> adopted = workflow.syncGeneration(owner, taskId, (String) generation.get("generation_id"));
        assertThat(adopted.get("status")).isEqualTo("queued");
        verify(client, times(2)).startRun(eq("session-one"), any(), eq((String) generation.get("generation_id")));

        Map<String, Object> manualDraft = new LinkedHashMap<>();
        manualDraft.put("base_version", null);
        manualDraft.put("body", "Human edit [" + source.get("source_id") + "]");
        manualDraft.put("source_ids", List.of(source.get("source_id")));
        workflow.saveVersion(owner, taskId, manualDraft);
        when(client.getRun("run-one")).thenReturn(Map.of("id", "run-one", "session_id", "session-one", "status", "succeeded",
                "result", Map.of("summary", Map.of("answer", "late generation"))));

        Map<String, Object> synced = workflow.syncGeneration(owner, taskId, (String) generation.get("generation_id"));

        assertThat(synced.get("status")).isEqualTo("stale");
        Map<String, Object> after = store.get(owner, taskId);
        assertThat(after.get("current_version")).isEqualTo(1);
        assertThat(((List<Map<String, Object>>) after.get("versions")).getFirst().get("body"))
                .isEqualTo("Human edit [" + source.get("source_id") + "]");
    }

    @Test
    void c1StartingRunRemainsActiveAndCanCompleteOnLaterExplicitSync() {
        String owner = owner("generation_starting");
        Map<String, Object> task = store.create(owner, "Starting", "brief");
        String taskId = (String) task.get("task_id");
        track(owner, taskId);
        PeerDirectory directory = mock(PeerDirectory.class);
        PeerClients client = mock(PeerClients.class);
        when(directory.forOwner(owner)).thenReturn(client);
        when(client.createSession(anyString())).thenReturn(Map.of("id", "session-starting"));
        when(client.startRun(eq("session-starting"), any(), anyString()))
                .thenReturn(Map.of("run", Map.of("id", "run-starting"), "replayed", false));
        Map<String, Object> source = new CreatorWorkflow(store, directory).addSource(owner, taskId,
                Map.of("title", "Ref", "url", "https://example.org/starting", "excerpt", "Source"));
        CreatorWorkflow workflow = new CreatorWorkflow(store, directory);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("base_version", null);
        Map<String, Object> generation = workflow.generate(owner, taskId, request, "generation-starting");
        String generationId = (String) generation.get("generation_id");
        when(client.getRun("run-starting"))
                .thenReturn(Map.of("id", "run-starting", "session_id", "session-starting", "status", "starting"))
                .thenReturn(Map.of("id", "run-starting", "session_id", "session-starting", "status", "succeeded",
                        "result", Map.of("summary", Map.of("answer", "Draft [" + source.get("source_id") + "]"))));

        Map<String, Object> inProgress = workflow.syncGeneration(owner, taskId, generationId);
        assertThat(inProgress.get("status")).isEqualTo("queued");
        assertThat(store.get(owner, taskId).get("current_version")).isNull();

        Map<String, Object> completed = workflow.syncGeneration(owner, taskId, generationId);
        assertThat(completed.get("status")).isEqualTo("completed");
        assertThat(completed.get("error_code")).isNull();
        verify(client, times(1)).startRun(eq("session-starting"), any(), eq(generationId));
        verify(client, times(2)).getRun("run-starting");
    }

    @Test
    void c1UnknownRunStaysRecoverableUntilLaterExplicitSyncReadsItAgain() {
        String owner = owner("generation_unknown");
        Map<String, Object> task = store.create(owner, "Unknown", "brief");
        String taskId = (String) task.get("task_id");
        track(owner, taskId);
        PeerDirectory directory = mock(PeerDirectory.class);
        PeerClients client = mock(PeerClients.class);
        when(directory.forOwner(owner)).thenReturn(client);
        when(client.createSession(anyString())).thenReturn(Map.of("id", "session-unknown"));
        when(client.startRun(eq("session-unknown"), any(), anyString()))
                .thenReturn(Map.of("run", Map.of("id", "run-unknown"), "replayed", false));
        Map<String, Object> source = new CreatorWorkflow(store, directory).addSource(owner, taskId,
                Map.of("title", "Ref", "url", "https://example.org/unknown", "excerpt", "Source"));
        CreatorWorkflow workflow = new CreatorWorkflow(store, directory);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("base_version", null);
        Map<String, Object> generation = workflow.generate(owner, taskId, request, "generation-unknown");
        String generationId = (String) generation.get("generation_id");
        when(client.getRun("run-unknown"))
                .thenReturn(Map.of("id", "run-unknown", "session_id", "session-unknown", "status", "unknown"))
                .thenReturn(Map.of("id", "run-unknown", "session_id", "session-unknown", "status", "succeeded",
                        "result", Map.of("summary", Map.of("answer", "Draft [" + source.get("source_id") + "]"))));

        Map<String, Object> unknown = workflow.syncGeneration(owner, taskId, generationId);
        assertThat(unknown.get("status")).isEqualTo("unknown");
        assertThat(unknown.get("error_code")).isEqualTo("agent_outcome_unknown");

        Map<String, Object> completed = workflow.syncGeneration(owner, taskId, generationId);
        assertThat(completed.get("status")).isEqualTo("completed");
        assertThat(completed.get("error_code")).isNull();
        verify(client, times(1)).startRun(eq("session-unknown"), any(), eq(generationId));
        verify(client, times(2)).getRun("run-unknown");
    }

    @Test
    void explicitPublicationRetryReusesFrozenBodyAndOriginalIdempotencyKey() {
        String owner = owner("publish");
        Map<String, Object> task = store.create(owner, "Publish", "brief");
        String taskId = (String) task.get("task_id");
        track(owner, taskId);
        PeerDirectory directory = mock(PeerDirectory.class);
        PeerClients client = mock(PeerClients.class);
        when(directory.forOwner(owner)).thenReturn(client);
        when(client.accountId()).thenReturn("account-one");
        when(client.contentOrigin()).thenReturn("http://127.0.0.1:18080");
        when(client.account()).thenReturn(Map.of("user_id", "account-one"));
        CreatorWorkflow workflow = new CreatorWorkflow(store, directory);
        Map<String, Object> source = workflow.addSource(owner, taskId,
                Map.of("title", "Ref", "url", "https://example.org/ref", "excerpt", "Source"));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("base_version", null);
        draft.put("body", "Approved body [" + source.get("source_id") + "]");
        draft.put("source_ids", List.of(source.get("source_id")));
        Map<String, Object> version = workflow.saveVersion(owner, taskId, draft);
        workflow.review(owner, taskId, Map.of("version", 1, "decision", "approved", "note", "checked", "source_check_confirmed", true));
        Map<String, Object> preview = workflow.previewPublication(owner, taskId, Map.of("version", 1), "preview-key");
        String publicationId = (String) preview.get("publication_id");
        String exactBody = (String) preview.get("body");
        String hash = (String) preview.get("body_sha256");
        assertThat(version.get("version")).isEqualTo(1);
        store.change(owner, taskId, current -> {
            current.get("publications");
            ((List<Map<String, Object>>) current.get("publications")).getFirst().put("status", "unknown");
            return Map.of("unknown", true);
        }, "test_unknown_publication");
        when(client.publish(exactBody, publicationId)).thenThrow(new PeerException("transport_error", true))
                .thenReturn(Map.of("content", Map.of("content_id", "article-one"), "replayed", true));
        when(client.detail("article-one")).thenReturn(Map.of("content_id", "article-one", "body", exactBody,
                "author_id", "account-one", "visibility", "public"));
        Map<String, Object> confirmation = Map.of("confirm", true, "body_sha256", hash, "target_account_id", "account-one");

        Map<String, Object> stillUnknown = workflow.publish(owner, taskId, publicationId, confirmation, true);
        assertThat(stillUnknown.get("status")).isEqualTo("unknown");
        assertThat(((Number) stillUnknown.get("attempts")).intValue()).isEqualTo(1);
        Map<String, Object> published = workflow.publish(owner, taskId, publicationId, confirmation, true);

        assertThat(published.get("status")).isEqualTo("published");
        assertThat(((Number) published.get("attempts")).intValue()).isEqualTo(2);
        verify(client, times(2)).publish(exactBody, publicationId);
        verify(client, times(1)).detail("article-one");
    }

    private String createTaskThroughApi(String token) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tasks")
                        .header("Host", "127.0.0.1:8814").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "API task", "brief", "Write from sources."))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> task = read(result);
        track(ownerRegistry.authenticate(token).owner(), task.get("task_id"));
        return (String) task.get("task_id");
    }

    private String configuredToken() throws Exception {
        String raw = System.getenv("CREATOR_DESK_OWNERS_JSON");
        if (raw == null) throw new IllegalStateException("The PostgreSQL integration test needs configured owners.");
        return mapper.readTree(raw).get(0).get("access_token").asText();
    }

    private String owner(String label) { return "test_" + label + "_" + UUID.randomUUID().toString().replace("-", ""); }

    private void track(String owner, Object taskId) { cleanup.add(new OwnerTask(owner, String.valueOf(taskId))); }
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    private Map<String, Object> read(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
    }

    private record OwnerTask(String owner, String taskId) {}
}
