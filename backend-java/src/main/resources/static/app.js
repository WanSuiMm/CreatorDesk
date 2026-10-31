"use strict";
class ApiFailure extends Error {
    constructor(message, status) {
        super(message);
        this.name = "ApiFailure";
        this.status = status;
    }
}
const byId = (id) => {
    const node = document.getElementById(id);
    if (!node)
        throw new Error("页面缺少必要控件：" + id);
    return node;
};
const taskListNode = byId("task-list");
const taskCountNode = byId("task-count");
const taskForm = byId("task-form");
const taskTitleInput = byId("new-task-title");
const taskBriefInput = byId("new-task-brief");
const tokenInput = byId("token-input");
const tokenStatusNode = byId("token-status");
const apiStatusNode = byId("api-status");
const alertNode = byId("alert");
const noticeNode = byId("notice");
const emptyStateNode = byId("empty-state");
const workspaceNode = byId("workspace");
const taskNameNode = byId("task-name");
const taskBriefNode = byId("task-brief");
const taskStatsNode = byId("task-stats");
const currentVersionNode = byId("current-version-chip");
const draftBodyInput = byId("draft-body");
const draftDirtyNode = byId("draft-dirty");
const saveVersionButton = byId("save-version");
const draftSourceOptions = byId("draft-source-options");
const versionListNode = byId("version-list");
const versionCountNode = byId("version-count");
const inspectedVersionLabel = byId("inspected-version-label");
const inspectedVersionBody = byId("inspected-version-body");
const copyVersionButton = byId("copy-version");
const sourceForm = byId("source-form");
const sourceTitleInput = byId("source-title");
const sourceUrlInput = byId("source-url");
const sourceExcerptInput = byId("source-excerpt");
const sourceCountNode = byId("source-count");
const sourceListNode = byId("source-list");
const startGenerationButton = byId("start-generation");
const generationListNode = byId("generation-list");
const reviewStateNode = byId("review-state");
const reviewTargetNode = byId("review-target");
const reviewNoteInput = byId("review-note");
const sourceCheckInput = byId("source-check-confirmed");
const approveButton = byId("approve-version");
const rejectButton = byId("reject-version");
const reviewHistoryNode = byId("review-history");
const previewPublicationButton = byId("preview-publication");
const publicationPreviewNode = byId("publish-preview");
const previewStatusNode = byId("preview-status");
const previewTargetNode = byId("preview-target");
const previewHashNode = byId("preview-hash");
const previewBodyNode = byId("preview-body");
const confirmCheckbox = byId("confirm-checkbox");
const confirmPhraseInput = byId("confirm-phrase");
const confirmPublicationButton = byId("confirm-publication");
const publicationRecoveryNode = byId("publication-recovery");
const recoveryNoteNode = byId("publication-recovery-note");
const retryConfirmationNode = byId("retry-confirmation");
const retryCheckbox = byId("retry-checkbox");
const retryPhraseInput = byId("retry-phrase");
const reconcilePublicationButton = byId("reconcile-publication");
const retryPublicationButton = byId("retry-publication");
const publicationListNode = byId("publication-list");
const searchForm = byId("search-form");
const searchQueryInput = byId("search-query");
const searchSubmitButton = byId("search-submit");
const searchResultsNode = byId("search-results");
let bearerToken = null;
let tasks = [];
let activeTaskId = null;
let activeTask = null;
let inspectedVersionNumber = null;
let activePublicationId = null;
let selectedSourceIds = new Set();
let baselineBody = "";
let baselineSourceIds = new Set();
let baselineVersionNumber = null;
let generationEventRows = new Map();
let generationEventCursors = new Map();
let uncertainPublicationIds = new Set();
let lastRenderedPublicationId = null;
let lastRenderedPublicationStatus = null;
function record(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value)
        ? value
        : {};
}
function stringValue(value, fallback = "") {
    return typeof value === "string" ? value : value == null ? fallback : String(value);
}
function numberValue(value, fallback = 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
}
function arrayValue(value) {
    return Array.isArray(value) ? value : [];
}
function textNode(tag, text, className) {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className)
        node.className = className;
    return node;
}
function setStatusBadge(node, status) {
    node.textContent = statusLabel(status);
    node.className = "status-badge";
}
function statusLabel(status) {
    const labels = {
        recorded_unverified: "已记录 · 未核验",
        prepared: "已准备预览",
        dispatching: "正在提交",
        unknown: "结果待确认",
        unverified: "回执未核验",
        published: "已发布",
        stale: "预览已过期",
        failed: "提交失败",
        approved: "已通过",
        rejected: "已拒绝",
        queued: "排队中",
        running: "运行中",
        completed: "已完成",
        succeeded: "已完成",
        failed_permanent: "执行失败",
        cancelled: "已取消",
        starting: "正在启动"
    };
    return labels[status] || status || "状态未知";
}
function formatDate(value) {
    if (!value)
        return "时间未提供";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN");
}
function makeButton(label, className, onClick) {
    const button = textNode("button", label, "button " + className);
    button.type = "button";
    button.addEventListener("click", onClick);
    return button;
}
function showAlert(message) {
    alertNode.textContent = message;
    alertNode.hidden = !message;
    if (message)
        noticeNode.hidden = true;
}
function showNotice(message) {
    noticeNode.textContent = message;
    noticeNode.hidden = !message;
    if (message)
        alertNode.hidden = true;
}
function clearMessages() {
    alertNode.hidden = true;
    noticeNode.hidden = true;
}
function setApiStatus(label, isError = false) {
    apiStatusNode.textContent = label;
    apiStatusNode.classList.toggle("is-error", isError);
}
async function request(path, options = {}) {
    const headers = new Headers();
    if (bearerToken)
        headers.set("Authorization", "Bearer " + bearerToken);
    if (options.body !== undefined)
        headers.set("Content-Type", "application/json");
    if (options.idempotencyKey)
        headers.set("Idempotency-Key", options.idempotencyKey);
    const response = await fetch(new URL(path, window.location.origin), {
        method: options.method || "GET",
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        credentials: "same-origin",
        cache: "no-store"
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
        const outer = record(payload);
        const error = record(outer.error);
        const code = stringValue(error.code);
        const message = stringValue(error.message, "请求未成功（HTTP " + response.status + "）");
        throw new ApiFailure(code ? code + "：" + message : message, response.status);
    }
    return payload;
}
function newIdempotencyKey() {
    if (typeof crypto.randomUUID === "function")
        return crypto.randomUUID();
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
}
function routeTask(path) {
    if (!activeTaskId)
        throw new Error("请先选择一个任务。");
    return "/api/tasks/" + encodeURIComponent(activeTaskId) + path;
}
function showRequestError(error) {
    const message = error instanceof Error ? error.message : "请求未能完成，请稍后重试。";
    showAlert(message);
    setApiStatus("请求失败", true);
}
function sourceIdsEqual(left, right) {
    if (left.size !== right.size)
        return false;
    for (const value of left)
        if (!right.has(value))
            return false;
    return true;
}
function draftIsDirty() {
    return draftBodyInput.value !== baselineBody || !sourceIdsEqual(selectedSourceIds, baselineSourceIds);
}
function editorMatchesSelectedVersion() {
    const version = selectedVersion();
    return Boolean(version && draftBodyInput.value === version.body && sourceIdsEqual(selectedSourceIds, new Set(version.source_ids)));
}
function selectedVersion() {
    if (!activeTask || inspectedVersionNumber === null)
        return null;
    return activeTask.versions.find(item => item.version === inspectedVersionNumber) || null;
}
function latestReview(version) {
    if (!activeTask)
        return null;
    const matching = activeTask.reviews.filter(item => numberValue(item.version, -1) === version);
    if (matching.length === 0)
        return null;
    matching.sort((left, right) => {
        const leftTime = Date.parse(stringValue(left.created_at));
        const rightTime = Date.parse(stringValue(right.created_at));
        if (!Number.isNaN(leftTime) && !Number.isNaN(rightTime) && leftTime !== rightTime)
            return leftTime - rightTime;
        return activeTask.reviews.indexOf(left) - activeTask.reviews.indexOf(right);
    });
    return matching[matching.length - 1] || null;
}
function publicationById(id) {
    if (!activeTask || !id)
        return null;
    return activeTask.publications.find(item => item.publication_id === id) || null;
}
function normalizeTaskSummary(value) {
    const item = record(value);
    return {
        task_id: stringValue(item.task_id, stringValue(item.id)),
        title: stringValue(item.title, "未命名任务"),
        brief: stringValue(item.brief)
    };
}
function normalizeTaskDetail(value) {
    const item = record(value);
    const versions = arrayValue(item.versions).map(raw => {
        const row = record(raw);
        return {
            ...row,
            version: numberValue(row.version),
            body: stringValue(row.body),
            sha256: stringValue(row.sha256),
            source_ids: arrayValue(row.source_ids).map(id => stringValue(id)),
            origin: stringValue(row.origin),
            created_at: stringValue(row.created_at)
        };
    });
    const sources = arrayValue(item.sources).map(raw => {
        const row = record(raw);
        return {
            ...row,
            source_id: stringValue(row.source_id),
            title: stringValue(row.title, "未命名来源"),
            url: stringValue(row.url),
            excerpt: stringValue(row.excerpt),
            sha256: stringValue(row.sha256),
            status: stringValue(row.status, "recorded_unverified")
        };
    });
    const reviews = arrayValue(item.reviews).map(raw => {
        const row = record(raw);
        return { ...row, version: numberValue(row.version), decision: stringValue(row.decision), note: stringValue(row.note) };
    });
    const generations = arrayValue(item.generations).map(raw => {
        const row = record(raw);
        return { ...row, generation_id: stringValue(row.generation_id), status: stringValue(row.status) };
    });
    const publications = arrayValue(item.publications).map(raw => {
        const row = record(raw);
        const target = record(row.target);
        return {
            ...row,
            publication_id: stringValue(row.publication_id),
            version: numberValue(row.version),
            body: stringValue(row.body),
            body_sha256: stringValue(row.body_sha256),
            target: {
                ...target,
                account_id: stringValue(target.account_id),
                origin: stringValue(target.origin),
                visibility: stringValue(target.visibility)
            },
            status: stringValue(row.status)
        };
    });
    const current = item.current_version == null ? null : numberValue(item.current_version, -1);
    return {
        ...normalizeTaskSummary(item),
        current_version: current !== null && current >= 0 ? current : null,
        sources,
        versions,
        reviews,
        generations,
        publications,
        revision: numberValue(item.revision)
    };
}
function taskRowsFromPayload(payload) {
    if (Array.isArray(payload))
        return payload.map(normalizeTaskSummary);
    const item = record(payload);
    const rows = Array.isArray(item.tasks) ? item.tasks : Array.isArray(item.items) ? item.items : [];
    return rows.map(normalizeTaskSummary);
}
function renderTaskList() {
    taskCountNode.textContent = String(tasks.length);
    taskListNode.replaceChildren();
    if (tasks.length === 0) {
        taskListNode.append(textNode("div", bearerToken ? "还没有任务" : "载入令牌后查看任务", "task-empty"));
        return;
    }
    for (const task of tasks) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "task-button";
        button.setAttribute("aria-current", task.task_id === activeTaskId ? "page" : "false");
        const icon = textNode("span", "✦", "task-icon");
        icon.setAttribute("aria-hidden", "true");
        const copy = textNode("span", "", "task-copy");
        copy.append(textNode("strong", task.title), textNode("small", task.brief ? task.brief.slice(0, 54) : "打开任务工作区"));
        button.append(icon, copy);
        button.addEventListener("click", () => {
            void selectTask(task.task_id);
        });
        taskListNode.append(button);
    }
}
function setDraftBaseline(body, sourceIds, versionNumber) {
    baselineBody = body;
    baselineSourceIds = new Set(sourceIds);
    baselineVersionNumber = versionNumber;
    selectedSourceIds = new Set(sourceIds);
    draftBodyInput.value = body;
}
function initializeDraftFromTask() {
    if (!activeTask) {
        setDraftBaseline("", [], null);
        return;
    }
    let current = activeTask.current_version === null
        ? null
        : activeTask.versions.find(version => version.version === activeTask.current_version) || null;
    if (!current && activeTask.versions.length > 0)
        current = activeTask.versions[activeTask.versions.length - 1] || null;
    if (current) {
        inspectedVersionNumber = current.version;
        setDraftBaseline(current.body, current.source_ids, current.version);
    }
    else {
        inspectedVersionNumber = null;
        setDraftBaseline("", [], null);
    }
}
function renderSourceOptions() {
    draftSourceOptions.replaceChildren();
    if (!activeTask || activeTask.sources.length === 0) {
        draftSourceOptions.append(textNode("div", "先记录来源，再勾选用于草稿的摘录。", "source-option-empty"));
        return;
    }
    for (const source of activeTask.sources) {
        const label = document.createElement("label");
        label.className = "source-option";
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = source.source_id;
        checkbox.checked = selectedSourceIds.has(source.source_id);
        checkbox.addEventListener("change", () => {
            if (checkbox.checked)
                selectedSourceIds.add(source.source_id);
            else
                selectedSourceIds.delete(source.source_id);
            renderControls();
        });
        const copy = textNode("span", source.title + " · " + statusLabel(source.status));
        label.append(checkbox, copy);
        draftSourceOptions.append(label);
    }
}
function renderSources() {
    if (!activeTask)
        return;
    sourceCountNode.textContent = String(activeTask.sources.length);
    sourceListNode.replaceChildren();
    for (const source of activeTask.sources) {
        const card = textNode("article", "", "source-record");
        const heading = textNode("div", "", "record-heading");
        heading.append(textNode("strong", source.title));
        const badge = textNode("span", "", "status-badge");
        setStatusBadge(badge, source.status);
        heading.append(badge);
        card.append(heading, textNode("p", source.excerpt));
        if (source.url)
            card.append(textNode("span", source.url, "source-url"));
        const meta = textNode("div", "", "source-meta");
        meta.append(textNode("span", source.source_id));
        meta.append(textNode("code", source.sha256 || "未提供"));
        card.append(meta);
        sourceListNode.append(card);
    }
}
function renderVersions() {
    if (!activeTask)
        return;
    versionCountNode.textContent = activeTask.versions.length + " 版";
    versionListNode.replaceChildren();
    const ordered = [...activeTask.versions].sort((left, right) => right.version - left.version);
    if (ordered.length === 0) {
        versionListNode.append(textNode("li", "保存第一版草稿后，这里会保留版本历史。", "version-empty"));
        inspectedVersionLabel.textContent = "选择一个版本查看";
        inspectedVersionBody.textContent = "保存草稿后，版本内容会显示在这里。";
        copyVersionButton.disabled = true;
        return;
    }
    for (const version of ordered) {
        const item = document.createElement("li");
        const button = textNode("button", "", "version-button");
        button.type = "button";
        button.setAttribute("aria-pressed", version.version === inspectedVersionNumber ? "true" : "false");
        const review = latestReview(version.version);
        const currentMark = version.version === activeTask.current_version ? " · 当前" : "";
        button.append(textNode("strong", "版本 " + version.version + currentMark), textNode("small", statusLabel(review?.decision || "未审核") + " · " + formatDate(version.created_at)));
        button.addEventListener("click", () => {
            inspectedVersionNumber = version.version;
            renderTask();
        });
        item.append(button);
        versionListNode.append(item);
    }
    const selected = selectedVersion();
    if (!selected) {
        inspectedVersionLabel.textContent = "选择一个版本查看";
        inspectedVersionBody.textContent = "没有可显示的版本。";
        copyVersionButton.disabled = true;
        return;
    }
    const currentText = selected.version === activeTask.current_version ? " · 当前版本" : " · 历史版本";
    inspectedVersionLabel.textContent = "版本 " + selected.version + currentText + " · SHA-256 " + (selected.sha256 || "未提供");
    inspectedVersionBody.textContent = selected.body;
    copyVersionButton.disabled = false;
}
function renderReviews() {
    if (!activeTask)
        return;
    const version = selectedVersion();
    const review = version ? latestReview(version.version) : null;
    reviewStateNode.textContent = review ? statusLabel(review.decision) : "未审核";
    reviewStateNode.className = "review-state";
    if (review?.decision === "approved")
        reviewStateNode.classList.add("is-approved");
    else if (review?.decision === "rejected")
        reviewStateNode.classList.add("is-rejected");
    else if (!review && version)
        reviewStateNode.classList.add("is-unknown");
    if (!version) {
        reviewTargetNode.textContent = "先在版本历史中选择一个已保存版本。";
    }
    else if (draftIsDirty()) {
        reviewTargetNode.textContent = "当前查看版本：v" + version.version + "。编辑器有未保存修改；请先保存为新版本，再审核新版本。";
    }
    else if (version.version !== activeTask.current_version) {
        reviewTargetNode.textContent = "当前查看版本：v" + version.version + "。这是历史版本；请回到当前版本后再审核或准备发布。";
    }
    else if (!editorMatchesSelectedVersion()) {
        reviewTargetNode.textContent = "当前查看版本：v" + version.version + "。编辑器尚未载入这版正文与来源；请先点击“载入到编辑器”再审核。";
    }
    else {
        reviewTargetNode.textContent = "审核目标：任务“" + activeTask.title + "”的版本 v" + version.version + "。审核只针对这个已保存版本。";
    }
    reviewHistoryNode.replaceChildren();
    const rows = activeTask.reviews.filter(item => !version || numberValue(item.version, -1) === version.version);
    for (const row of rows) {
        const card = textNode("article", "", "review-record");
        const heading = textNode("div", "", "record-heading");
        heading.append(textNode("strong", "版本 " + row.version + " · " + statusLabel(row.decision)));
        const state = textNode("span", statusLabel(row.decision), "review-state");
        if (row.decision === "approved")
            state.classList.add("is-approved");
        if (row.decision === "rejected")
            state.classList.add("is-rejected");
        heading.append(state);
        card.append(heading, textNode("p", row.note || "未填写审核备注"));
        const meta = [formatDate(row.created_at)];
        if (row.decision === "approved")
            meta.push(row.source_check_confirmed ? "已确认人工核对来源" : "未记录来源核对确认");
        card.append(textNode("p", meta.join(" · ")));
        reviewHistoryNode.append(card);
    }
}
function targetDetails(target) {
    const details = document.createElement("dl");
    details.className = "target-details";
    const rows = [
        ["账号", target.account_id || "未提供"],
        ["来源", target.origin || "未提供"],
        ["可见范围", target.visibility || "未提供"]
    ];
    for (const [label, value] of rows) {
        details.append(textNode("dt", label), textNode("dd", value));
    }
    return details;
}
function clearPublicationConfirmation() {
    confirmCheckbox.checked = false;
    confirmPhraseInput.value = "";
    retryCheckbox.checked = false;
    retryPhraseInput.value = "";
}
function renderPublications() {
    if (!activeTask)
        return;
    publicationListNode.replaceChildren();
    for (const publication of [...activeTask.publications].reverse()) {
        const card = textNode("article", "", "publication-record");
        const heading = textNode("div", "", "record-heading");
        heading.append(textNode("strong", "版本 " + publication.version + " · " + publication.publication_id));
        const badge = textNode("span", "", "status-badge");
        setStatusBadge(badge, publication.status);
        heading.append(badge);
        card.append(heading);
        card.append(textNode("p", "内容摘要 SHA-256：" + (publication.body_sha256 || "未提供")));
        card.append(textNode("p", "目标账号：" + (publication.target.account_id || "未提供") + " · " + (publication.target.visibility || "可见范围未知")));
        if (publication.created_at)
            card.append(textNode("p", formatDate(publication.created_at)));
        const open = makeButton("查看已保存预览与恢复操作", "button-small button-quiet", () => {
            activePublicationId = publication.publication_id;
            clearPublicationConfirmation();
            renderTask();
        });
        const actions = textNode("div", "", "publication-actions");
        actions.append(open);
        card.append(actions);
        publicationListNode.append(card);
    }
    const publication = publicationById(activePublicationId);
    if (publication)
        renderPublicationPreview(publication);
    else {
        publicationPreviewNode.hidden = true;
        lastRenderedPublicationId = null;
        lastRenderedPublicationStatus = null;
    }
}
function renderPublicationPreview(publication) {
    const statusChanged = lastRenderedPublicationId !== publication.publication_id || lastRenderedPublicationStatus !== publication.status;
    if (statusChanged)
        clearPublicationConfirmation();
    lastRenderedPublicationId = publication.publication_id;
    lastRenderedPublicationStatus = publication.status;
    publicationPreviewNode.hidden = false;
    previewStatusNode.textContent = statusLabel(publication.status);
    previewStatusNode.className = "review-state";
    if (publication.status === "published")
        previewStatusNode.classList.add("is-approved");
    else if (publication.status === "failed" || publication.status === "stale")
        previewStatusNode.classList.add("is-rejected");
    else if (publication.status === "unknown" || publication.status === "unverified" || uncertainPublicationIds.has(publication.publication_id))
        previewStatusNode.classList.add("is-unknown");
    previewTargetNode.replaceChildren(...Array.from(targetDetails(publication.target).children));
    previewHashNode.textContent = publication.body_sha256 || "未提供";
    previewBodyNode.textContent = publication.body;
    const selected = selectedVersion();
    const review = selected ? latestReview(selected.version) : null;
    const editorMatches = !draftIsDirty();
    const publicationMatches = Boolean(selected && selected.version === publication.version && editorMatches && editorMatchesSelectedVersion() && review?.decision === "approved");
    const canConfirm = publication.status === "prepared" && !uncertainPublicationIds.has(publication.publication_id) && publicationMatches;
    confirmCheckbox.closest("label").hidden = publication.status !== "prepared";
    confirmPhraseInput.previousElementSibling?.classList.toggle("visually-hidden", publication.status !== "prepared");
    confirmPhraseInput.hidden = publication.status !== "prepared";
    confirmPublicationButton.hidden = publication.status !== "prepared";
    confirmPublicationButton.disabled = !canConfirm || !confirmCheckbox.checked || confirmPhraseInput.value !== "确认发布";
    const recoverable = publication.status === "unknown" || publication.status === "unverified" || publication.status === "dispatching" || uncertainPublicationIds.has(publication.publication_id);
    publicationRecoveryNode.hidden = !recoverable;
    recoveryNoteNode.textContent = uncertainPublicationIds.has(publication.publication_id)
        ? "提交结果尚未确认。请先同步回执；页面不会自动再次提交。"
        : "请同步目标平台回执确认当前状态。同步只读取回执，不会再次发布。";
    const canRetry = publication.status === "unknown" && !uncertainPublicationIds.has(publication.publication_id);
    retryConfirmationNode.hidden = !canRetry;
    reconcilePublicationButton.disabled = !recoverable;
    retryPublicationButton.disabled = !canRetry || !retryCheckbox.checked || retryPhraseInput.value !== "重试原发布";
}
function renderGenerations() {
    if (!activeTask)
        return;
    generationListNode.replaceChildren();
    for (const generation of [...activeTask.generations].reverse()) {
        const card = textNode("article", "", "generation-record");
        const heading = textNode("div", "", "record-heading");
        heading.append(textNode("strong", "生成任务 " + generation.generation_id));
        const badge = textNode("span", "", "status-badge");
        setStatusBadge(badge, generation.status);
        heading.append(badge);
        card.append(heading);
        const info = [];
        if (generation.session_id)
            info.push("会话 " + generation.session_id);
        if (generation.run_id)
            info.push("运行 " + generation.run_id);
        if (generation.version !== undefined)
            info.push("生成版本 v" + generation.version);
        if (generation.error_code)
            info.push("错误码 " + generation.error_code);
        if (generation.created_at)
            info.push(formatDate(generation.created_at));
        if (info.length)
            card.append(textNode("p", info.join(" · ")));
        const events = generationEventRows.get(generation.generation_id) || [];
        if (events.length) {
            const eventList = document.createElement("ol");
            eventList.className = "generation-events";
            for (const event of events.slice(-8)) {
                const row = record(event);
                const summary = stringValue(row.message, stringValue(row.type, stringValue(row.status, JSON.stringify(event))));
                eventList.append(textNode("li", summary.slice(0, 500)));
            }
            card.append(eventList);
        }
        const actions = textNode("div", "", "generation-actions");
        actions.append(makeButton("手动同步状态", "button-small button-quiet", () => {
            void syncGeneration(generation.generation_id);
        }));
        card.append(actions);
        generationListNode.append(card);
    }
}
function renderTask() {
    if (!activeTask) {
        workspaceNode.hidden = true;
        emptyStateNode.hidden = tasks.length !== 0 || !bearerToken;
        renderTaskList();
        renderControls();
        return;
    }
    workspaceNode.hidden = false;
    emptyStateNode.hidden = true;
    taskNameNode.textContent = activeTask.title;
    taskBriefNode.textContent = activeTask.brief;
    taskStatsNode.textContent = activeTask.sources.length + " 个来源 · " + activeTask.versions.length + " 个版本";
    currentVersionNode.textContent = activeTask.current_version === null ? "暂无草稿" : "当前版本 v" + activeTask.current_version;
    renderTaskList();
    renderSourceOptions();
    renderSources();
    renderVersions();
    renderReviews();
    renderGenerations();
    renderPublications();
    renderControls();
}
function renderControls() {
    const hasTask = Boolean(activeTask);
    const version = selectedVersion();
    const dirty = draftIsDirty();
    const editorIsCurrentBase = Boolean(activeTask && baselineVersionNumber === activeTask.current_version);
    const editorAligned = editorMatchesSelectedVersion();
    const review = version ? latestReview(version.version) : null;
    const currentPublication = publicationById(activePublicationId);
    const hasBody = draftBodyInput.value.trim().length > 0 && draftBodyInput.value.length <= 12000;
    const sourcesSelected = selectedSourceIds.size > 0 && selectedSourceIds.size <= 12;
    const hasUnresolvedPublication = Boolean(activeTask?.publications.some(item => ["dispatching", "unknown", "unverified"].includes(item.status)));
    const versionIsCurrent = Boolean(activeTask && version && version.version === activeTask.current_version);
    saveVersionButton.disabled = !hasTask || !hasBody || !dirty || !sourcesSelected || !editorIsCurrentBase || hasUnresolvedPublication;
    draftDirtyNode.textContent = dirty ? "有未保存修改" : editorIsCurrentBase ? "已保存版本" : "需载入当前版本";
    draftDirtyNode.classList.toggle("is-dirty", dirty || !editorIsCurrentBase);
    const generationCount = activeTask?.sources.length || 0;
    const generationActive = Boolean(activeTask?.generations.some(item => ["starting", "queued", "running"].includes(item.status)));
    startGenerationButton.disabled = !hasTask || generationCount < 1 || generationCount > 8 || generationActive || hasUnresolvedPublication;
    approveButton.disabled = !versionIsCurrent || dirty || !editorAligned || !sourceCheckInput.checked || hasUnresolvedPublication;
    rejectButton.disabled = !versionIsCurrent || dirty || !editorAligned || hasUnresolvedPublication;
    previewPublicationButton.disabled = !versionIsCurrent || dirty || !editorAligned || review?.decision !== "approved" || hasUnresolvedPublication;
    searchSubmitButton.disabled = !bearerToken || searchQueryInput.value.trim().length === 0;
    if (currentPublication)
        renderPublicationPreview(currentPublication);
    const canConfirm = Boolean(currentPublication && currentPublication.status === "prepared" &&
        !uncertainPublicationIds.has(currentPublication.publication_id) &&
        version && version.version === currentPublication.version && !dirty && editorAligned &&
        review?.decision === "approved" && confirmCheckbox.checked && confirmPhraseInput.value === "确认发布");
    confirmPublicationButton.disabled = !canConfirm;
    const canRetry = Boolean(currentPublication && currentPublication.status === "unknown" && !uncertainPublicationIds.has(currentPublication.publication_id));
    retryPublicationButton.disabled = !canRetry || !retryCheckbox.checked || retryPhraseInput.value !== "重试原发布";
}
async function loadTaskList() {
    const payload = await request("/api/tasks");
    tasks = taskRowsFromPayload(payload).filter(task => task.task_id.length > 0);
    renderTaskList();
    setApiStatus("已连接");
}
async function refreshTask(resetDraft = false) {
    if (!activeTaskId)
        return;
    const requestedTaskId = activeTaskId;
    const payload = await request("/api/tasks/" + encodeURIComponent(requestedTaskId));
    if (requestedTaskId !== activeTaskId)
        return;
    activeTask = normalizeTaskDetail(payload);
    if (inspectedVersionNumber === null || !activeTask.versions.some(version => version.version === inspectedVersionNumber)) {
        inspectedVersionNumber = activeTask.current_version;
    }
    if (!activePublicationId || !activeTask.publications.some(item => item.publication_id === activePublicationId)) {
        const latest = activeTask.publications[activeTask.publications.length - 1];
        activePublicationId = latest?.publication_id || null;
    }
    if (resetDraft)
        initializeDraftFromTask();
    renderTask();
    setApiStatus("已连接");
}
async function selectTask(taskId) {
    clearMessages();
    activeTaskId = taskId;
    activeTask = null;
    inspectedVersionNumber = null;
    activePublicationId = null;
    selectedSourceIds = new Set();
    baselineBody = "";
    baselineSourceIds = new Set();
    baselineVersionNumber = null;
    workspaceNode.hidden = true;
    emptyStateNode.hidden = true;
    generationEventRows.clear();
    generationEventCursors.clear();
    uncertainPublicationIds.clear();
    clearPublicationConfirmation();
    renderTaskList();
    try {
        await refreshTask(true);
    }
    catch (error) {
        showRequestError(error);
        activeTaskId = null;
        renderTask();
    }
}
async function runButton(button, action) {
    if (button.disabled)
        return;
    button.disabled = true;
    clearMessages();
    try {
        await action();
    }
    catch (error) {
        showRequestError(error);
    }
    finally {
        renderControls();
    }
}
async function createTask(event) {
    event.preventDefault();
    if (!bearerToken) {
        showAlert("请先载入 Bearer 令牌。");
        return;
    }
    const title = taskTitleInput.value.trim();
    const brief = taskBriefInput.value.trim();
    if (!title || !brief || brief.length > 6000)
        return;
    const submit = taskForm.querySelector("button[type=submit]");
    if (submit)
        submit.disabled = true;
    clearMessages();
    try {
        const created = record(await request("/api/tasks", { method: "POST", body: { title, brief } }));
        const summary = normalizeTaskSummary(created);
        if (!summary.task_id)
            throw new Error("服务端没有返回任务编号。");
        taskForm.reset();
        taskForm.closest("details")?.removeAttribute("open");
        await loadTaskList();
        if (!tasks.some(item => item.task_id === summary.task_id))
            tasks.unshift(summary);
        await selectTask(summary.task_id);
        showNotice("任务已创建。");
    }
    catch (error) {
        showRequestError(error);
    }
    finally {
        if (submit)
            submit.disabled = false;
    }
}
async function createSource(event) {
    event.preventDefault();
    if (!activeTask)
        return;
    const title = sourceTitleInput.value.trim();
    const url = sourceUrlInput.value.trim();
    const excerpt = sourceExcerptInput.value.trim();
    if (!title || !excerpt || excerpt.length > 4000)
        return;
    const submit = sourceForm.querySelector("button[type=submit]");
    if (!submit)
        return;
    await runButton(submit, async () => {
        await request(routeTask("/sources"), { method: "POST", body: { title, url, excerpt } });
        sourceForm.reset();
        await refreshTask();
        showNotice("来源摘录已记录，状态为未核验。");
    });
}
async function saveVersion() {
    if (!activeTask)
        return;
    const body = draftBodyInput.value;
    if (!body.trim() || body.length > 12000) {
        showAlert("草稿正文需填写，且不能超过 12,000 个字符。");
        return;
    }
    if (selectedSourceIds.size === 0 || selectedSourceIds.size > 12) {
        showAlert("保存版本前，请勾选 1–12 个实际使用的来源。");
        return;
    }
    if (activeTask.current_version !== baselineVersionNumber) {
        showAlert("当前版本已更新；请先从版本历史载入当前版本，再继续编辑。");
        return;
    }
    if (activeTask.publications.some(item => ["dispatching", "unknown", "unverified"].includes(item.status))) {
        showAlert("请先同步并处理待确认的发布回执，再修改草稿。");
        return;
    }
    await runButton(saveVersionButton, async () => {
        const saved = record(await request(routeTask("/versions"), {
            method: "POST",
            body: {
                base_version: activeTask.current_version,
                body,
                source_ids: Array.from(selectedSourceIds)
            }
        }));
        const version = numberValue(saved.version, -1);
        await refreshTask();
        const persisted = activeTask?.versions.find(item => item.version === version);
        if (persisted) {
            inspectedVersionNumber = persisted.version;
            setDraftBaseline(persisted.body, persisted.source_ids, persisted.version);
        }
        renderTask();
        showNotice("草稿已保存为新版本；该版本需要单独审核。");
    });
}
async function createReview(decision) {
    const version = selectedVersion();
    if (!activeTask || !version || version.version !== activeTask.current_version || draftIsDirty() || !editorMatchesSelectedVersion())
        return;
    if (activeTask.publications.some(item => ["dispatching", "unknown", "unverified"].includes(item.status))) {
        showAlert("请先同步并处理待确认的发布回执，再进行审核。");
        return;
    }
    if (decision === "approved" && !sourceCheckInput.checked) {
        showAlert("通过前请确认已人工核对来源与引用内容。");
        return;
    }
    const button = decision === "approved" ? approveButton : rejectButton;
    await runButton(button, async () => {
        await request(routeTask("/reviews"), {
            method: "POST",
            body: {
                version: version.version,
                decision,
                note: reviewNoteInput.value.trim(),
                ...(decision === "approved" ? { source_check_confirmed: true } : {})
            }
        });
        reviewNoteInput.value = "";
        if (decision === "rejected")
            sourceCheckInput.checked = false;
        await refreshTask();
        showNotice(decision === "approved" ? "审核通过记录已保存。" : "拒绝记录已保存。编辑并保存新版本后需要重新审核。改写时仍以人工核对来源为准。");
    });
}
async function startGeneration() {
    if (!activeTask)
        return;
    if (activeTask.sources.length < 1 || activeTask.sources.length > 8) {
        showAlert("生成需要准备 1–8 个来源摘录。可以直接从来源开始，也可以基于当前草稿继续生成。");
        return;
    }
    await runButton(startGenerationButton, async () => {
        const generation = record(await request(routeTask("/generations"), {
            method: "POST",
            body: { base_version: activeTask.current_version },
            idempotencyKey: newIdempotencyKey()
        }));
        await refreshTask();
        showNotice("生成任务已提交。点击对应的“手动同步状态”查看进度。");
        const id = stringValue(generation.generation_id);
        if (!id)
            showNotice("生成任务已提交；同步任务列表查看状态。");
    });
}
function eventsFromPayload(payload) {
    if (Array.isArray(payload))
        return { events: payload, cursor: null };
    const item = record(payload);
    const events = arrayValue(item.events || item.items);
    const next = item.next_after ?? item.cursor ?? item.last_seq;
    return { events, cursor: next == null ? null : numberValue(next, 0) };
}
function eventSequence(event) {
    const item = record(event);
    return numberValue(item.seq ?? item.sequence ?? item.event_id ?? item.id, 0);
}
async function syncGeneration(generationId) {
    if (!activeTask)
        return;
    const base = routeTask("/generations/" + encodeURIComponent(generationId));
    const button = generationListNode.querySelector("button:focus");
    if (button)
        button.disabled = true;
    clearMessages();
    try {
        await request(base + "/sync", { method: "POST", body: {} });
        const after = generationEventCursors.get(generationId) || 0;
        const payload = await request(base + "/events?after=" + encodeURIComponent(String(after)));
        const parsed = eventsFromPayload(payload);
        const prior = generationEventRows.get(generationId) || [];
        generationEventRows.set(generationId, prior.concat(parsed.events));
        const observed = parsed.events.reduce((max, item) => Math.max(max, eventSequence(item)), after);
        generationEventCursors.set(generationId, parsed.cursor === null ? observed : Math.max(observed, parsed.cursor));
        await refreshTask();
        showNotice("生成状态已同步。页面不会自动轮询。");
    }
    catch (error) {
        showRequestError(error);
    }
    finally {
        renderControls();
    }
}
function renderSearchResults(items) {
    searchResultsNode.replaceChildren();
    if (items.length === 0) {
        searchResultsNode.append(textNode("p", "没有匹配结果。", "search-empty"));
        return;
    }
    for (const raw of items) {
        const item = record(raw);
        const card = textNode("article", "", "search-result");
        if (typeof raw === "string") {
            card.append(textNode("p", raw));
        }
        else {
            const title = stringValue(item.title, stringValue(item.task_title, stringValue(item.source_title, stringValue(item.name, "检索结果"))));
            card.append(textNode("strong", title));
            const snippet = stringValue(item.snippet, stringValue(item.excerpt, stringValue(item.body, stringValue(item.content))));
            if (snippet)
                card.append(textNode("p", snippet.slice(0, 600)));
            const meta = [];
            if (item.version !== undefined)
                meta.push("版本 " + stringValue(item.version));
            if (item.source_id)
                meta.push("来源 " + stringValue(item.source_id));
            if (item.sha256)
                meta.push("SHA-256 " + stringValue(item.sha256));
            if (meta.length)
                card.append(textNode("small", meta.join(" · ")));
            const taskId = stringValue(item.task_id);
            if (taskId && tasks.some(task => task.task_id === taskId)) {
                card.append(makeButton("打开任务", "button-small button-quiet", () => {
                    void selectTask(taskId);
                }));
            }
        }
        searchResultsNode.append(card);
    }
}
async function searchWorkspace(event) {
    event.preventDefault();
    const query = searchQueryInput.value.trim();
    if (!bearerToken || !query)
        return;
    searchSubmitButton.disabled = true;
    clearMessages();
    try {
        const payload = record(await request("/api/search?q=" + encodeURIComponent(query)));
        renderSearchResults(arrayValue(payload.items));
        showNotice("检索完成。");
    }
    catch (error) {
        showRequestError(error);
    }
    finally {
        renderControls();
    }
}
async function preparePublication() {
    const version = selectedVersion();
    if (!activeTask || !version || version.version !== activeTask.current_version || draftIsDirty() || !editorMatchesSelectedVersion())
        return;
    const review = latestReview(version.version);
    if (review?.decision !== "approved") {
        showAlert("只有通过人工审核的已保存版本才能准备发布预览。");
        return;
    }
    await runButton(previewPublicationButton, async () => {
        const publication = normalizePublication(await request(routeTask("/publications/preview"), {
            method: "POST",
            body: { version: version.version },
            idempotencyKey: newIdempotencyKey()
        }));
        activePublicationId = publication.publication_id;
        clearPublicationConfirmation();
        await refreshTask();
        if (!activeTask?.publications.some(item => item.publication_id === publication.publication_id)) {
            activeTask?.publications.push(publication);
            renderTask();
        }
        showNotice("发布预览已保存。请核对目标账号、完整正文和 SHA-256，再单独确认发布。");
    });
}
function normalizePublication(value) {
    const item = record(value);
    const target = record(item.target);
    return {
        ...item,
        publication_id: stringValue(item.publication_id),
        version: numberValue(item.version),
        body: stringValue(item.body),
        body_sha256: stringValue(item.body_sha256),
        target: {
            ...target,
            account_id: stringValue(target.account_id),
            origin: stringValue(target.origin),
            visibility: stringValue(target.visibility)
        },
        status: stringValue(item.status)
    };
}
async function confirmPublication() {
    const publication = publicationById(activePublicationId);
    if (!publication || publication.status !== "prepared")
        return;
    if (!confirmCheckbox.checked || confirmPhraseInput.value !== "确认发布")
        return;
    const button = confirmPublicationButton;
    await runButton(button, async () => {
        try {
            await request(routeTask("/publications/" + encodeURIComponent(publication.publication_id) + "/confirm"), {
                method: "POST",
                body: {
                    confirm: true,
                    body_sha256: publication.body_sha256,
                    target_account_id: publication.target.account_id
                }
            });
            uncertainPublicationIds.delete(publication.publication_id);
            await refreshTask();
            showNotice("发布确认请求已完成；页面已载入服务端保存的状态。");
        }
        catch (error) {
            if (!(error instanceof ApiFailure) || error.status >= 500) {
                uncertainPublicationIds.add(publication.publication_id);
                renderTask();
            }
            throw error;
        }
    });
}
async function reconcilePublication() {
    const publication = publicationById(activePublicationId);
    if (!publication)
        return;
    await runButton(reconcilePublicationButton, async () => {
        const updated = normalizePublication(await request(routeTask("/publications/" + encodeURIComponent(publication.publication_id) + "/reconcile"), {
            method: "POST",
            body: {}
        }));
        uncertainPublicationIds.delete(publication.publication_id);
        if (activeTask) {
            const index = activeTask.publications.findIndex(item => item.publication_id === updated.publication_id);
            if (index >= 0)
                activeTask.publications[index] = updated;
        }
        await refreshTask();
        showNotice("已同步目标平台回执；这一步只读取回执，不会重新发布。");
    });
}
async function retryPublication() {
    const publication = publicationById(activePublicationId);
    if (!publication || publication.status !== "unknown")
        return;
    if (!retryCheckbox.checked || retryPhraseInput.value !== "重试原发布")
        return;
    await runButton(retryPublicationButton, async () => {
        try {
            const updated = normalizePublication(await request(routeTask("/publications/" + encodeURIComponent(publication.publication_id) + "/retry"), {
                method: "POST",
                body: {
                    confirm: true,
                    body_sha256: publication.body_sha256,
                    target_account_id: publication.target.account_id
                }
            }));
            uncertainPublicationIds.delete(publication.publication_id);
            if (activeTask) {
                const index = activeTask.publications.findIndex(item => item.publication_id === updated.publication_id);
                if (index >= 0)
                    activeTask.publications[index] = updated;
            }
            await refreshTask();
            showNotice("原发布请求已显式重试，页面已载入服务端回执。");
        }
        catch (error) {
            if (!(error instanceof ApiFailure) || error.status >= 500) {
                uncertainPublicationIds.add(publication.publication_id);
                renderTask();
            }
            throw error;
        }
    });
}
function loadToken() {
    const token = tokenInput.value.trim();
    tokenInput.value = "";
    if (!token) {
        showAlert("请在输入框中填写 Bearer 令牌后载入。");
        return;
    }
    bearerToken = token;
    tokenStatusNode.textContent = "已载入至页面内存";
    tokenStatusNode.classList.add("is-loaded");
    clearMessages();
    setApiStatus("正在连接");
    searchResultsNode.replaceChildren();
    void loadTaskList().then(async () => {
        if (tasks.length > 0)
            await selectTask(tasks[0].task_id);
        else {
            activeTaskId = null;
            activeTask = null;
            emptyStateNode.hidden = false;
            workspaceNode.hidden = true;
            renderTaskList();
            renderControls();
        }
    }).catch(showRequestError);
}
function clearToken() {
    bearerToken = null;
    tokenInput.value = "";
    tokenStatusNode.textContent = "未载入";
    tokenStatusNode.classList.remove("is-loaded");
    tasks = [];
    activeTaskId = null;
    activeTask = null;
    inspectedVersionNumber = null;
    activePublicationId = null;
    generationEventRows.clear();
    generationEventCursors.clear();
    uncertainPublicationIds.clear();
    searchResultsNode.replaceChildren();
    draftBodyInput.value = "";
    selectedSourceIds.clear();
    baselineBody = "";
    baselineSourceIds.clear();
    baselineVersionNumber = null;
    emptyStateNode.hidden = true;
    workspaceNode.hidden = true;
    renderTaskList();
    renderControls();
    setApiStatus("等待载入令牌");
    showNotice("令牌已清除。它只在当前页面内存中使用，不会写入浏览器存储。");
}
byId("token-load").addEventListener("click", loadToken);
byId("token-clear").addEventListener("click", clearToken);
taskForm.addEventListener("submit", event => void createTask(event));
sourceForm.addEventListener("submit", event => void createSource(event));
byId("empty-create").addEventListener("click", () => {
    const details = taskForm.closest("details");
    if (details)
        details.open = true;
    taskTitleInput.focus();
});
saveVersionButton.addEventListener("click", () => void saveVersion());
draftBodyInput.addEventListener("input", renderControls);
copyVersionButton.addEventListener("click", () => {
    const version = selectedVersion();
    if (!version)
        return;
    setDraftBaseline(version.body, version.source_ids, version.version);
    renderTask();
    draftBodyInput.focus();
    showNotice("版本内容已载入编辑器；修改并保存后会创建新版本。");
});
startGenerationButton.addEventListener("click", () => void startGeneration());
approveButton.addEventListener("click", () => void createReview("approved"));
rejectButton.addEventListener("click", () => void createReview("rejected"));
sourceCheckInput.addEventListener("change", renderControls);
previewPublicationButton.addEventListener("click", () => void preparePublication());
confirmCheckbox.addEventListener("change", renderControls);
confirmPhraseInput.addEventListener("input", renderControls);
confirmPublicationButton.addEventListener("click", () => void confirmPublication());
reconcilePublicationButton.addEventListener("click", () => void reconcilePublication());
retryCheckbox.addEventListener("change", renderControls);
retryPhraseInput.addEventListener("input", renderControls);
retryPublicationButton.addEventListener("click", () => void retryPublication());
searchForm.addEventListener("submit", event => void searchWorkspace(event));
searchQueryInput.addEventListener("input", renderControls);
renderTaskList();
renderControls();
emptyStateNode.hidden = true;
workspaceNode.hidden = true;
setApiStatus("等待载入令牌");
