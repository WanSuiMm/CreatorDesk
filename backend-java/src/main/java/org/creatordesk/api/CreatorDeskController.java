package org.creatordesk.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.creatordesk.error.DeskException;
import org.creatordesk.service.CreatorWorkflow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public final class CreatorDeskController {
    private static final Set<String> NONE = Set.of();
    private final CreatorWorkflow workflow;

    public CreatorDeskController(CreatorWorkflow workflow) {
        this.workflow = workflow;
    }

    @GetMapping("/tasks")
    public Map<String, Object> listTasks(Authentication auth, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        return workflow.listTasks(owner(auth));
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(Authentication auth, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        BodyRules.fields(body, Set.of("title", "brief"), NONE);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflow.createTask(owner(auth), body));
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> getTask(Authentication auth, @PathVariable String taskId, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        return workflow.task(owner(auth), taskId);
    }

    @PostMapping("/tasks/{taskId}/sources")
    public ResponseEntity<?> addSource(Authentication auth, @PathVariable String taskId,
                                       @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        BodyRules.fields(body, Set.of("title", "url", "excerpt"), NONE);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflow.addSource(owner(auth), taskId, body));
    }

    @PostMapping("/tasks/{taskId}/versions")
    public ResponseEntity<?> saveVersion(Authentication auth, @PathVariable String taskId,
                                         @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        BodyRules.fields(body, Set.of("base_version", "body", "source_ids"), NONE);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflow.saveVersion(owner(auth), taskId, body));
    }

    @PostMapping("/tasks/{taskId}/reviews")
    public ResponseEntity<?> review(Authentication auth, @PathVariable String taskId,
                                    @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        BodyRules.fields(body, Set.of("version", "decision", "note"), Set.of("source_check_confirmed"));
        return ResponseEntity.status(HttpStatus.CREATED).body(workflow.review(owner(auth), taskId, body));
    }

    @PostMapping("/tasks/{taskId}/generations")
    public ResponseEntity<?> generate(Authentication auth, @PathVariable String taskId,
                                      @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        BodyRules.fields(body, Set.of("base_version"), NONE);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                workflow.generate(owner(auth), taskId, body, BodyRules.idempotencyKey(request)));
    }

    @PostMapping("/tasks/{taskId}/generations/{generationId}/sync")
    public Map<String, Object> syncGeneration(Authentication auth, @PathVariable String taskId,
                                              @PathVariable String generationId, @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        generationId(generationId);
        BodyRules.fields(body, NONE, NONE);
        return workflow.syncGeneration(owner(auth), taskId, generationId);
    }

    @GetMapping("/tasks/{taskId}/generations/{generationId}/events")
    public Map<String, Object> generationEvents(Authentication auth, @PathVariable String taskId,
                                                @PathVariable String generationId, @RequestParam(defaultValue = "0") long after,
                                                HttpServletRequest request) {
        taskId(taskId);
        generationId(generationId);
        if (!request.getParameterMap().isEmpty()) BodyRules.queryKeys(request, Set.of("after"));
        if (after < 0) throw new DeskException(400, "invalid_cursor");
        return workflow.generationEvents(owner(auth), taskId, generationId, after);
    }

    @PostMapping("/tasks/{taskId}/publications/preview")
    public ResponseEntity<?> previewPublication(Authentication auth, @PathVariable String taskId,
                                                @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        BodyRules.fields(body, Set.of("version"), NONE);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                workflow.previewPublication(owner(auth), taskId, body, BodyRules.idempotencyKey(request)));
    }

    @PostMapping("/tasks/{taskId}/publications/{publicationId}/confirm")
    public Map<String, Object> confirmPublication(Authentication auth, @PathVariable String taskId,
                                                  @PathVariable String publicationId, @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        publicationId(publicationId);
        BodyRules.fields(body, Set.of("confirm", "body_sha256", "target_account_id"), NONE);
        return workflow.publish(owner(auth), taskId, publicationId, body, false);
    }

    @PostMapping("/tasks/{taskId}/publications/{publicationId}/retry")
    public Map<String, Object> retryPublication(Authentication auth, @PathVariable String taskId,
                                                @PathVariable String publicationId, @RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        publicationId(publicationId);
        BodyRules.fields(body, Set.of("confirm", "body_sha256", "target_account_id"), NONE);
        return workflow.publish(owner(auth), taskId, publicationId, body, true);
    }

    @PostMapping("/tasks/{taskId}/publications/{publicationId}/reconcile")
    public Map<String, Object> reconcilePublication(Authentication auth, @PathVariable String taskId,
                                                    @PathVariable String publicationId, @RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        BodyRules.emptyQuery(request);
        taskId(taskId);
        publicationId(publicationId);
        BodyRules.fields(body, NONE, NONE);
        return workflow.reconcilePublication(owner(auth), taskId, publicationId);
    }

    @GetMapping("/search")
    public Map<String, Object> search(Authentication auth, @RequestParam String q, HttpServletRequest request) {
        BodyRules.queryKeys(request, Set.of("q"));
        return workflow.search(owner(auth), q);
    }

    private static String owner(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof String owner)) throw new DeskException(401, "authentication_required");
        return owner;
    }

    private static void taskId(String id) {
        if (id == null || !id.matches("task_[a-zA-Z0-9-]{1,80}")) throw new DeskException(404, "route_not_found");
    }

    private static void generationId(String id) {
        if (id == null || !id.matches("gen_[a-zA-Z0-9-]{1,80}")) throw new DeskException(404, "record_not_found");
    }

    private static void publicationId(String id) {
        if (id == null || !id.matches("pub_[a-zA-Z0-9-]{1,80}")) throw new DeskException(404, "record_not_found");
    }
}
