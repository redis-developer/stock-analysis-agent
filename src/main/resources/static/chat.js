(function () {
    const sessionStorageKey = "stock-analysis-chat:session-id";
    const sessionLabelsStorageKey = "stock-analysis-chat:session-labels";
    const maxRetrievedMemoriesLimit = 20;
    const defaultRateLimitLimit = 6;

    const state = {
        loading: false,
        sessionsRefreshing: false,
        memoriesLoading: false,
        cacheLoading: false,
        messages: [],
        messagesBySession: {},
        pendingSessions: {},
        pendingProgressBySession: {},
        sessions: [],
        memories: [],
        caches: [],
        memoriesOpen: false,
        cacheOpen: false,
        sessionId: null,
        userId: null,
        defaultRetrievedMemoriesLimit: 10,
        retrievedMemoriesLimit: 10,
        apiCachingEnabled: true,
        semanticCachingEnabled: true,
        rateLimitingEnabled: true,
        rateLimitLimit: defaultRateLimitLimit,
        rateLimitRemaining: defaultRateLimitLimit,
        providerUsage: {
            secApiHits: 0,
            tavilyApiHits: 0,
            twelveDataApiHits: 0
        },
        sessionManagementEnabled: true
    };

    const elements = {
        loginScreen: document.getElementById("login-screen"),
        loginForm: document.getElementById("login-form"),
        usernameInput: document.getElementById("username-input"),
        loginError: document.getElementById("login-error"),
        chatApp: document.getElementById("chat-app"),
        questionInput: document.getElementById("question-input"),
        apiCacheToggle: document.getElementById("api-cache-toggle"),
        semanticCacheToggle: document.getElementById("semantic-cache-toggle"),
        rateLimitToggle: document.getElementById("rate-limit-toggle"),
        retrievedMemoriesLimitInput: document.getElementById("retrieved-memories-limit-input"),
        messages: document.getElementById("messages"),
        composer: document.getElementById("composer"),
        sendButton: document.getElementById("send-button"),
        cacheButton: document.getElementById("cache-button"),
        cacheSidebar: document.getElementById("cache-sidebar"),
        closeCacheButton: document.getElementById("close-cache-button"),
        refreshCacheButton: document.getElementById("refresh-cache-button"),
        cacheList: document.getElementById("cache-list"),
        cacheEmpty: document.getElementById("cache-empty"),
        memoriesButton: document.getElementById("memories-button"),
        memorySidebar: document.getElementById("memory-sidebar"),
        memoryCreateForm: document.getElementById("memory-create-form"),
        memoryTextInput: document.getElementById("memory-text-input"),
        memoryTypeInput: document.getElementById("memory-type-input"),
        memoryTopicsInput: document.getElementById("memory-topics-input"),
        memoryCreateError: document.getElementById("memory-create-error"),
        createMemoryButton: document.getElementById("create-memory-button"),
        closeMemoriesButton: document.getElementById("close-memories-button"),
        flushMemoriesButton: document.getElementById("flush-memories-button"),
        memoriesList: document.getElementById("memories-list"),
        memoriesEmpty: document.getElementById("memories-empty"),
        refreshSessionsButton: document.getElementById("refresh-sessions-button"),
        newSessionButton: document.getElementById("new-session-button"),
        sessionsList: document.getElementById("sessions-list"),
        sessionsEmpty: document.getElementById("sessions-empty"),
        accountMenuButton: document.getElementById("account-menu-button"),
        accountMenu: document.getElementById("account-menu"),
        accountUsername: document.getElementById("account-username"),
        logoutButton: document.getElementById("logout-button"),
        statusPill: document.getElementById("status-pill"),
        rateLimitStatus: document.querySelector(".rate-limit-status"),
        rateLimitStatusValue: document.getElementById("rate-limit-status-value"),
        secApiHitsValue: document.getElementById("sec-api-hits-value"),
        tavilyApiHitsValue: document.getElementById("tavily-api-hits-value"),
        twelveDataApiHitsValue: document.getElementById("twelve-data-api-hits-value"),
        secApiResetButton: document.getElementById("sec-api-reset-button"),
        tavilyApiResetButton: document.getElementById("tavily-api-reset-button"),
        twelveDataApiResetButton: document.getElementById("twelve-data-api-reset-button"),
        sessionIdValue: document.getElementById("session-id-value"),
        emptyStateTemplate: document.getElementById("empty-state-template")
    };

    initialize();

    async function initialize() {
        state.retrievedMemoriesLimit = state.defaultRetrievedMemoriesLimit;
        clearLegacyStoredSettings();

        elements.loginForm.addEventListener("submit", onLoginSubmit);
        elements.composer.addEventListener("submit", onSubmit);
        elements.refreshSessionsButton.addEventListener("click", onRefreshSessionsClick);
        elements.newSessionButton.addEventListener("click", startNewSession);
        elements.sessionsList.addEventListener("click", onSessionClick);
        elements.cacheButton.addEventListener("click", onCacheButtonClick);
        elements.closeCacheButton.addEventListener("click", closeCache);
        elements.refreshCacheButton.addEventListener("click", onRefreshCacheClick);
        elements.cacheList.addEventListener("click", onCacheClick);
        elements.memoriesButton.addEventListener("click", onMemoriesButtonClick);
        elements.memoryCreateForm.addEventListener("submit", onMemoryCreateSubmit);
        elements.closeMemoriesButton.addEventListener("click", closeMemories);
        elements.memoriesList.addEventListener("click", onMemoryClick);
        elements.flushMemoriesButton.addEventListener("click", flushMemories);
        elements.questionInput.addEventListener("input", autoResizeTextarea);
        elements.questionInput.addEventListener("keydown", onComposerKeydown);
        elements.messages.addEventListener("click", onSuggestionClick);
        elements.apiCacheToggle.addEventListener("click", onApiCacheToggleClick);
        elements.semanticCacheToggle.addEventListener("click", onSemanticCacheToggleClick);
        elements.rateLimitToggle.addEventListener("click", onRateLimitToggleClick);
        elements.secApiResetButton.addEventListener("click", onProviderUsageResetClick);
        elements.tavilyApiResetButton.addEventListener("click", onProviderUsageResetClick);
        elements.twelveDataApiResetButton.addEventListener("click", onProviderUsageResetClick);
        elements.retrievedMemoriesLimitInput.addEventListener("input", onRetrievedMemoriesLimitInput);
        elements.retrievedMemoriesLimitInput.addEventListener("blur", commitRetrievedMemoriesLimit);
        elements.accountMenuButton.addEventListener("click", onAccountMenuClick);
        elements.logoutButton.addEventListener("click", onLogout);
        document.addEventListener("click", onDocumentClick);
        document.addEventListener("keydown", onDocumentKeydown);

        setStatus("Session ready");
        await hydrateChatContext();
        if (!isLoggedIn()) {
            clearStoredSession();
            showLogin();
            return;
        }

        state.sessionId = hydrateSessionId();
        enterChat(false);
    }

    async function onLoginSubmit(event) {
        event.preventDefault();
        const username = normalizeUserId(elements.usernameInput.value);
        if (!username) {
            elements.loginError.textContent = "Enter a username.";
            elements.loginError.hidden = false;
            elements.usernameInput.focus();
            return;
        }

        try {
            const context = await login(username);
            applyChatContext(context, username);
            state.sessionId = createSessionId();
            persistSessionId(state.sessionId);
            elements.loginError.hidden = true;
            enterChat(true);
        } catch (error) {
            elements.loginError.textContent = "Login failed.";
            elements.loginError.hidden = false;
            elements.usernameInput.focus();
        }
    }

    function enterChat(focusComposer) {
        elements.loginScreen.hidden = true;
        elements.chatApp.hidden = false;
        closeAccountMenu();
        applySessionManagementState();
        renderIdentity();
        renderSessions();
        renderMessages();
        autoResizeTextarea();
        if (isSessionManagementEnabled()) {
            refreshSessions();
            loadSessionMessages(state.sessionId, false);
        }

        if (focusComposer) {
            elements.questionInput.focus();
        }
    }

    function showLogin() {
        elements.chatApp.hidden = true;
        elements.loginScreen.hidden = false;
        closeAccountMenu();
        closeCache();
        closeMemories();
        elements.usernameInput.value = "";
        elements.loginError.textContent = "Enter a username.";
        elements.loginError.hidden = true;
        elements.usernameInput.focus();
    }

    function isLoggedIn() {
        return Boolean(normalizeUserId(state.userId));
    }

    function onAccountMenuClick(event) {
        event.stopPropagation();
        const isOpen = !elements.accountMenu.hidden;
        setAccountMenuOpen(!isOpen);
    }

    function onDocumentClick(event) {
        if (elements.accountMenu.hidden || event.target.closest(".sidebar-account")) {
            return;
        }

        closeAccountMenu();
    }

    function onDocumentKeydown(event) {
        if (event.key === "Escape") {
            closeAccountMenu();
            closeCache();
            closeMemories();
        }
    }

    async function onLogout() {
        if (hasPendingSessions()) {
            setStatus("Wait for responses", "warning");
            closeAccountMenu();
            return;
        }

        try {
            await fetch(new URL("./api/chat/logout", window.location.href), {
                method: "POST"
            });
        } catch (error) {
            // The local UI still needs to return to the login screen.
        }

        state.userId = null;
        state.sessionId = null;
        state.messages = [];
        state.messagesBySession = {};
        state.pendingSessions = {};
        state.pendingProgressBySession = {};
        state.sessions = [];
        state.memories = [];
        state.caches = [];
        state.memoriesOpen = false;
        state.cacheOpen = false;
        state.apiCachingEnabled = true;
        state.semanticCachingEnabled = true;
        state.rateLimitingEnabled = true;
        resetRateLimitStatus();
        clearLegacyStoredSettings();
        clearStoredSession();
        clearMemoryCreateForm();
        showLogin();
    }

    function setAccountMenuOpen(isOpen) {
        elements.accountMenu.hidden = !isOpen;
        elements.accountMenuButton.setAttribute("aria-expanded", String(isOpen));
    }

    function closeAccountMenu() {
        setAccountMenuOpen(false);
    }

    function onComposerKeydown(event) {
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            elements.composer.requestSubmit();
        }
    }

    function onSuggestionClick(event) {
        const suggestion = event.target.closest(".suggestion");
        if (!suggestion) {
            return;
        }

        elements.questionInput.value = suggestion.dataset.question || "";
        autoResizeTextarea();
        elements.questionInput.focus();
        setStatus("Prompt loaded");
    }

    function onApiCacheToggleClick() {
        state.apiCachingEnabled = !isApiCachingEnabled();
        renderIdentity();
        persistChatSettings();
    }

    function onSemanticCacheToggleClick() {
        state.semanticCachingEnabled = !isSemanticCachingEnabled();
        renderIdentity();
        persistChatSettings();
    }

    function onRateLimitToggleClick() {
        state.rateLimitingEnabled = !isRateLimitingEnabled();
        renderIdentity();
        persistChatSettings();
    }

    async function onProviderUsageResetClick(event) {
        const button = event.currentTarget;
        const provider = button ? button.dataset.provider : "";
        if (!provider) {
            return;
        }

        button.disabled = true;
        try {
            const usage = await resetProviderUsage(provider);
            applyProviderUsage(usage);
            renderProviderUsage();
        } catch (error) {
            setStatus("Reset failed", "error");
        } finally {
            button.disabled = false;
        }
    }

    async function onSessionClick(event) {
        if (!isSessionManagementEnabled()) {
            return;
        }

        const deleteButton = event.target.closest(".session-list__delete");
        if (deleteButton) {
            event.preventDefault();
            event.stopPropagation();
            await deleteSession(deleteButton.dataset.sessionId);
            return;
        }

        const sessionItem = event.target.closest(".session-list__item");
        if (!sessionItem) {
            return;
        }

        const nextSessionId = sessionItem.dataset.sessionId;
        if (!nextSessionId || nextSessionId === state.sessionId) {
            return;
        }

        await selectSession(nextSessionId);
    }

    async function onRefreshSessionsClick() {
        if (!isSessionManagementEnabled() || state.sessionsRefreshing) {
            return;
        }

        await refreshSessions(true, true);
    }

    async function onCacheButtonClick() {
        if (state.cacheOpen) {
            closeCache();
            return;
        }

        await openCache();
    }

    async function onRefreshCacheClick() {
        if (state.cacheLoading) {
            return;
        }

        await refreshCache(true);
    }

    async function onCacheClick(event) {
        const deleteButton = event.target.closest(".cache-entry__delete");
        if (deleteButton) {
            event.preventDefault();
            event.stopPropagation();
            await deleteCacheEntry(deleteButton.dataset.cacheName, deleteButton.dataset.cacheKey);
            return;
        }

        const summary = event.target.closest(".cache-entry__summary");
        if (!summary) {
            return;
        }

        const details = summary.closest(".cache-entry__details");
        if (!details) {
            return;
        }

        event.preventDefault();
        details.open = !details.open;
    }

    async function onMemoriesButtonClick() {
        if (!isSessionManagementEnabled()) {
            return;
        }

        if (state.memoriesOpen) {
            closeMemories();
            return;
        }

        await openMemories();
    }

    async function onMemoryClick(event) {
        const deleteButton = event.target.closest(".memory-list__delete");
        if (!deleteButton) {
            return;
        }

        event.preventDefault();
        event.stopPropagation();
        await deleteMemory(deleteButton.dataset.memoryId);
    }

    async function onMemoryCreateSubmit(event) {
        event.preventDefault();

        if (state.memoriesLoading) {
            return;
        }

        clearMemoryCreateError();
        const text = elements.memoryTextInput.value.trim();
        if (!text) {
            showMemoryCreateError("Enter memory text.");
            elements.memoryTextInput.focus();
            return;
        }

        await createMemory({
            text: text,
            memoryType: elements.memoryTypeInput.value,
            topics: parseMemoryTopics(elements.memoryTopicsInput.value),
            sessionId: state.sessionId
        });
    }

    function clearMemoryCreateError() {
        elements.memoryCreateError.textContent = "";
        elements.memoryCreateError.hidden = true;
    }

    function showMemoryCreateError(message) {
        elements.memoryCreateError.textContent = message;
        elements.memoryCreateError.hidden = false;
    }

    function clearMemoryCreateForm() {
        elements.memoryTextInput.value = "";
        elements.memoryTypeInput.value = "semantic";
        elements.memoryTopicsInput.value = "";
        clearMemoryCreateError();
    }

    function startNewSession() {
        if (!isSessionManagementEnabled()) {
            return;
        }

        state.sessionId = createSessionId();
        setActiveSessionMessages([]);
        persistSessionId(state.sessionId);
        syncLoadingState();
        renderIdentity();
        renderSessions();
        renderMessages();
        setStatus("New session ready");
        elements.questionInput.focus();
    }

    async function selectSession(sessionId) {
        if (!isSessionManagementEnabled()) {
            return;
        }

        commitUserId();
        state.sessionId = sessionId;
        state.messages = sessionMessages(sessionId);
        persistSessionId(state.sessionId);
        syncLoadingState();
        renderIdentity();
        renderSessions();
        renderMessages();
        if (isSessionPending(sessionId)) {
            setStatus("Analyzing");
        } else {
            setStatus("Loading session");
            await loadSessionMessages(sessionId, true);
        }
        elements.questionInput.focus();
    }

    async function deleteSession(sessionId) {
        if (!isSessionManagementEnabled()) {
            return;
        }

        const targetSessionId = typeof sessionId === "string" ? sessionId.trim() : "";
        if (!targetSessionId) {
            return;
        }

        if (isSessionPending(targetSessionId)) {
            setStatus("Wait for response", "warning");
            return;
        }

        commitUserId();
        setStatus("Deleting session");

        try {
            await deleteSessionFromMemory(targetSessionId);
            removeSessionLabel(targetSessionId);
            delete state.messagesBySession[targetSessionId];
            state.sessions = state.sessions.filter(function (savedSessionId) {
                return savedSessionId !== targetSessionId;
            });

            if (targetSessionId === state.sessionId) {
                const nextSessionId = normalizeSessionList(state.sessions)
                    .find(function (savedSessionId) {
                        return savedSessionId !== targetSessionId;
                    });

                if (nextSessionId) {
                    state.sessionId = nextSessionId;
                    state.messages = sessionMessages(nextSessionId);
                    persistSessionId(state.sessionId);
                    syncLoadingState();
                    renderIdentity();
                    renderSessions();
                    renderMessages();
                    if (!isSessionPending(nextSessionId)) {
                        await loadSessionMessages(nextSessionId, false);
                    }
                } else {
                    state.sessionId = createSessionId();
                    setActiveSessionMessages([]);
                    persistSessionId(state.sessionId);
                    syncLoadingState();
                    renderIdentity();
                    renderSessions();
                    renderMessages();
                }
            } else {
                renderSessions();
            }

            setStatus("Session deleted");
            elements.questionInput.focus();
        } catch (error) {
            setStatus("Delete failed", "error");
        }
    }

    async function onSubmit(event) {
        event.preventDefault();
        const requestSessionId = state.sessionId || createSessionId();
        if (!state.sessionId) {
            state.sessionId = requestSessionId;
            persistSessionId(state.sessionId);
            renderIdentity();
            renderSessions();
        }
        if (isSessionPending(requestSessionId)) {
            return;
        }

        const question = elements.questionInput.value.trim();
        if (!question) {
            setStatus("Question required", "warning");
            elements.questionInput.focus();
            return;
        }

        if (!activeUserId()) {
            showLogin();
            return;
        }

        commitRetrievedMemoriesLimit();

        appendSessionMessage(requestSessionId, {
            role: "user",
            content: question,
            timestamp: new Date().toISOString()
        });

        elements.questionInput.value = "";
        autoResizeTextarea();
        setSessionLoading(requestSessionId, true);
        updateSessionProgress(requestSessionId, {
            id: "REQUEST_ANALYSIS",
            label: "Analyzing request",
            kind: "system",
            status: "running",
            summary: "Preparing the stock analysis request."
        });

        try {
            const response = await requestChatWithProgress(question, requestSessionId);
            const responseSessionId = normalizeSessionValue(response.sessionId) || requestSessionId;
            state.userId = response.userId || activeUserId();
            state.retrievedMemoriesLimit = normalizeRetrievedMemoriesLimit(
                response.retrievedMemoriesLimit ?? state.retrievedMemoriesLimit
            );
            state.apiCachingEnabled = response.apiCachingEnabled !== false;
            state.semanticCachingEnabled = response.semanticCachingEnabled !== false;
            state.rateLimitingEnabled = response.rateLimitingEnabled !== false;
            applyProviderUsage(response.providerUsage);
            renderProviderUsage();
            if (state.sessionId === requestSessionId) {
                state.sessionId = responseSessionId;
                persistSessionId(state.sessionId);
                syncLoadingState();
                renderIdentity();
            }

            appendSessionMessage(responseSessionId, {
                role: "assistant",
                content: response.response || "No response returned.",
                timestamp: new Date().toISOString(),
                memories: response.retrievedMemories || [],
                fromSemanticCache: Boolean(response.fromSemanticCache),
                fromSemanticGuardrail: Boolean(response.fromSemanticGuardrail),
                tokenUsage: normalizeTokenUsage(response.tokenUsage),
                executionSteps: Array.isArray(response.executionSteps) ? response.executionSteps : [],
                responseTimeMs: Number.isFinite(response.responseTimeMs) ? response.responseTimeMs : null
            });
            if (isSessionManagementEnabled()) {
                refreshSessions();
            }
            if (responseSessionId === state.sessionId || !state.loading) {
                setStatus("Response received");
            }
        } catch (error) {
            appendSessionMessage(requestSessionId, {
                role: "assistant",
                variant: "error",
                content: error.message,
                timestamp: new Date().toISOString()
            });
            await hydrateChatContext();
            renderIdentity();
            if (requestSessionId === state.sessionId || !state.loading) {
                setStatus("Request failed", "error");
            }
        } finally {
            setSessionLoading(requestSessionId, false);
            clearSessionProgress(requestSessionId);
            if (requestSessionId === state.sessionId) {
                elements.questionInput.focus();
            }
        }
    }

    async function requestChatWithProgress(message, sessionId) {
        if (!canReadStreamingResponse()) {
            return requestChat(message, sessionId);
        }

        try {
            return await requestChatStream(message, sessionId, function (step) {
                updateSessionProgress(sessionId, step);
            });
        } catch (error) {
            if (error && error.fallbackToChat) {
                clearSessionProgress(sessionId);
                return requestChat(message, sessionId);
            }
            throw error;
        }
    }

    async function requestChatStream(message, sessionId, onProgress) {
        let response;
        try {
            response = await fetch(new URL("./api/chat/stream", window.location.href), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    sessionId: sessionId,
                    message: message,
                    retrievedMemoriesLimit: activeRetrievedMemoriesLimit(),
                    apiCachingEnabled: isApiCachingEnabled(),
                    semanticCachingEnabled: isSemanticCachingEnabled(),
                    rateLimitingEnabled: isRateLimitingEnabled()
                })
            });
        } catch (error) {
            throw createStreamingFallbackError();
        }
        applyRateLimitHeaders(response);

        if (!response.body || typeof response.body.getReader !== "function") {
            throw createStreamingFallbackError();
        }

        if (!response.ok) {
            if (response.status === 404 || response.status === 405 || response.status === 406) {
                throw createStreamingFallbackError();
            }
            if (handleUnauthorizedResponse(response)) {
                throw new Error("Login is required.");
            }
            const rawBody = await response.text();
            const contentType = response.headers.get("content-type") || "";
            const body = contentType.includes("application/json") ? safeParseJson(rawBody) : null;
            throw new Error(extractErrorMessage(body, rawBody, response.status));
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
            const result = await reader.read();
            if (result.done) {
                break;
            }

            buffer += decoder.decode(result.value, { stream: true });
            const lines = buffer.split(/\r?\n/);
            buffer = lines.pop() || "";

            for (const line of lines) {
                const streamResponse = handleStreamLine(line, onProgress);
                if (streamResponse) {
                    return streamResponse;
                }
            }
        }

        buffer += decoder.decode();
        const streamResponse = handleStreamLine(buffer, onProgress);
        if (streamResponse) {
            return streamResponse;
        }

        throw createStreamingFallbackError();
    }

    function handleStreamLine(line, onProgress) {
        const trimmed = String(line || "").trim();
        if (!trimmed) {
            return null;
        }

        const event = safeParseJson(trimmed);
        if (!event || typeof event !== "object") {
            return null;
        }

        if (event.type === "progress" && event.step) {
            onProgress(event.step);
            return null;
        }

        if (event.type === "final") {
            return event.response || {};
        }

        if (event.type === "error") {
            throw new Error(typeof event.message === "string" && event.message.trim()
                ? event.message.trim()
                : "Request failed.");
        }

        return null;
    }

    function canReadStreamingResponse() {
        return typeof ReadableStream !== "undefined" && typeof TextDecoder !== "undefined";
    }

    function createStreamingFallbackError() {
        const error = new Error("Streaming unavailable.");
        error.fallbackToChat = true;
        return error;
    }

    async function requestChat(message, sessionId) {
        const response = await fetch(new URL("./api/chat", window.location.href), {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                sessionId: sessionId,
                message: message,
                retrievedMemoriesLimit: activeRetrievedMemoriesLimit(),
                apiCachingEnabled: isApiCachingEnabled(),
                semanticCachingEnabled: isSemanticCachingEnabled(),
                rateLimitingEnabled: isRateLimitingEnabled()
            })
        });
        applyRateLimitHeaders(response);

        const contentType = response.headers.get("content-type") || "";
        const rawBody = await response.text();
        const body = contentType.includes("application/json") ? safeParseJson(rawBody) : null;

        if (!response.ok) {
            if (handleUnauthorizedResponse(response)) {
                throw new Error("Login is required.");
            }
            throw new Error(extractErrorMessage(body, rawBody, response.status));
        }

        return body || {};
    }

    async function login(userId) {
        const response = await fetch(new URL("./api/chat/login", window.location.href), {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                userId: userId,
                retrievedMemoriesLimit: activeRetrievedMemoriesLimit(),
                apiCachingEnabled: isApiCachingEnabled(),
                semanticCachingEnabled: isSemanticCachingEnabled(),
                rateLimitingEnabled: isRateLimitingEnabled()
            })
        });

        const contentType = response.headers.get("content-type") || "";
        const rawBody = await response.text();
        const body = contentType.includes("application/json") ? safeParseJson(rawBody) : null;

        if (!response.ok) {
            throw new Error(extractErrorMessage(body, rawBody, response.status));
        }

        return body || {};
    }

    async function resetProviderUsage(provider) {
        const response = await fetch(new URL(
            "./api/chat/provider-usage/" + encodeURIComponent(provider) + "/reset",
            window.location.href
        ), {
            method: "POST"
        });

        const contentType = response.headers.get("content-type") || "";
        const rawBody = await response.text();
        const body = contentType.includes("application/json") ? safeParseJson(rawBody) : null;

        if (!response.ok) {
            if (handleUnauthorizedResponse(response)) {
                throw new Error("Login is required.");
            }
            throw new Error(extractErrorMessage(body, rawBody, response.status));
        }

        return body || {};
    }

    async function hydrateChatContext() {
        try {
            const response = await fetch(new URL("./api/chat/context", window.location.href));
            if (!response.ok) {
                throw new Error("Failed to load chat context.");
            }

            const body = safeParseJson(await response.text());
            applyChatContext(body);
        } catch (error) {
            if (!normalizeUserId(state.userId)) {
                state.userId = null;
            }
        }
    }

    async function refreshSessions(forceRefresh, reportStatus) {
        if (!isSessionManagementEnabled()) {
            state.sessions = [];
            renderSessions();
            return;
        }

        const requestedUserId = activeUserId();
        if (!requestedUserId) {
            return;
        }

        setSessionsRefreshing(true);
        if (reportStatus) {
            setStatus("Refreshing sessions");
        }

        try {
            const sessionsUrl = new URL("./api/chat/sessions", window.location.href);
            if (forceRefresh) {
                sessionsUrl.searchParams.set("forceRefresh", "true");
            }

            const response = await fetch(sessionsUrl);
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to load sessions.");
            }

            const body = safeParseJson(await response.text());
            if (requestedUserId !== activeUserId()) {
                return;
            }
            state.sessions = normalizeSessionList(body && body.sessions);
            if (reportStatus) {
                setStatus("Sessions refreshed");
            }
        } catch (error) {
            if (requestedUserId !== activeUserId()) {
                return;
            }
            state.sessions = normalizeSessionList(state.sessions);
            if (reportStatus) {
                setStatus("Session refresh failed", "error");
            }
        } finally {
            setSessionsRefreshing(false);
        }

        renderSessions();
    }

    async function openCache() {
        closeMemories();
        setCacheOpen(true);
        await refreshCache(true);
    }

    function closeCache() {
        setCacheOpen(false);
    }

    function setCacheOpen(isOpen) {
        state.cacheOpen = Boolean(isOpen);
        elements.cacheSidebar.hidden = !state.cacheOpen;
        elements.cacheButton.setAttribute("aria-expanded", String(state.cacheOpen));
        elements.chatApp.classList.toggle("chat-app--cache-open", state.cacheOpen);
        if (state.cacheOpen) {
            renderCache();
        }
    }

    async function refreshCache(reportStatus) {
        const requestedUserId = activeUserId();
        if (!requestedUserId) {
            return;
        }

        setCacheLoading(true);
        if (reportStatus) {
            setStatus("Loading cache");
        }

        try {
            const response = await fetch(new URL("./api/chat/cache", window.location.href));
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to load cache.");
            }

            const body = safeParseJson(await response.text());
            if (requestedUserId !== activeUserId()) {
                return;
            }
            state.caches = normalizeCaches(body && body.caches);
            if (reportStatus) {
                setStatus("Cache loaded");
            }
        } catch (error) {
            if (requestedUserId === activeUserId() && reportStatus) {
                setStatus("Cache load failed", "error");
            }
        } finally {
            setCacheLoading(false);
        }
    }

    async function deleteCacheEntry(cacheName, cacheKey) {
        if (state.cacheLoading) {
            return;
        }

        const targetCacheName = typeof cacheName === "string" ? cacheName.trim() : "";
        const targetCacheKey = typeof cacheKey === "string" ? cacheKey.trim() : "";
        if (!targetCacheName || !targetCacheKey) {
            return;
        }

        setCacheLoading(true);
        setStatus("Deleting cache entry");

        try {
            const deleteUrl = new URL("./api/chat/cache/" + encodeURIComponent(targetCacheName) + "/entries", window.location.href);
            deleteUrl.searchParams.set("key", targetCacheKey);
            const response = await fetch(deleteUrl, {
                method: "DELETE"
            });
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to delete cache entry.");
            }

            state.caches = removeCacheEntry(state.caches, targetCacheName, targetCacheKey);
            renderCache();
            setStatus("Cache entry deleted");
        } catch (error) {
            setStatus("Cache delete failed", "error");
        } finally {
            setCacheLoading(false);
        }
    }

    function removeCacheEntry(caches, cacheName, cacheKey) {
        return normalizeCaches(caches).map(function (cache) {
            if (cache.name !== cacheName) {
                return cache;
            }

            const entries = cache.entries.filter(function (entry) {
                return entry.key !== cacheKey;
            });
            return {
                name: cache.name,
                entryCount: Math.max(0, cache.entryCount - (cache.entries.length - entries.length)),
                truncated: cache.truncated,
                entries: entries
            };
        });
    }

    async function openMemories() {
        closeCache();
        setMemoriesOpen(true);
        await refreshMemories(true);
    }

    function closeMemories() {
        setMemoriesOpen(false);
    }

    function setMemoriesOpen(isOpen) {
        state.memoriesOpen = Boolean(isOpen);
        elements.memorySidebar.hidden = !state.memoriesOpen;
        elements.memoriesButton.setAttribute("aria-expanded", String(state.memoriesOpen));
        elements.chatApp.classList.toggle("chat-app--memories-open", state.memoriesOpen);
        if (state.memoriesOpen) {
            renderMemories();
        }
    }

    async function refreshMemories(reportStatus) {
        if (!isSessionManagementEnabled()) {
            state.memories = [];
            renderMemories();
            return;
        }

        const requestedUserId = activeUserId();
        if (!requestedUserId) {
            return;
        }

        setMemoriesLoading(true);
        if (reportStatus) {
            setStatus("Loading memories");
        }

        try {
            const response = await fetch(new URL("./api/chat/memories", window.location.href));
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to load memories.");
            }

            const body = safeParseJson(await response.text());
            if (requestedUserId !== activeUserId()) {
                return;
            }
            state.memories = normalizeMemories(body && body.memories);
            if (reportStatus) {
                setStatus("Memories loaded");
            }
        } catch (error) {
            if (requestedUserId === activeUserId() && reportStatus) {
                setStatus("Memory load failed", "error");
            }
        } finally {
            setMemoriesLoading(false);
        }
    }

    async function createMemory(memory) {
        if (!isSessionManagementEnabled()) {
            return;
        }

        const requestedUserId = activeUserId();
        if (!requestedUserId) {
            showLogin();
            return;
        }

        setMemoriesLoading(true);
        setStatus("Creating memory");

        try {
            const response = await fetch(new URL("./api/chat/memories", window.location.href), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(memory)
            });
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to create memory.");
            }

            clearMemoryCreateForm();
            await refreshMemories(false);
            setStatus("Memory created");
        } catch (error) {
            showMemoryCreateError("Create failed.");
            setStatus("Create failed", "error");
        } finally {
            setMemoriesLoading(false);
        }
    }

    async function deleteMemory(memoryId) {
        if (state.memoriesLoading) {
            return;
        }

        const targetMemoryId = typeof memoryId === "string" ? memoryId.trim() : "";
        if (!targetMemoryId) {
            return;
        }

        setMemoriesLoading(true);
        setStatus("Deleting memory");

        try {
            const response = await fetch(new URL("./api/chat/memories/" + encodeURIComponent(targetMemoryId), window.location.href), {
                method: "DELETE"
            });
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to delete memory.");
            }

            state.memories = state.memories.filter(function (memory) {
                return memory.id !== targetMemoryId;
            });
            renderMemories();
            setStatus("Memory deleted");
        } catch (error) {
            setStatus("Delete failed", "error");
        } finally {
            setMemoriesLoading(false);
        }
    }

    async function flushMemories() {
        if (state.memoriesLoading) {
            return;
        }

        if (state.memories.length === 0) {
            setStatus("No memories");
            return;
        }

        if (!window.confirm("Delete all long term memories for this user?")) {
            return;
        }

        setMemoriesLoading(true);
        setStatus("Flushing memories");

        try {
            const response = await fetch(new URL("./api/chat/memories", window.location.href), {
                method: "DELETE"
            });
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to flush memories.");
            }

            state.memories = [];
            renderMemories();
            setStatus("Memories flushed");
        } catch (error) {
            setStatus("Flush failed", "error");
        } finally {
            setMemoriesLoading(false);
        }
    }

    async function loadSessionMessages(sessionId, reportStatus) {
        if (!isSessionManagementEnabled()) {
            return;
        }

        if (!sessionId) {
            setActiveSessionMessages([]);
            renderMessages();
            return;
        }

        try {
            const sessionUrl = new URL("./api/chat/session/" + encodeURIComponent(sessionId), window.location.href);
            const requestedUserId = activeUserId();
            if (!requestedUserId) {
                return;
            }

            const response = await fetch(sessionUrl);
            if (!response.ok) {
                if (handleUnauthorizedResponse(response)) {
                    return;
                }
                throw new Error("Failed to load session.");
            }

            const body = safeParseJson(await response.text()) || {};
            if (requestedUserId !== activeUserId() || sessionId !== state.sessionId) {
                return;
            }
            state.userId = body.userId || activeUserId();
            state.sessionId = body.sessionId || sessionId;
            setActiveSessionMessages(normalizeSessionMessages(body.messages));
            persistSessionId(state.sessionId);
            syncLoadingState();
            renderIdentity();
            renderSessions();
            renderMessages();
            if (reportStatus) {
                setStatus("Session loaded");
            }
        } catch (error) {
            await refreshSessions(true, false);
            if (reportStatus) {
                setStatus("Session load failed", "error");
            }
            renderMessages();
        }
    }

    function appendMessage(message) {
        appendSessionMessage(state.sessionId, message);
    }

    function appendSessionMessage(sessionId, message) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            state.messages.push(message);
            renderMessages();
            return;
        }

        const messages = sessionMessages(targetSessionId).concat(message);
        state.messagesBySession[targetSessionId] = messages;
        if (targetSessionId === state.sessionId) {
            state.messages = messages;
            renderMessages();
        }
    }

    function setActiveSessionMessages(messages) {
        state.messages = Array.isArray(messages) ? messages : [];
        const targetSessionId = normalizeSessionValue(state.sessionId);
        if (targetSessionId) {
            state.messagesBySession[targetSessionId] = state.messages;
        }
    }

    function sessionMessages(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return [];
        }

        const messages = state.messagesBySession[targetSessionId];
        return Array.isArray(messages) ? messages : [];
    }

    function renderMessages() {
        elements.messages.replaceChildren();

        if (state.messages.length === 0) {
            elements.messages.appendChild(buildEmptyState());
            return;
        }

        for (const message of state.messages) {
            elements.messages.appendChild(buildMessage(message));
        }

        if (state.loading) {
            elements.messages.appendChild(buildTypingIndicator(sessionProgress(state.sessionId)));
        }

        scrollMessagesToBottom();
    }

    function renderIdentity() {
        elements.accountUsername.textContent = activeUserId() || "";
        if (isSessionManagementEnabled()) {
            renderSessionTimestamp(elements.sessionIdValue, state.sessionId);
        } else {
            elements.sessionIdValue.replaceChildren();
        }
        elements.retrievedMemoriesLimitInput.value = String(activeRetrievedMemoriesLimit());
        renderCacheToggle(
            elements.apiCacheToggle,
            isApiCachingEnabled(),
            "API cache"
        );
        renderCacheToggle(
            elements.semanticCacheToggle,
            isSemanticCachingEnabled(),
            "Semantic cache"
        );
        renderCacheToggle(
            elements.rateLimitToggle,
            isRateLimitingEnabled(),
            "Rate limit"
        );
        renderRateLimitStatus();
        renderProviderUsage();
    }

    function renderRateLimitStatus() {
        const enabled = isRateLimitingEnabled();
        const limit = normalizeRateLimitValue(state.rateLimitLimit, defaultRateLimitLimit);
        const remaining = Math.min(limit, normalizeRateLimitValue(state.rateLimitRemaining, limit));
        elements.rateLimitStatus.classList.toggle("is-off", !enabled);
        elements.rateLimitStatusValue.textContent = enabled ? remaining + " / " + limit : "Off";
    }

    function renderProviderUsage() {
        elements.secApiHitsValue.textContent = String(state.providerUsage.secApiHits);
        elements.tavilyApiHitsValue.textContent = String(state.providerUsage.tavilyApiHits);
        elements.twelveDataApiHitsValue.textContent = String(state.providerUsage.twelveDataApiHits);
    }

    function renderCacheToggle(button, enabled, label) {
        const stateLabel = enabled ? "On" : "Off";
        const text = button.querySelector(".identity-toggle__text");
        const state = button.querySelector(".identity-toggle__state");
        if (text) {
            text.textContent = label;
        }
        if (state) {
            state.textContent = stateLabel;
        }
        button.classList.toggle("is-off", !enabled);
        button.setAttribute("aria-pressed", String(!enabled));
        button.setAttribute("aria-label", label + " " + stateLabel);
        button.title = label + " " + stateLabel;
    }

    function renderSessions() {
        if (!isSessionManagementEnabled()) {
            state.sessions = [];
            elements.sessionsList.replaceChildren();
            elements.sessionsEmpty.hidden = true;
            return;
        }

        const sessions = normalizeSessionList(state.sessions);
        elements.sessionsList.replaceChildren();

        for (const sessionId of sessions) {
            elements.sessionsList.appendChild(buildSessionListItem(sessionId));
        }

        elements.sessionsEmpty.hidden = sessions.length > 0;
    }

    function renderMemories() {
        if (!isSessionManagementEnabled()) {
            state.memories = [];
            elements.memoriesList.replaceChildren();
            elements.memoriesEmpty.hidden = true;
            elements.memoryTextInput.disabled = true;
            elements.memoryTypeInput.disabled = true;
            elements.memoryTopicsInput.disabled = true;
            elements.createMemoryButton.disabled = true;
            return;
        }

        const memories = normalizeMemories(state.memories);
        elements.memoriesList.replaceChildren();

        for (const memory of memories) {
            elements.memoriesList.appendChild(buildMemoryListItem(memory));
        }

        elements.memoriesEmpty.hidden = memories.length > 0 || state.memoriesLoading;
        elements.flushMemoriesButton.disabled = state.memoriesLoading || memories.length === 0;
        elements.memoryTextInput.disabled = state.memoriesLoading;
        elements.memoryTypeInput.disabled = state.memoriesLoading;
        elements.memoryTopicsInput.disabled = state.memoriesLoading;
        elements.createMemoryButton.disabled = state.memoriesLoading;
    }

    function renderCache() {
        const caches = normalizeCaches(state.caches);
        elements.cacheList.replaceChildren();

        for (const cache of caches) {
            elements.cacheList.appendChild(buildCacheListItem(cache));
        }

        const hasEntries = caches.some(function (cache) {
            return cache.entries.length > 0;
        });
        elements.cacheEmpty.hidden = caches.length > 0 || hasEntries || state.cacheLoading;
        elements.refreshCacheButton.disabled = state.cacheLoading;
    }

    function buildSessionListItem(sessionId) {
        const pending = isSessionPending(sessionId);
        const item = document.createElement("div");
        item.className = "session-list__item";
        item.dataset.sessionId = sessionId;
        item.title = formatSessionLabel(sessionId);
        item.classList.toggle("is-active", sessionId === state.sessionId);
        item.setAttribute("role", "listitem");

        const selectButton = document.createElement("button");
        selectButton.className = "session-list__select";
        selectButton.type = "button";
        selectButton.setAttribute("aria-label", "Open " + formatSessionLabel(sessionId));

        const label = document.createElement("span");
        label.className = "session-list__label";
        renderSessionTimestamp(label, sessionId);

        const meta = document.createElement("span");
        meta.className = "session-list__meta";
        if (pending) {
            meta.textContent = "Working";
        } else {
            meta.textContent = sessionId === state.sessionId ? "Active" : "Saved";
        }

        const deleteButton = document.createElement("button");
        deleteButton.className = "session-list__delete";
        deleteButton.type = "button";
        deleteButton.dataset.sessionId = sessionId;
        deleteButton.textContent = "x";
        deleteButton.title = "Delete session";
        deleteButton.disabled = pending;
        deleteButton.setAttribute("aria-label", "Delete " + formatSessionLabel(sessionId));

        selectButton.append(label, meta);
        item.append(selectButton, deleteButton);
        return item;
    }

    function buildMemoryListItem(memory) {
        const item = document.createElement("div");
        item.className = "session-list__item memory-list__item";
        item.dataset.memoryId = memory.id;
        item.setAttribute("role", "listitem");

        const content = document.createElement("div");
        content.className = "memory-list__content";

        const text = document.createElement("p");
        text.className = "session-list__label memory-list__text";
        text.textContent = memory.text || "Untitled memory";
        content.appendChild(text);

        const metadata = document.createElement("dl");
        metadata.className = "memory-list__metadata";
        appendMemoryMetadata(metadata, "Type", memory.memoryType);
        appendMemoryMetadata(metadata, "Created", formatMemoryDate(memory.createdAt));
        appendMemoryMetadata(metadata, "Updated", formatMemoryDate(memory.updatedAt));
        appendMemoryMetadata(metadata, "User", memory.userId);
        appendMemoryMetadata(metadata, "Session", memory.sessionId);
        appendMemoryMetadata(metadata, "Namespace", memory.namespace);
        appendMemoryMetadata(metadata, "Topics", formatMemoryList(memory.topics));
        appendMemoryMetadata(metadata, "Entities", formatMemoryList(memory.entities));
        appendMemoryMetadata(metadata, "Id", memory.id);
        content.appendChild(metadata);

        const deleteButton = document.createElement("button");
        deleteButton.className = "session-list__delete memory-list__delete";
        deleteButton.type = "button";
        deleteButton.dataset.memoryId = memory.id;
        deleteButton.textContent = "x";
        deleteButton.title = "Delete memory";
        deleteButton.disabled = state.memoriesLoading;
        deleteButton.setAttribute("aria-label", "Delete memory");

        item.append(content, deleteButton);
        return item;
    }

    function buildCacheListItem(cache) {
        const item = document.createElement("div");
        item.className = "session-list__item memory-list__item cache-list__item";
        item.setAttribute("role", "listitem");

        const content = document.createElement("div");
        content.className = "memory-list__content cache-list__content";

        const heading = document.createElement("p");
        heading.className = "session-list__label memory-list__text";
        heading.textContent = formatCacheName(cache.name);
        content.appendChild(heading);

        const metadata = document.createElement("dl");
        metadata.className = "memory-list__metadata";
        appendMemoryMetadata(metadata, "Name", cache.name);
        appendMemoryMetadata(metadata, "Entries", String(cache.entryCount));
        if (cache.truncated) {
            appendMemoryMetadata(metadata, "Limit", "Showing first 50 entries");
        }
        content.appendChild(metadata);

        if (cache.entries.length === 0) {
            const empty = document.createElement("p");
            empty.className = "cache-list__empty";
            empty.textContent = "No entries";
            content.appendChild(empty);
        } else {
            const entries = document.createElement("div");
            entries.className = "cache-entry-list";
            for (const entry of cache.entries) {
                entries.appendChild(buildCacheEntry(cache.name, entry));
            }
            content.appendChild(entries);
        }

        item.appendChild(content);
        return item;
    }

    function buildCacheEntry(cacheName, entry) {
        const item = document.createElement("div");
        item.className = "cache-entry";
        item.dataset.cacheName = cacheName;
        item.dataset.cacheKey = entry.key;

        const details = document.createElement("details");
        details.className = "cache-entry__details";

        const summary = document.createElement("summary");
        summary.className = "cache-entry__summary";

        const summaryContent = document.createElement("span");
        summaryContent.className = "cache-entry__summary-content";

        const key = document.createElement("span");
        key.className = "cache-entry__key";
        key.textContent = entry.key || "Unknown key";

        const ttl = document.createElement("span");
        ttl.className = "cache-entry__ttl";
        ttl.textContent = formatCacheTtl(entry.ttlSeconds);

        summaryContent.append(key, ttl);
        summary.appendChild(summaryContent);
        details.appendChild(summary);

        const deleteButton = document.createElement("button");
        deleteButton.className = "session-list__delete cache-entry__delete";
        deleteButton.type = "button";
        deleteButton.dataset.cacheName = cacheName;
        deleteButton.dataset.cacheKey = entry.key;
        deleteButton.textContent = "x";
        deleteButton.title = "Delete cache entry";
        deleteButton.disabled = state.cacheLoading;
        deleteButton.setAttribute("aria-label", "Delete cache entry " + entry.key);

        const metadata = document.createElement("dl");
        metadata.className = "memory-list__metadata cache-entry__metadata";
        appendMemoryMetadata(metadata, "Type", entry.valueType);
        appendMemoryMetadata(metadata, "TTL", formatCacheTtl(entry.ttlSeconds));
        appendMemoryMetadata(metadata, "Size", formatBytes(entry.valueSizeBytes));
        if (entry.valueTruncated) {
            appendMemoryMetadata(metadata, "Value", "Preview truncated");
        }
        details.appendChild(metadata);

        const value = document.createElement("pre");
        value.className = "cache-entry__value";
        value.textContent = entry.value || "";
        details.appendChild(value);

        item.append(details, deleteButton);
        return item;
    }

    function appendMemoryMetadata(container, label, value) {
        const normalizedValue = typeof value === "string" ? value.trim() : "";
        if (!normalizedValue) {
            return;
        }

        const term = document.createElement("dt");
        term.textContent = label;
        const detail = document.createElement("dd");
        detail.textContent = normalizedValue;
        container.append(term, detail);
    }

    async function deleteSessionFromMemory(sessionId) {
        if (!isSessionManagementEnabled()) {
            return;
        }

        const clearUrl = new URL("./api/chat/session/" + encodeURIComponent(sessionId), window.location.href);

        const response = await fetch(clearUrl, {
            method: "DELETE"
        });
        if (!response.ok) {
            if (handleUnauthorizedResponse(response)) {
                return;
            }
            throw new Error("Failed to delete session.");
        }
    }

    function commitUserId() {
        state.userId = activeUserId();
        renderIdentity();
    }

    function clearLegacyStoredSettings() {
        try {
            window.localStorage.removeItem("stock-analysis-chat:user-id");
            window.localStorage.removeItem("stock-analysis-chat:retrieved-memories-limit");
        } catch (error) {
            // Ignore storage access failures.
        }
    }

    function clearStoredSession() {
        try {
            window.localStorage.removeItem(sessionStorageKey);
        } catch (error) {
            // Ignore storage access failures.
        }
    }

    function onRetrievedMemoriesLimitInput() {
        state.retrievedMemoriesLimit = normalizeRetrievedMemoriesLimit(elements.retrievedMemoriesLimitInput.value);
    }

    function commitRetrievedMemoriesLimit() {
        state.retrievedMemoriesLimit = normalizeRetrievedMemoriesLimit(elements.retrievedMemoriesLimitInput.value);
        persistChatSettings();
        renderIdentity();
    }

    function persistChatSettings() {
        if (!activeUserId()) {
            return;
        }

        if (!isSessionManagementEnabled()) {
            return;
        }

        updateChatSettings();
    }

    async function updateChatSettings() {
        try {
            const response = await fetch(new URL("./api/chat/settings", window.location.href), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    retrievedMemoriesLimit: activeRetrievedMemoriesLimit(),
                    apiCachingEnabled: isApiCachingEnabled(),
                    semanticCachingEnabled: isSemanticCachingEnabled(),
                    rateLimitingEnabled: isRateLimitingEnabled()
                })
            });

            if (handleUnauthorizedResponse(response)) {
                return;
            }

            if (!response.ok) {
                return;
            }

            const body = safeParseJson(await response.text());
            applyChatContext(body);
            renderIdentity();
        } catch (error) {
            // Settings will be sent with the next chat request too.
        }
    }

    function applyChatContext(body, fallbackUserId) {
        const context = body && typeof body === "object" ? body : {};
        const defaultLimit = Number.isFinite(context.defaultRetrievedMemoriesLimit)
            ? normalizeRetrievedMemoriesLimit(context.defaultRetrievedMemoriesLimit)
            : state.defaultRetrievedMemoriesLimit;

        state.defaultRetrievedMemoriesLimit = defaultLimit;
        state.userId = normalizeUserId(context.userId) || normalizeUserId(fallbackUserId);
        state.retrievedMemoriesLimit = Number.isFinite(context.retrievedMemoriesLimit)
            ? normalizeRetrievedMemoriesLimit(context.retrievedMemoriesLimit)
            : defaultLimit;
        state.apiCachingEnabled = context.apiCachingEnabled !== false;
        state.semanticCachingEnabled = context.semanticCachingEnabled !== false;
        state.rateLimitingEnabled = context.rateLimitingEnabled !== false;
        state.rateLimitLimit = normalizeRateLimitValue(context.rateLimitLimit, defaultRateLimitLimit);
        state.rateLimitRemaining = Math.min(
            state.rateLimitLimit,
            normalizeRateLimitValue(context.rateLimitRemaining, state.rateLimitLimit)
        );
        applyProviderUsage(context.providerUsage);
        state.sessionManagementEnabled = context.sessionManagementEnabled !== false;
        applySessionManagementState();
    }

    function applyProviderUsage(usage) {
        if (!usage || typeof usage !== "object") {
            return;
        }

        state.providerUsage = {
            secApiHits: normalizeProviderHitCount(usage.secApiHits, state.providerUsage.secApiHits),
            tavilyApiHits: normalizeProviderHitCount(usage.tavilyApiHits, state.providerUsage.tavilyApiHits),
            twelveDataApiHits: normalizeProviderHitCount(usage.twelveDataApiHits, state.providerUsage.twelveDataApiHits)
        };
    }

    function normalizeProviderHitCount(value, fallback) {
        if (value === null || value === undefined || String(value).trim() === "") {
            return fallback;
        }

        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
    }

    function isSessionManagementEnabled() {
        return state.sessionManagementEnabled !== false;
    }

    function applySessionManagementState() {
        const enabled = isSessionManagementEnabled();
        elements.chatApp.classList.toggle("chat-app--session-management-disabled", !enabled);
        if (!enabled) {
            state.sessions = [];
            state.memories = [];
            state.caches = [];
            setMemoriesOpen(false);
        }
        syncLoadingState();
        elements.newSessionButton.disabled = !enabled;
        elements.refreshSessionsButton.disabled = state.sessionsRefreshing || !enabled;
        elements.memoriesButton.disabled = !enabled;
    }

    function handleUnauthorizedResponse(response) {
        if (!response || response.status !== 401) {
            return false;
        }

        state.userId = null;
        state.sessionId = null;
        state.messages = [];
        state.messagesBySession = {};
        state.pendingSessions = {};
        state.pendingProgressBySession = {};
        state.sessions = [];
        state.memories = [];
        state.caches = [];
        state.memoriesOpen = false;
        state.cacheOpen = false;
        state.apiCachingEnabled = true;
        state.semanticCachingEnabled = true;
        state.rateLimitingEnabled = true;
        resetRateLimitStatus();
        clearStoredSession();
        showLogin();
        return true;
    }

    function activeRetrievedMemoriesLimit() {
        return normalizeRetrievedMemoriesLimit(state.retrievedMemoriesLimit);
    }

    function isApiCachingEnabled() {
        return state.apiCachingEnabled !== false;
    }

    function isSemanticCachingEnabled() {
        return state.semanticCachingEnabled !== false;
    }

    function isRateLimitingEnabled() {
        return state.rateLimitingEnabled !== false;
    }

    function applyRateLimitHeaders(response) {
        if (!response || !response.headers) {
            return;
        }

        const limit = parseRateLimitHeader(response.headers.get("X-Rate-Limit-Limit"));
        const remaining = parseRateLimitHeader(response.headers.get("X-Rate-Limit-Remaining"));
        if (limit === null && remaining === null) {
            renderRateLimitStatus();
            return;
        }

        if (limit !== null) {
            state.rateLimitLimit = limit;
        }

        if (remaining !== null) {
            state.rateLimitRemaining = remaining;
        }

        renderRateLimitStatus();
    }

    function resetRateLimitStatus() {
        state.rateLimitLimit = defaultRateLimitLimit;
        state.rateLimitRemaining = defaultRateLimitLimit;
        renderRateLimitStatus();
    }

    function parseRateLimitHeader(value) {
        if (value === null || value === undefined || String(value).trim() === "") {
            return null;
        }

        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
    }

    function normalizeRateLimitValue(value, fallback) {
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
    }

    function normalizeRetrievedMemoriesLimit(value) {
        const parsed = Number.parseInt(String(value), 10);
        if (!Number.isFinite(parsed)) {
            return state.defaultRetrievedMemoriesLimit;
        }

        return Math.min(maxRetrievedMemoriesLimit, Math.max(1, parsed));
    }

    function buildEmptyState() {
        return elements.emptyStateTemplate.content.firstElementChild.cloneNode(true);
    }

    function buildMessage(message) {
        const article = document.createElement("article");
        article.className = ["message", "message--" + message.role, message.variant ? "message--" + message.variant : ""]
            .filter(Boolean)
            .join(" ");

        const header = document.createElement("div");
        header.className = "message__header";

        const role = document.createElement("span");
        role.className = "message__role";
        role.textContent = message.role === "user" ? "You" : "Agent";

        const meta = document.createElement("div");
        meta.className = "message__meta";

        const tokenUsage = resolveTokenUsage(message);
        if (message.fromSemanticGuardrail || message.fromSemanticCache || message.responseTimeMs != null || tokenUsage) {
            const badges = document.createElement("div");
            badges.className = "message__badges";

            if (message.fromSemanticGuardrail) {
                const guardrailBadge = document.createElement("span");
                guardrailBadge.className = "badge badge--guardrail";
                guardrailBadge.textContent = "Blocked by guardrail";
                badges.appendChild(guardrailBadge);
            }

            if (message.fromSemanticCache) {
                const cacheBadge = document.createElement("span");
                cacheBadge.className = "badge badge--cache";
                cacheBadge.textContent = "From cache";
                badges.appendChild(cacheBadge);
            }

            if (message.responseTimeMs != null) {
                const durationBadge = document.createElement("span");
                durationBadge.className = "badge badge--timing";
                durationBadge.textContent = formatDuration(message.responseTimeMs);
                badges.appendChild(durationBadge);
            }

            if (tokenUsage) {
                const tokenBadge = document.createElement("span");
                tokenBadge.className = "badge badge--tokens";
                tokenBadge.textContent = formatTokenBadge(tokenUsage);
                badges.appendChild(tokenBadge);
            }

            meta.appendChild(badges);
        }

        const timestamp = document.createElement("span");
        timestamp.className = "message__timestamp";
        timestamp.textContent = formatTimestamp(message.timestamp);

        meta.appendChild(timestamp);
        header.append(role, meta);
        article.appendChild(header);

        const content = document.createElement("div");
        content.className = "message__content";
        appendMessageContent(content, message);
        article.appendChild(content);

        const supplements = buildSupplementPanels(message);
        if (supplements) {
            article.appendChild(supplements);
        }

        return article;
    }

    function buildSupplementPanels(message) {
        const panels = [];
        const memories = Array.isArray(message.memories) ? message.memories : [];
        const hasExecutionMetadata = Array.isArray(message.executionSteps);
        const executionSteps = hasExecutionMetadata ? message.executionSteps : [];

        if (memories.length > 0) {
            panels.push(buildDisclosurePanel("Retrieved memories", memories, function (memory) {
                const item = document.createElement("li");
                item.textContent = memory;
                return item;
            }));
        }

        if (message.role === "assistant" && hasExecutionMetadata) {
            panels.push(buildDisclosurePanel(
                "Execution breakdown",
                buildExecutionItems(executionSteps),
                renderExecutionBreakdownItem,
                "No execution steps recorded.",
                "message__disclosure-list--steps"
            ));
        }

        if (panels.length === 0) {
            return null;
        }

        const container = document.createElement("div");
        container.className = "message__supplements";
        panels.forEach(function (panel) {
            container.appendChild(panel);
        });
        return container;
    }

    function buildExecutionItems(steps) {
        const items = [];
        const loops = new Map();

        for (const step of steps) {
            const loop = resolveStepLoop(step);
            if (loop == null) {
                items.push({ type: "step", step: step });
                continue;
            }

            let loopItem = loops.get(loop);
            if (!loopItem) {
                loopItem = { type: "loop", loop: loop, steps: [] };
                loops.set(loop, loopItem);
                items.push(loopItem);
            }
            loopItem.steps.push(step);
        }

        return items;
    }

    function renderExecutionBreakdownItem(item) {
        if (item && item.type === "loop") {
            return renderExecutionLoopItem(item);
        }

        return renderExecutionStepItem(item && item.step ? item.step : item);
    }

    function renderExecutionLoopItem(loopItem) {
        const item = document.createElement("li");
        item.className = "message__disclosure-item message__disclosure-item--loop";

        const row = document.createElement("div");
        row.className = "message__disclosure-item-row";

        const heading = document.createElement("div");
        heading.className = "message__disclosure-item-heading";

        const label = document.createElement("span");
        label.className = "message__disclosure-item-label";
        label.textContent = "Agent loop " + loopItem.loop;
        heading.appendChild(label);

        const kindBadge = document.createElement("span");
        kindBadge.className = "message__step-kind message__step-kind--loop";
        kindBadge.textContent = "Loop";
        heading.appendChild(kindBadge);

        row.appendChild(heading);

        const tokenUsage = sumStepTokenUsage(loopItem.steps);
        if (tokenUsage) {
            const tokenBadge = document.createElement("span");
            tokenBadge.className = "badge badge--tokens badge--timing-inline";
            tokenBadge.textContent = formatTokenBadge(tokenUsage);
            row.appendChild(tokenBadge);
        }

        const durationMs = sumStepDurations(loopItem.steps);
        if (durationMs != null) {
            const timingBadge = document.createElement("span");
            timingBadge.className = "badge badge--timing badge--timing-inline";
            timingBadge.textContent = formatDuration(durationMs);
            row.appendChild(timingBadge);
        }

        item.appendChild(row);

        const stepList = document.createElement("ul");
        stepList.className = "message__loop-step-list";
        loopItem.steps.forEach(function (step) {
            stepList.appendChild(renderExecutionStepItem(step));
        });
        item.appendChild(stepList);

        return item;
    }

    function renderExecutionStepItem(step) {
        const item = document.createElement("li");
        item.className = "message__disclosure-item";

        const row = document.createElement("div");
        row.className = "message__disclosure-item-row";

        const heading = document.createElement("div");
        heading.className = "message__disclosure-item-heading";

        const label = document.createElement("span");
        label.className = "message__disclosure-item-label";
        label.textContent = resolveStepLabel(step);
        heading.appendChild(label);

        const kind = resolveStepKind(step);
        if (kind) {
            const kindBadge = document.createElement("span");
            kindBadge.className = "message__step-kind message__step-kind--" + kind;
            kindBadge.textContent = formatStepKind(kind);
            heading.appendChild(kindBadge);
        }

        row.appendChild(heading);

        const stepTokenUsage = resolveTokenUsage(step);
        const dataAccesses = resolveDataAccesses(step);
        if (stepTokenUsage) {
            const tokenBadge = document.createElement("span");
            tokenBadge.className = "badge badge--tokens badge--timing-inline";
            tokenBadge.textContent = formatTokenBadge(stepTokenUsage);
            row.appendChild(tokenBadge);
        }

        const dataAccessBadgeLabel = formatDataAccessBadge(dataAccesses);
        if (dataAccessBadgeLabel) {
            const dataAccessBadge = document.createElement("span");
            dataAccessBadge.className = "badge badge--data-source badge--timing-inline";
            dataAccessBadge.textContent = dataAccessBadgeLabel;
            row.appendChild(dataAccessBadge);
        }

        const durationMs = resolveStepDuration(step);
        if (durationMs != null) {
            const timingBadge = document.createElement("span");
            timingBadge.className = "badge badge--timing badge--timing-inline";
            timingBadge.textContent = formatDuration(durationMs);
            row.appendChild(timingBadge);
        }

        item.appendChild(row);

        const summary = resolveStepSummary(step);
        if (summary || stepTokenUsage || dataAccesses.length > 0) {
            const details = document.createElement("details");
            details.className = "message__subdisclosure";

            const summaryToggle = document.createElement("summary");
            summaryToggle.className = "message__subdisclosure-summary";
            summaryToggle.textContent = "Details";
            details.appendChild(summaryToggle);

            const body = document.createElement("div");
            body.className = "message__subdisclosure-body";
            if (dataAccesses.length > 0) {
                body.appendChild(renderDataAccessList(dataAccesses));
            }
            if (stepTokenUsage) {
                const tokenBreakdown = document.createElement("p");
                tokenBreakdown.className = "message__token-breakdown";
                tokenBreakdown.textContent = formatTokenBreakdown(stepTokenUsage);
                body.appendChild(tokenBreakdown);
            }
            if (summary) {
                renderMarkdownContent(body, summary);
            }
            details.appendChild(body);

            item.appendChild(details);
        }

        return item;
    }

    function renderDataAccessList(dataAccesses) {
        const wrapper = document.createElement("div");
        wrapper.className = "message__data-accesses";

        const heading = document.createElement("p");
        heading.className = "message__data-access-heading";
        heading.textContent = "Data retrieval";
        wrapper.appendChild(heading);

        const list = document.createElement("ul");
        list.className = "message__data-access-list";
        for (const access of dataAccesses) {
            const item = document.createElement("li");
            item.className = "message__data-access-item";

            const name = document.createElement("span");
            name.className = "message__data-access-name";
            name.textContent = formatCacheName(access.cacheName || "data");
            item.appendChild(name);

            const source = document.createElement("span");
            source.className = "message__data-access-source message__data-access-source--" + access.source;
            source.textContent = formatDataAccessSource(access.source);
            item.appendChild(source);

            const latency = document.createElement("span");
            latency.className = "message__data-access-latency";
            latency.textContent = access.durationMs == null ? "Latency unavailable" : formatDuration(access.durationMs);
            item.appendChild(latency);

            if (access.key) {
                const key = document.createElement("code");
                key.className = "message__data-access-key";
                key.textContent = access.key;
                item.appendChild(key);
            }

            list.appendChild(item);
        }

        wrapper.appendChild(list);
        return wrapper;
    }

    function buildDisclosurePanel(title, items, renderItem, emptyText, listClassName) {
        const wrapper = document.createElement("details");
        wrapper.className = "message__disclosure";

        const summary = document.createElement("summary");
        summary.className = "message__disclosure-summary";

        const label = document.createElement("span");
        label.className = "message__disclosure-label";
        label.textContent = title;
        summary.appendChild(label);

        const count = document.createElement("span");
        count.className = "message__disclosure-count";
        count.textContent = String(items.length);
        summary.appendChild(count);

        wrapper.appendChild(summary);

        if (items.length === 0) {
            const empty = document.createElement("p");
            empty.className = "message__disclosure-empty";
            empty.textContent = emptyText || "No items.";
            wrapper.appendChild(empty);
            return wrapper;
        }

        const list = document.createElement("ul");
        list.className = "message__disclosure-list";
        if (listClassName) {
            list.classList.add(listClassName);
        }
        for (const itemValue of items) {
            list.appendChild(renderItem(itemValue));
        }
        wrapper.appendChild(list);

        return wrapper;
    }

    function appendMessageContent(container, message) {
        if (message.role === "assistant" && !message.variant) {
            renderMarkdownContent(container, message.content);
            return;
        }

        const paragraph = document.createElement("p");
        paragraph.textContent = message.content;
        container.appendChild(paragraph);
    }

    function renderMarkdownContent(container, markdown) {
        const lines = String(markdown || "").replace(/\r\n?/g, "\n").split("\n");
        let index = 0;
        let paragraphLines = [];

        function flushParagraph() {
            if (paragraphLines.length === 0) {
                return;
            }

            const paragraph = document.createElement("p");
            appendInlineMarkdown(paragraph, paragraphLines.join(" "));
            container.appendChild(paragraph);
            paragraphLines = [];
        }

        while (index < lines.length) {
            const line = lines[index];
            const trimmed = line.trim();

            if (!trimmed) {
                flushParagraph();
                index += 1;
                continue;
            }

            const headingMatch = trimmed.match(/^(#{1,6})\s+(.*)$/);
            if (headingMatch) {
                flushParagraph();
                const level = Math.min(headingMatch[1].length, 6);
                const heading = document.createElement("h" + level);
                appendInlineMarkdown(heading, headingMatch[2]);
                container.appendChild(heading);
                index += 1;
                continue;
            }

            const unorderedMatch = trimmed.match(/^[-*]\s+(.*)$/);
            const orderedMatch = trimmed.match(/^\d+\.\s+(.*)$/);
            if (unorderedMatch || orderedMatch) {
                flushParagraph();
                const list = document.createElement(orderedMatch ? "ol" : "ul");

                while (index < lines.length) {
                    const listLine = lines[index].trim();
                    const currentUnordered = listLine.match(/^[-*]\s+(.*)$/);
                    const currentOrdered = listLine.match(/^\d+\.\s+(.*)$/);
                    const currentMatch = orderedMatch ? currentOrdered : currentUnordered;

                    if (!currentMatch) {
                        break;
                    }

                    const item = document.createElement("li");
                    appendInlineMarkdown(item, currentMatch[1]);
                    list.appendChild(item);
                    index += 1;
                }

                container.appendChild(list);
                continue;
            }

            paragraphLines.push(trimmed);
            index += 1;
        }

        flushParagraph();

        if (!container.hasChildNodes()) {
            const paragraph = document.createElement("p");
            paragraph.textContent = markdown;
            container.appendChild(paragraph);
        }
    }

    function appendInlineMarkdown(parent, text) {
        const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\))/g;
        let lastIndex = 0;
        let match;

        while ((match = pattern.exec(text)) !== null) {
            if (match.index > lastIndex) {
                parent.appendChild(document.createTextNode(text.slice(lastIndex, match.index)));
            }

            const token = match[0];
            if (token.startsWith("**") && token.endsWith("**")) {
                const strong = document.createElement("strong");
                strong.textContent = token.slice(2, -2);
                parent.appendChild(strong);
            } else if (token.startsWith("`") && token.endsWith("`")) {
                const code = document.createElement("code");
                code.textContent = token.slice(1, -1);
                parent.appendChild(code);
            } else if (token.startsWith("[")) {
                const linkMatch = token.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
                if (linkMatch) {
                    const anchor = document.createElement("a");
                    anchor.href = linkMatch[2];
                    anchor.textContent = linkMatch[1];
                    anchor.target = "_blank";
                    anchor.rel = "noreferrer noopener";
                    parent.appendChild(anchor);
                } else {
                    parent.appendChild(document.createTextNode(token));
                }
            } else {
                parent.appendChild(document.createTextNode(token));
            }

            lastIndex = pattern.lastIndex;
        }

        if (lastIndex < text.length) {
            parent.appendChild(document.createTextNode(text.slice(lastIndex)));
        }
    }

    function buildTypingIndicator(progressSteps) {
        const article = document.createElement("article");
        article.className = "message message--assistant";

        const header = document.createElement("div");
        header.className = "message__header";

        const role = document.createElement("span");
        role.className = "message__role";
        role.textContent = "Agent";

        const timestamp = document.createElement("span");
        timestamp.className = "message__timestamp";
        timestamp.textContent = "Working";

        header.append(role, timestamp);
        article.appendChild(header);

        const content = document.createElement("div");
        content.className = "message__content";

        const steps = Array.isArray(progressSteps) ? progressSteps : [];
        if (steps.length > 0) {
            content.appendChild(buildLiveActivityList(steps));
        } else {
            const dots = document.createElement("div");
            dots.className = "typing-indicator";
            dots.innerHTML = "<span></span><span></span><span></span>";
            content.appendChild(dots);
        }
        article.appendChild(content);

        return article;
    }

    function buildLiveActivityList(steps) {
        const list = document.createElement("ol");
        list.className = "live-activity-list";

        steps.forEach(function (step) {
            const item = document.createElement("li");
            item.className = "live-activity-list__item live-activity-list__item--" + normalizeProgressStatus(step.status);

            const label = document.createElement("span");
            label.className = "live-activity-list__label";
            label.textContent = cleanProgressText(step.label) || cleanProgressText(step.id) || "Working";

            const meta = document.createElement("span");
            meta.className = "live-activity-list__meta";
            meta.textContent = formatLiveActivityMeta(step);

            item.append(label, meta);

            const summary = cleanProgressText(step.summary);
            if (summary) {
                const summaryElement = document.createElement("span");
                summaryElement.className = "live-activity-list__summary";
                summaryElement.textContent = summary;
                item.appendChild(summaryElement);
            }

            list.appendChild(item);
        });

        return list;
    }

    function updateSessionProgress(sessionId, step) {
        const targetSessionId = normalizeSessionValue(sessionId);
        const progressStep = normalizeProgressStep(step);
        if (!targetSessionId || !progressStep) {
            return;
        }

        const currentSteps = sessionProgress(targetSessionId);
        const nextSteps = currentSteps.slice();
        const existingIndex = nextSteps.findIndex(function (currentStep) {
            return currentStep.id === progressStep.id;
        });

        if (existingIndex >= 0) {
            nextSteps[existingIndex] = progressStep;
        } else {
            nextSteps.push(progressStep);
        }

        state.pendingProgressBySession[targetSessionId] = nextSteps;
        if (targetSessionId === state.sessionId) {
            setStatus(progressStep.label || "Analyzing");
            renderMessages();
        }
    }

    function clearSessionProgress(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        delete state.pendingProgressBySession[targetSessionId];
    }

    function sessionProgress(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return [];
        }

        const steps = state.pendingProgressBySession[targetSessionId];
        return Array.isArray(steps) ? steps : [];
    }

    function normalizeProgressStep(step) {
        if (!step || typeof step !== "object") {
            return null;
        }

        const id = cleanProgressText(step.id) || cleanProgressText(step.label);
        if (!id) {
            return null;
        }

        return {
            id: id,
            label: cleanProgressText(step.label) || id,
            kind: cleanProgressText(step.kind),
            status: normalizeProgressStatus(step.status),
            summary: cleanProgressText(step.summary),
            durationMs: Number.isFinite(step.durationMs) ? step.durationMs : null
        };
    }

    function cleanProgressText(value) {
        return typeof value === "string" ? value.trim() : "";
    }

    function normalizeProgressStatus(status) {
        const value = cleanProgressText(status).toLowerCase();
        if (value === "completed" || value === "failed" || value === "running") {
            return value;
        }
        return "running";
    }

    function formatLiveActivityMeta(step) {
        const parts = [];
        if (step.kind) {
            parts.push(step.kind);
        }
        parts.push(step.status === "completed" ? "done" : step.status);
        if (step.durationMs != null) {
            parts.push(formatDuration(step.durationMs));
        }
        return parts.join(" ");
    }

    function setSessionLoading(sessionId, isLoading) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        if (isLoading) {
            state.pendingSessions[targetSessionId] = true;
            if (targetSessionId === state.sessionId) {
                setStatus("Analyzing");
            }
        } else {
            delete state.pendingSessions[targetSessionId];
            clearSessionProgress(targetSessionId);
        }

        syncLoadingState();
        renderMessages();
        renderSessions();
    }

    function syncLoadingState() {
        state.loading = isSessionPending(state.sessionId);
        elements.sendButton.disabled = state.loading;
        elements.newSessionButton.disabled = !isSessionManagementEnabled();
        elements.refreshSessionsButton.disabled = state.sessionsRefreshing || !isSessionManagementEnabled();
        elements.questionInput.disabled = state.loading;
        elements.logoutButton.disabled = hasPendingSessions();
        elements.sendButton.textContent = state.loading ? "Sending..." : "Send";
    }

    function isSessionPending(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        return Boolean(targetSessionId && state.pendingSessions[targetSessionId]);
    }

    function hasPendingSessions() {
        return Object.keys(state.pendingSessions).some(function (sessionId) {
            return Boolean(state.pendingSessions[sessionId]);
        });
    }

    function setSessionsRefreshing(isRefreshing) {
        state.sessionsRefreshing = isRefreshing;
        elements.refreshSessionsButton.disabled = isRefreshing || !isSessionManagementEnabled();
    }

    function setMemoriesLoading(isLoading) {
        state.memoriesLoading = isLoading;
        elements.memoriesButton.disabled = isLoading || !isSessionManagementEnabled();
        renderMemories();
    }

    function setCacheLoading(isLoading) {
        state.cacheLoading = isLoading;
        elements.cacheButton.disabled = isLoading;
        elements.refreshCacheButton.disabled = isLoading;
        renderCache();
    }

    function setStatus(label, variant) {
        elements.statusPill.textContent = label;
        elements.statusPill.classList.toggle("is-warning", variant === "warning");
        elements.statusPill.classList.toggle("is-error", variant === "error");
    }

    function autoResizeTextarea() {
        elements.questionInput.style.height = "auto";
        elements.questionInput.style.height = Math.min(elements.questionInput.scrollHeight, 224) + "px";
    }

    function scrollMessagesToBottom() {
        window.requestAnimationFrame(() => {
            elements.messages.scrollTop = elements.messages.scrollHeight;
        });
    }

    function extractErrorMessage(body, rawBody, status) {
        if (body && typeof body === "object") {
            if (typeof body.detail === "string" && body.detail.trim()) {
                return body.detail.trim();
            }
            if (typeof body.message === "string" && body.message.trim()) {
                return body.message.trim();
            }
            if (typeof body.title === "string" && body.title.trim()) {
                return body.title.trim();
            }
        }

        if (rawBody && rawBody.trim()) {
            return rawBody.trim();
        }

        return "Request failed with HTTP " + status + ".";
    }

    function formatTimestamp(value) {
        const parsed = new Date(value);
        if (Number.isNaN(parsed.getTime())) {
            return "";
        }

        return new Intl.DateTimeFormat(undefined, {
            hour: "numeric",
            minute: "2-digit"
        }).format(parsed);
    }

    function normalizeSessionList(sessions) {
        const normalized = [];
        const seen = new Set();

        addSessionId(state.sessionId);
        for (const sessionId of Object.keys(state.pendingSessions)) {
            addSessionId(sessionId);
        }
        for (const sessionId of Object.keys(state.messagesBySession)) {
            if (sessionMessages(sessionId).length > 0) {
                addSessionId(sessionId);
            }
        }
        if (Array.isArray(sessions)) {
            for (const sessionId of sessions) {
                addSessionId(sessionId);
            }
        }

        return normalized;

        function addSessionId(sessionId) {
            const value = typeof sessionId === "string" ? sessionId.trim() : "";
            if (!value || seen.has(value)) {
                return;
            }

            seen.add(value);
            normalized.push(value);
        }
    }

    function normalizeSessionValue(sessionId) {
        return typeof sessionId === "string" && sessionId.trim() ? sessionId.trim() : "";
    }

    function normalizeSessionMessages(messages) {
        if (!Array.isArray(messages)) {
            return [];
        }

        const loadedAt = new Date().toISOString();
        return messages.map(function (message) {
            const role = message && message.role === "user" ? "user" : "assistant";
            const content = message && typeof message.content === "string" ? message.content.trim() : "";
            if (!content) {
                return null;
            }

            return {
                role: role,
                content: content,
                timestamp: loadedAt
            };
        }).filter(Boolean);
    }

    function normalizeMemories(memories) {
        if (!Array.isArray(memories)) {
            return [];
        }

        return memories.map(function (memory) {
            if (!memory || typeof memory !== "object") {
                return null;
            }

            const id = typeof memory.id === "string" ? memory.id.trim() : "";
            const text = typeof memory.text === "string" ? memory.text.trim() : "";
            if (!id || !text) {
                return null;
            }

            return {
                id: id,
                text: text,
                userId: normalizeUserId(memory.userId),
                sessionId: typeof memory.sessionId === "string" ? memory.sessionId.trim() : "",
                namespace: typeof memory.namespace === "string" ? memory.namespace.trim() : "",
                memoryType: typeof memory.memoryType === "string" ? memory.memoryType.trim() : "",
                createdAt: typeof memory.createdAt === "string" ? memory.createdAt : "",
                updatedAt: typeof memory.updatedAt === "string" ? memory.updatedAt : "",
                lastAccessed: typeof memory.lastAccessed === "string" ? memory.lastAccessed : "",
                topics: Array.isArray(memory.topics) ? memory.topics : [],
                entities: Array.isArray(memory.entities) ? memory.entities : []
            };
        }).filter(Boolean);
    }

    function normalizeCaches(caches) {
        if (!Array.isArray(caches)) {
            return [];
        }

        return caches.map(function (cache) {
            if (!cache || typeof cache !== "object") {
                return null;
            }

            const name = typeof cache.name === "string" ? cache.name.trim() : "";
            if (!name) {
                return null;
            }

            const entries = Array.isArray(cache.entries)
                ? cache.entries.map(normalizeCacheEntry).filter(Boolean)
                : [];

            return {
                name: name,
                entryCount: Number.isFinite(cache.entryCount) ? cache.entryCount : entries.length,
                truncated: Boolean(cache.truncated),
                entries: entries
            };
        }).filter(Boolean);
    }

    function normalizeCacheEntry(entry) {
        if (!entry || typeof entry !== "object") {
            return null;
        }

        const key = typeof entry.key === "string" ? entry.key.trim() : "";
        if (!key) {
            return null;
        }

        return {
            key: key,
            ttlSeconds: Number.isFinite(entry.ttlSeconds) ? entry.ttlSeconds : null,
            valueType: typeof entry.valueType === "string" ? entry.valueType.trim() : "",
            valueSizeBytes: Number.isFinite(entry.valueSizeBytes) ? entry.valueSizeBytes : null,
            valueTruncated: Boolean(entry.valueTruncated),
            value: typeof entry.value === "string" ? entry.value : ""
        };
    }

    function formatCacheName(name) {
        return String(name || "")
            .split("-")
            .filter(Boolean)
            .map(function (part) {
                return part.charAt(0).toUpperCase() + part.slice(1);
            })
            .join(" ");
    }

    function formatCacheTtl(ttlSeconds) {
        if (!Number.isFinite(ttlSeconds)) {
            return "No TTL";
        }

        if (ttlSeconds < 60) {
            return ttlSeconds + "s";
        }

        const minutes = Math.floor(ttlSeconds / 60);
        const seconds = ttlSeconds % 60;
        return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
    }

    function formatBytes(value) {
        if (!Number.isFinite(value) || value <= 0) {
            return "";
        }

        if (value < 1024) {
            return value + " B";
        }

        const kilobytes = value / 1024;
        if (kilobytes < 1024) {
            return kilobytes.toFixed(1) + " KB";
        }

        return (kilobytes / 1024).toFixed(1) + " MB";
    }

    function formatMemoryDate(value) {
        const parsed = new Date(value);
        if (Number.isNaN(parsed.getTime())) {
            return "";
        }

        return new Intl.DateTimeFormat(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric",
            hour: "numeric",
            minute: "2-digit"
        }).format(parsed);
    }

    function formatMemoryList(values) {
        if (!Array.isArray(values)) {
            return "";
        }

        return values.map(function (value) {
            return typeof value === "string" ? value.trim() : "";
        }).filter(Boolean).join(", ");
    }

    function parseMemoryTopics(value) {
        if (typeof value !== "string") {
            return [];
        }

        const topics = [];
        value.split(",").forEach(function (topic) {
            const normalized = topic.trim();
            if (normalized && !topics.includes(normalized)) {
                topics.push(normalized);
            }
        });
        return topics;
    }

    function formatSessionLabel(sessionId) {
        const value = String(sessionId || "").trim();
        if (!value) {
            return "Session";
        }

        return ensureSessionLabel(value);
    }

    function renderSessionTimestamp(container, sessionId) {
        container.replaceChildren();
        container.classList.add("session-timestamp");
        const label = sessionId ? formatSessionLabel(sessionId) : "Unavailable";
        const parts = splitSessionLabel(label);

        const date = document.createElement("span");
        date.className = "session-timestamp__date";
        date.textContent = parts.date;

        const time = document.createElement("span");
        time.className = "session-timestamp__time";
        time.textContent = parts.time;

        container.append(date, time);
    }

    function splitSessionLabel(label) {
        const value = String(label || "").trim();
        const separatorIndex = value.indexOf(" ");
        if (separatorIndex < 0) {
            return {
                date: value || "Session",
                time: ""
            };
        }

        return {
            date: value.slice(0, separatorIndex),
            time: value.slice(separatorIndex + 1)
        };
    }

    function ensureSessionLabel(sessionId) {
        const labels = readSessionLabels();
        if (typeof labels[sessionId] === "string" && labels[sessionId].trim()) {
            return labels[sessionId];
        }

        labels[sessionId] = formatSessionTimestamp(new Date());
        writeSessionLabels(labels);
        return labels[sessionId];
    }

    function removeSessionLabel(sessionId) {
        const labels = readSessionLabels();
        if (!Object.prototype.hasOwnProperty.call(labels, sessionId)) {
            return;
        }

        delete labels[sessionId];
        writeSessionLabels(labels);
    }

    function readSessionLabels() {
        try {
            const stored = window.localStorage.getItem(activeSessionLabelsStorageKey());
            const labels = stored ? JSON.parse(stored) : {};
            return labels && typeof labels === "object" && !Array.isArray(labels) ? labels : {};
        } catch (error) {
            return {};
        }
    }

    function writeSessionLabels(labels) {
        try {
            window.localStorage.setItem(activeSessionLabelsStorageKey(), JSON.stringify(labels));
        } catch (error) {
            // Ignore storage access failures.
        }
    }

    function activeSessionLabelsStorageKey() {
        const userId = activeUserId() || "anonymous";
        return sessionLabelsStorageKey + ":" + userId;
    }

    function formatSessionTimestamp(value) {
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return formatSessionTimestamp(new Date());
        }

        return [
            date.getFullYear(),
            padTimestampPart(date.getMonth() + 1),
            padTimestampPart(date.getDate())
        ].join("-") + " " + [
            padTimestampPart(date.getHours()),
            padTimestampPart(date.getMinutes()),
            padTimestampPart(date.getSeconds())
        ].join(":") + "." + padTimestampPart(date.getMilliseconds(), 3);
    }

    function padTimestampPart(value, length) {
        return String(value).padStart(length || 2, "0");
    }

    function safeParseJson(rawBody) {
        if (!rawBody) {
            return null;
        }

        try {
            return JSON.parse(rawBody);
        } catch (error) {
            return null;
        }
    }

    function formatDuration(durationMs) {
        if (durationMs < 1000) {
            return durationMs + " ms";
        }

        return (durationMs / 1000).toFixed(durationMs >= 10_000 ? 0 : 1) + " s";
    }

    function formatDataAccessBadge(dataAccesses) {
        if (!Array.isArray(dataAccesses) || dataAccesses.length === 0) {
            return "";
        }

        const hasCache = dataAccesses.some(function (access) {
            return access.source === "cache";
        });
        const hasApi = dataAccesses.some(function (access) {
            return access.source === "api";
        });

        if (hasCache && hasApi) {
            return "Cache + API";
        }

        return hasCache ? "Cache data" : "API data";
    }

    function formatDataAccessSource(source) {
        return source === "cache" ? "Cache" : "Third party API";
    }

    function formatTokenBadge(tokenUsage) {
        const totalTokens = resolveTokenCount(tokenUsage && tokenUsage.totalTokens);
        if (totalTokens != null) {
            return totalTokens + " tok";
        }

        const promptTokens = resolveTokenCount(tokenUsage && tokenUsage.promptTokens);
        const completionTokens = resolveTokenCount(tokenUsage && tokenUsage.completionTokens);
        const fallbackTotal = [promptTokens, completionTokens].filter(function (value) {
            return value != null;
        }).reduce(function (sum, value) {
            return sum + value;
        }, 0);

        return fallbackTotal + " tok";
    }

    function formatTokenBreakdown(tokenUsage) {
        const parts = [];
        const promptTokens = resolveTokenCount(tokenUsage && tokenUsage.promptTokens);
        const completionTokens = resolveTokenCount(tokenUsage && tokenUsage.completionTokens);
        const totalTokens = resolveTokenCount(tokenUsage && tokenUsage.totalTokens);

        if (promptTokens != null) {
            parts.push("Prompt: " + promptTokens);
        }

        if (completionTokens != null) {
            parts.push("Completion: " + completionTokens);
        }

        if (totalTokens != null) {
            parts.push("Total: " + totalTokens);
        }

        return "Token usage: " + parts.join(" • ");
    }

    function hydrateSessionId() {
        try {
            const savedSessionId = window.localStorage.getItem(sessionStorageKey);
            if (savedSessionId) {
                return savedSessionId;
            }
        } catch (error) {
            // Ignore storage access failures.
        }

        const sessionId = createSessionId();
        persistSessionId(sessionId);
        return sessionId;
    }

    function persistSessionId(sessionId) {
        try {
            window.localStorage.setItem(sessionStorageKey, sessionId);
        } catch (error) {
            // Ignore storage access failures.
        }
    }

    function createSessionId() {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
            return window.crypto.randomUUID();
        }

        return "session-" + Date.now();
    }

    function formatAgentLabel(agentName) {
        return String(agentName || "")
            .toLowerCase()
            .split("_")
            .map(function (segment) {
                return segment ? segment.charAt(0).toUpperCase() + segment.slice(1) : segment;
            })
            .join(" ");
    }

    function resolveStepLabel(step) {
        if (step && typeof step === "object" && typeof step.label === "string" && step.label.trim()) {
            return step.label.trim();
        }

        return formatAgentLabel(resolveStepId(step));
    }

    function resolveStepId(step) {
        if (step && typeof step === "object" && typeof step.id === "string") {
            return step.id;
        }

        return step;
    }

    function resolveStepKind(step) {
        if (step && typeof step === "object" && typeof step.kind === "string" && step.kind.trim()) {
            return step.kind.trim().toLowerCase();
        }

        return null;
    }

    function resolveStepLoop(step) {
        if (step && typeof step === "object" && Number.isFinite(step.loop) && step.loop > 0) {
            return Math.floor(step.loop);
        }

        return null;
    }

    function resolveStepDuration(step) {
        if (step && typeof step === "object" && Number.isFinite(step.durationMs)) {
            return step.durationMs;
        }

        return null;
    }

    function resolveDataAccesses(step) {
        const dataAccesses = step && typeof step === "object" && Array.isArray(step.dataAccesses)
            ? step.dataAccesses
            : [];

        return dataAccesses.map(normalizeDataAccess).filter(Boolean);
    }

    function normalizeDataAccess(access) {
        if (!access || typeof access !== "object") {
            return null;
        }

        const cacheName = typeof access.cacheName === "string" ? access.cacheName.trim() : "";
        const key = typeof access.key === "string" ? access.key.trim() : "";
        const source = access.source === "cache" ? "cache" : "api";
        const durationMs = Number.isFinite(access.durationMs) ? Math.max(0, access.durationMs) : null;

        if (!cacheName && !key) {
            return null;
        }

        return {
            cacheName: cacheName,
            key: key,
            source: source,
            durationMs: durationMs
        };
    }

    function resolveStepSummary(step) {
        if (step && typeof step === "object" && typeof step.summary === "string" && step.summary.trim()) {
            return step.summary.trim();
        }

        return null;
    }

    function resolveTokenUsage(value) {
        if (!value || typeof value !== "object") {
            return null;
        }

        const tokenSource = value.tokenUsage && typeof value.tokenUsage === "object"
            ? value.tokenUsage
            : value;

        const promptTokens = resolveTokenCount(tokenSource.promptTokens);
        const completionTokens = resolveTokenCount(tokenSource.completionTokens);
        const totalTokens = resolveTokenCount(tokenSource.totalTokens);
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return null;
        }

        return {
            promptTokens: promptTokens,
            completionTokens: completionTokens,
            totalTokens: totalTokens
        };
    }

    function normalizeTokenUsage(value) {
        return resolveTokenUsage(value);
    }

    function sumStepDurations(steps) {
        let total = 0;
        let hasDuration = false;
        for (const step of steps) {
            const duration = resolveStepDuration(step);
            if (duration != null) {
                total += duration;
                hasDuration = true;
            }
        }

        return hasDuration ? total : null;
    }

    function sumStepTokenUsage(steps) {
        let promptTokens = 0;
        let completionTokens = 0;
        let totalTokens = 0;
        let hasPromptTokens = false;
        let hasCompletionTokens = false;
        let hasTotalTokens = false;

        for (const step of steps) {
            const tokenUsage = resolveTokenUsage(step);
            if (!tokenUsage) {
                continue;
            }

            if (tokenUsage.promptTokens != null) {
                promptTokens += tokenUsage.promptTokens;
                hasPromptTokens = true;
            }
            if (tokenUsage.completionTokens != null) {
                completionTokens += tokenUsage.completionTokens;
                hasCompletionTokens = true;
            }
            if (tokenUsage.totalTokens != null) {
                totalTokens += tokenUsage.totalTokens;
                hasTotalTokens = true;
            }
        }

        if (!hasPromptTokens && !hasCompletionTokens && !hasTotalTokens) {
            return null;
        }

        return {
            promptTokens: hasPromptTokens ? promptTokens : null,
            completionTokens: hasCompletionTokens ? completionTokens : null,
            totalTokens: hasTotalTokens ? totalTokens : null
        };
    }

    function resolveTokenCount(value) {
        return Number.isFinite(value) ? value : null;
    }

    function formatStepKind(kind) {
        if (kind === "loop") {
            return "Loop";
        }

        return kind === "agent" ? "Agent" : "System";
    }

    function normalizeUserId(userId) {
        return userId && userId.trim() ? userId.trim() : null;
    }

    function activeUserId() {
        return normalizeUserId(state.userId);
    }
}());
