(function () {
    const sessionStorageKey = "stock-analysis-chat:session-id";
    const sessionLabelsStorageKey = "stock-analysis-chat:session-labels";
    const maxRetrievedMemoriesLimit = 20;
    const defaultRateLimitLimit = 6;
    const sessionRefreshIntervalMs = 2000;
    const circuitBreakerRefreshIntervalMs = 2000;
    const memoryTabAll = "all";
    const memoryTabCurrentChat = "current-chat";
    const circuitBreakerProviders = [
        {
            providerId: "twelve-data",
            label: "Twelve Data",
            approvalTools: ["runMarketDataAgent", "runTechnicalAnalysisAgent", "runBacktestAgent"]
        },
        {
            providerId: "sec",
            label: "SEC",
            approvalTools: ["runFundamentalsAgent"]
        },
        {
            providerId: "tavily",
            label: "Tavily",
            approvalTools: ["runNewsAgent"]
        },
        { providerId: "lang-cache", label: "LangCache" },
        { providerId: "agent-memory", label: "Agent Memory" }
    ];
    const approvalToolOptions = [
        { toolName: "runMarketDataAgent", label: "Market data" },
        { toolName: "runFundamentalsAgent", label: "Fundamentals" },
        { toolName: "runNewsAgent", label: "News" },
        { toolName: "runTechnicalAnalysisAgent", label: "Technical" },
        { toolName: "runBacktestAgent", label: "Backtest" }
    ];

    const state = {
        loading: false,
        sessionsRefreshing: false,
        sessionRefreshTimer: null,
        sessionRefreshInFlight: false,
        circuitBreakerRefreshTimer: null,
        circuitBreakerRefreshInFlight: false,
        circuitBreakersLoading: false,
        memoriesLoading: false,
        cacheLoading: false,
        messages: [],
        messagesBySession: {},
        hiddenApprovalIds: new Set(),
        sessionWorkflowBySession: {},
        pendingSessions: {},
        pendingProgressBySession: {},
        pendingProgressStartedAtBySession: {},
        progressClock: null,
        sessions: [],
        memories: [],
        memoryTab: memoryTabAll,
        caches: [],
        memoriesOpen: false,
        memoryCreateOpen: false,
        cacheOpen: false,
        mobileSessionsOpen: false,
        mobileToolsOpen: false,
        sessionId: null,
        userId: null,
        defaultRetrievedMemoriesLimit: 10,
        retrievedMemoriesLimit: 10,
        apiCachingEnabled: true,
        semanticCachingEnabled: true,
        rateLimitingEnabled: true,
        approvalRequiredTools: [],
        rateLimitLimit: defaultRateLimitLimit,
        rateLimitRemaining: defaultRateLimitLimit,
        providerUsage: {
            secApiHits: 0,
            tavilyApiHits: 0,
            twelveDataApiHits: 0
        },
        circuitBreakers: defaultCircuitBreakers(),
        providerDeadLetter: [],
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
        requireApprovalTools: document.getElementById("require-approval-tools"),
        retrievedMemoriesLimitInput: document.getElementById("retrieved-memories-limit-input"),
        messages: document.getElementById("messages"),
        composer: document.getElementById("composer"),
        sendButton: document.getElementById("send-button"),
        mobileSessionsButton: document.getElementById("mobile-sessions-button"),
        mobileNewSessionButton: document.getElementById("mobile-new-session-button"),
        mobileToolsButton: document.getElementById("mobile-tools-button"),
        mobileCloseSessionsButton: document.getElementById("mobile-close-sessions-button"),
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
        addMemoryButton: document.getElementById("add-memory-button"),
        closeMemoryFormButton: document.getElementById("close-memory-form-button"),
        closeMemoriesButton: document.getElementById("close-memories-button"),
        flushMemoriesButton: document.getElementById("flush-memories-button"),
        memoriesList: document.getElementById("memories-list"),
        memoriesEmpty: document.getElementById("memories-empty"),
        memoryTabButtons: Array.from(document.querySelectorAll("[data-memory-tab]")),
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
        circuitBreakerList: document.getElementById("circuit-breaker-list"),
        providerDeadLetterList: document.getElementById("provider-dead-letter-list"),
        providerDeadLetterCount: document.getElementById("provider-dead-letter-count"),
        refreshCircuitBreakersButton: document.getElementById("refresh-circuit-breakers-button"),
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
        elements.mobileSessionsButton.addEventListener("click", onMobileSessionsClick);
        elements.mobileNewSessionButton.addEventListener("click", onMobileNewSessionClick);
        elements.mobileToolsButton.addEventListener("click", onMobileToolsClick);
        elements.mobileCloseSessionsButton.addEventListener("click", closeMobileSessions);
        elements.sessionsList.addEventListener("click", onSessionClick);
        elements.cacheButton.addEventListener("click", onCacheButtonClick);
        elements.closeCacheButton.addEventListener("click", closeCache);
        elements.refreshCacheButton.addEventListener("click", onRefreshCacheClick);
        elements.cacheList.addEventListener("click", onCacheClick);
        elements.memoriesButton.addEventListener("click", onMemoriesButtonClick);
        elements.addMemoryButton.addEventListener("click", onAddMemoryClick);
        elements.closeMemoryFormButton.addEventListener("click", closeMemoryCreatePopup);
        elements.memoryCreateForm.addEventListener("submit", onMemoryCreateSubmit);
        elements.closeMemoriesButton.addEventListener("click", closeMemories);
        elements.memoriesList.addEventListener("click", onMemoryClick);
        elements.flushMemoriesButton.addEventListener("click", flushMemories);
        elements.memoryTabButtons.forEach(function (button) {
            button.addEventListener("click", onMemoryTabClick);
        });
        elements.questionInput.addEventListener("input", autoResizeTextarea);
        elements.questionInput.addEventListener("keydown", onComposerKeydown);
        elements.messages.addEventListener("click", onSuggestionClick);
        elements.messages.addEventListener("click", onApprovalClick);
        elements.apiCacheToggle.addEventListener("click", onApiCacheToggleClick);
        elements.semanticCacheToggle.addEventListener("click", onSemanticCacheToggleClick);
        elements.rateLimitToggle.addEventListener("click", onRateLimitToggleClick);
        if (elements.requireApprovalTools) {
            elements.requireApprovalTools.addEventListener("click", onRequireApprovalToolClick);
        }
        elements.secApiResetButton.addEventListener("click", onProviderUsageResetClick);
        elements.tavilyApiResetButton.addEventListener("click", onProviderUsageResetClick);
        elements.twelveDataApiResetButton.addEventListener("click", onProviderUsageResetClick);
        elements.refreshCircuitBreakersButton.addEventListener("click", onCircuitBreakersRefreshClick);
        elements.circuitBreakerList.addEventListener("click", onCircuitBreakerListClick);
        elements.retrievedMemoriesLimitInput.addEventListener("input", onRetrievedMemoriesLimitInput);
        elements.retrievedMemoriesLimitInput.addEventListener("blur", commitRetrievedMemoriesLimit);
        elements.accountMenuButton.addEventListener("click", onAccountMenuClick);
        elements.logoutButton.addEventListener("click", onLogout);
        document.addEventListener("click", onDocumentClick);
        document.addEventListener("keydown", onDocumentKeydown);
        document.addEventListener("visibilitychange", onVisibilityChange);

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
        closeMobileSessions();
        closeMobileTools();
        applySessionManagementState();
        renderIdentity();
        renderCircuitBreakers();
        renderSessions();
        renderMessages();
        autoResizeTextarea();
        refreshCircuitBreakers(false);
        syncCircuitBreakerRefreshLoop();
        if (isSessionManagementEnabled()) {
            refreshSessions();
            loadSessionMessages(state.sessionId, false);
        }

        if (focusComposer) {
            elements.questionInput.focus();
        }
    }

    function showLogin() {
        stopSessionRefreshLoop();
        stopCircuitBreakerRefreshLoop();
        elements.chatApp.hidden = true;
        elements.loginScreen.hidden = false;
        closeAccountMenu();
        closeMobileSessions();
        closeMobileTools();
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
            if (!state.mobileToolsOpen || event.target.closest(".header-actions") || event.target.closest("#mobile-tools-button")) {
                return;
            }
        }

        closeAccountMenu();
        closeMobileTools();
    }

    function onDocumentKeydown(event) {
        if (event.key === "Escape") {
            if (state.memoryCreateOpen) {
                closeMemoryCreatePopup();
                return;
            }
            closeAccountMenu();
            closeMobileSessions();
            closeMobileTools();
            closeCache();
            closeMemories();
        }
    }

    function onVisibilityChange() {
        if (!document.hidden) {
            refreshActiveSessionFromServer();
            refreshCircuitBreakers(false);
        }
        syncSessionRefreshLoop();
        syncCircuitBreakerRefreshLoop();
    }

    async function onLogout() {
        if (hasPendingSessions()) {
            setStatus("Wait for responses", "warning");
            closeAccountMenu();
            return;
        }

        stopSessionRefreshLoop();
        stopCircuitBreakerRefreshLoop();
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
        state.hiddenApprovalIds = new Set();
        state.sessionWorkflowBySession = {};
        state.pendingSessions = {};
        state.pendingProgressBySession = {};
        state.sessions = [];
        state.memories = [];
        state.memoryTab = memoryTabAll;
        state.caches = [];
        state.memoriesOpen = false;
        state.memoryCreateOpen = false;
        state.cacheOpen = false;
        state.mobileSessionsOpen = false;
        state.mobileToolsOpen = false;
        state.apiCachingEnabled = true;
        state.semanticCachingEnabled = true;
        state.rateLimitingEnabled = true;
        state.approvalRequiredTools = [];
        state.circuitBreakers = defaultCircuitBreakers();
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

    function onMobileSessionsClick(event) {
        event.stopPropagation();
        if (!isSessionManagementEnabled()) {
            return;
        }

        setMobileSessionsOpen(!state.mobileSessionsOpen);
        closeMobileTools();
    }

    function onMobileNewSessionClick() {
        startNewSession();
        closeMobileSessions();
        closeMobileTools();
    }

    function onMobileToolsClick(event) {
        event.stopPropagation();
        setMobileToolsOpen(!state.mobileToolsOpen);
        closeMobileSessions();
    }

    function setMobileSessionsOpen(isOpen) {
        state.mobileSessionsOpen = Boolean(isOpen) && isSessionManagementEnabled();
        elements.chatApp.classList.toggle("chat-app--mobile-sessions-open", state.mobileSessionsOpen);
        elements.mobileSessionsButton.setAttribute("aria-expanded", String(state.mobileSessionsOpen));
    }

    function closeMobileSessions() {
        setMobileSessionsOpen(false);
    }

    function setMobileToolsOpen(isOpen) {
        state.mobileToolsOpen = Boolean(isOpen);
        elements.chatApp.classList.toggle("chat-app--mobile-tools-open", state.mobileToolsOpen);
        elements.mobileToolsButton.setAttribute("aria-expanded", String(state.mobileToolsOpen));
    }

    function closeMobileTools() {
        setMobileToolsOpen(false);
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

    async function onApprovalClick(event) {
        const button = event.target.closest("[data-approval-action]");
        if (!button) {
            return;
        }

        event.preventDefault();
        event.stopPropagation();
        const action = button.dataset.approvalAction;
        const workflowId = normalizeSessionValue(button.dataset.workflowId);
        const approvalId = normalizeSessionValue(button.dataset.approvalId);
        const targetSessionId = normalizeSessionValue(button.dataset.sessionId) || state.sessionId;
        if (!workflowId || !approvalId || (action !== "approve" && action !== "reject")) {
            return;
        }

        setApprovalButtonsDisabled(approvalId, true);
        setSessionLoading(targetSessionId, true);
        updateSessionProgress(targetSessionId, {
            id: "APPROVAL_RESUME",
            label: "Resuming workflow",
            kind: "system",
            status: "running",
            summary: action === "approve" ? "Continuing after approval." : "Continuing after rejection."
        });
        setStatus("Resuming workflow");

        let removedMessages = [];
        try {
            removedMessages = hideApprovalMessage(approvalId);
            const response = await requestApprovalDecision(workflowId, approvalId, action);
            appendAssistantResponse(response, targetSessionId);
            if (isSessionManagementEnabled()) {
                refreshSessions();
            }
            setStatus("Workflow resumed");
        } catch (error) {
            restoreApprovalMessages(approvalId, removedMessages);
            setApprovalButtonsDisabled(approvalId, false);
            setStatus("Approval failed", "error");
        } finally {
            setSessionLoading(targetSessionId, false);
            clearSessionProgress(targetSessionId);
        }
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

    function onRequireApprovalToolClick(event) {
        const button = event.target.closest("[data-approval-tool]");
        if (!button) {
            return;
        }
        const toolName = normalizeApprovalToolName(button.dataset.approvalTool);
        if (!toolName) {
            return;
        }
        if (isApprovalToolRequired(toolName)) {
            state.approvalRequiredTools = state.approvalRequiredTools.filter(function (selectedToolName) {
                return selectedToolName !== toolName;
            });
        } else {
            state.approvalRequiredTools = normalizeApprovalRequiredTools(state.approvalRequiredTools.concat(toolName));
        }
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

    async function onCircuitBreakersRefreshClick() {
        await refreshCircuitBreakers(true);
    }

    async function onCircuitBreakerListClick(event) {
        const latencyButton = event.target.closest("[data-latency-provider]");
        if (latencyButton) {
            await onLatencySimulationClick(latencyButton);
            return;
        }

        const approvalButton = event.target.closest("[data-approval-provider]");
        if (approvalButton) {
            onProviderApprovalClick(approvalButton);
            return;
        }

        const button = event.target.closest("[data-circuit-provider]");
        if (!button) {
            return;
        }

        const providerId = normalizeProviderId(button.dataset.circuitProvider);
        if (!providerId) {
            return;
        }

        const current = circuitBreakerByProvider(providerId);
        const enabled = !(current && current.failureSimulationEnabled);
        button.disabled = true;
        try {
            const updated = await updateFailureSimulation(providerId, enabled);
            mergeCircuitBreakerStatus(updated);
            renderCircuitBreakers();
            setStatus(enabled ? "Failure simulation on" : "Failure simulation off");
        } catch (error) {
            setStatus("Simulator update failed", "error");
        } finally {
            button.disabled = false;
        }
    }

    function onProviderApprovalClick(button) {
        const providerId = normalizeProviderId(button.dataset.approvalProvider);
        const toolNames = approvalToolsForProvider(providerId);
        if (toolNames.length === 0) {
            return;
        }

        if (areAllApprovalToolsRequired(toolNames)) {
            state.approvalRequiredTools = state.approvalRequiredTools.filter(function (selectedToolName) {
                return !toolNames.includes(selectedToolName);
            });
        } else {
            state.approvalRequiredTools = normalizeApprovalRequiredTools(state.approvalRequiredTools.concat(toolNames));
        }

        renderIdentity();
        persistChatSettings();
        setStatus(areAnyApprovalToolsRequired(toolNames) ? "Approval required" : "Approval disabled");
    }

    async function onLatencySimulationClick(button) {
        const providerId = normalizeProviderId(button.dataset.latencyProvider);
        if (!providerId) {
            return;
        }

        const current = circuitBreakerByProvider(providerId);
        const enabled = !(current && normalizeCount(current.latencySimulationMs) > 0);
        button.disabled = true;
        try {
            const updated = await updateLatencySimulation(providerId, enabled);
            mergeCircuitBreakerStatus(updated);
            renderCircuitBreakers();
            setStatus(enabled ? "Provider delay on" : "Provider delay off");
        } catch (error) {
            setStatus("Provider delay update failed", "error");
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

    function onMemoryTabClick(event) {
        const button = event.currentTarget;
        const nextTab = normalizeMemoryTab(button && button.dataset.memoryTab);
        if (nextTab === state.memoryTab) {
            return;
        }

        state.memoryTab = nextTab;
        renderMemories();
    }

    function onAddMemoryClick() {
        if (!isSessionManagementEnabled() || state.memoriesLoading) {
            return;
        }

        setMemoryCreateOpen(true);
        window.setTimeout(function () {
            elements.memoryTextInput.focus();
        }, 0);
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

    function closeMemoryCreatePopup() {
        setMemoryCreateOpen(false);
        clearMemoryCreateError();
    }

    function setMemoryCreateOpen(isOpen) {
        state.memoryCreateOpen = Boolean(isOpen) && state.memoriesOpen && isSessionManagementEnabled();
        elements.chatApp.classList.toggle("chat-app--memory-create-open", state.memoryCreateOpen);
        elements.addMemoryButton.setAttribute("aria-expanded", String(state.memoryCreateOpen));
        if (state.memoryCreateOpen) {
            elements.memoryCreateForm.dataset.open = "true";
        } else {
            delete elements.memoryCreateForm.dataset.open;
        }
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
        closeMobileSessions();
        closeMobileTools();
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
        closeMobileSessions();
        closeMobileTools();
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
            delete state.sessionWorkflowBySession[targetSessionId];
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

        let keepRequestPending = false;
        try {
            const clientRequestId = createClientRequestId();
            const response = await requestChatWithProgress(question, requestSessionId, clientRequestId);
            const responseSessionId = appendAssistantResponse(response, requestSessionId);
            if (isSessionManagementEnabled()) {
                refreshSessions();
            }
            if (responseSessionId === state.sessionId || !state.loading) {
                setStatus("Response received");
            }
        } catch (error) {
            if (isRequestAvailabilityError(error)) {
                keepRequestPending = true;
                markSessionWaitingForRecovery(requestSessionId);
                if (requestSessionId === state.sessionId || !state.loading) {
                    setStatus("Waiting for recovery", "warning");
                }
            } else {
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
            }
        } finally {
            if (!keepRequestPending) {
                setSessionLoading(requestSessionId, false);
                clearSessionProgress(requestSessionId);
            }
            if (requestSessionId === state.sessionId) {
                elements.questionInput.focus();
            }
        }
    }

    function appendAssistantResponse(response, fallbackSessionId) {
        const requestSessionId = normalizeSessionValue(fallbackSessionId) || state.sessionId;
        const responseSessionId = normalizeSessionValue(response.sessionId) || requestSessionId;
        state.userId = response.userId || activeUserId();
        state.retrievedMemoriesLimit = normalizeRetrievedMemoriesLimit(
            response.retrievedMemoriesLimit ?? state.retrievedMemoriesLimit
        );
        state.apiCachingEnabled = response.apiCachingEnabled !== false;
        state.semanticCachingEnabled = response.semanticCachingEnabled !== false;
        state.rateLimitingEnabled = response.rateLimitingEnabled !== false;
        state.approvalRequiredTools = normalizeApprovalRequiredTools(
            response.approvalRequiredTools,
            response.requireApprovalEnabled
        );
        applyProviderUsage(response.providerUsage);
        renderProviderUsage();
        if (state.sessionId === requestSessionId) {
            state.sessionId = responseSessionId;
            persistSessionId(state.sessionId);
            syncLoadingState();
            renderIdentity();
        }

        const executionSteps = Array.isArray(response.executionSteps) ? response.executionSteps : [];
        appendSessionMessage(responseSessionId, {
            role: "assistant",
            content: response.response || "No response returned.",
            timestamp: new Date().toISOString(),
            memories: response.retrievedMemories || [],
            fromSemanticCache: Boolean(response.fromSemanticCache),
            fromSemanticGuardrail: Boolean(response.fromSemanticGuardrail),
            tokenUsage: normalizeTokenUsage(response.tokenUsage),
            executionSteps: executionSteps,
            activitySteps: assistantActivitySteps(requestSessionId, executionSteps),
            responseTimeMs: Number.isFinite(response.responseTimeMs) ? response.responseTimeMs : null,
            pendingApproval: normalizePendingApproval(response.pendingApproval)
        });
        return responseSessionId;
    }

    async function requestChatWithProgress(message, sessionId, clientRequestId) {
        if (!canReadStreamingResponse()) {
            return requestChat(message, sessionId, clientRequestId);
        }

        try {
            return await requestChatStream(
                message,
                sessionId,
                clientRequestId,
                function (step) {
                    updateSessionProgress(sessionId, step);
                },
                function (metadata) {
                    applySessionWorkflowMetadata(sessionId, metadata);
                }
            );
        } catch (error) {
            if (error && error.fallbackToChat) {
                return requestChat(message, sessionId, clientRequestId);
            }
            throw error;
        }
    }

    async function requestChatStream(message, sessionId, clientRequestId, onProgress, onWorkflow) {
        let response;
        try {
            response = await fetch(new URL("./api/chat/stream", window.location.href), {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    sessionId: sessionId,
                    clientRequestId: clientRequestId,
                    message: message,
                    retrievedMemoriesLimit: activeRetrievedMemoriesLimit(),
                    apiCachingEnabled: isApiCachingEnabled(),
                    semanticCachingEnabled: isSemanticCachingEnabled(),
                    rateLimitingEnabled: isRateLimitingEnabled(),
                    requireApprovalEnabled: isRequireApprovalEnabled(),
                    approvalRequiredTools: approvalRequiredToolsPayload()
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
                const streamResponse = handleStreamLine(line, onProgress, onWorkflow);
                if (streamResponse) {
                    return streamResponse;
                }
            }
        }

        buffer += decoder.decode();
        const streamResponse = handleStreamLine(buffer, onProgress, onWorkflow);
        if (streamResponse) {
            return streamResponse;
        }

        throw createStreamingFallbackError();
    }

    function handleStreamLine(line, onProgress, onWorkflow) {
        const trimmed = String(line || "").trim();
        if (!trimmed) {
            return null;
        }

        const event = safeParseJson(trimmed);
        if (!event || typeof event !== "object") {
            return null;
        }

        if (event.metadata && typeof onWorkflow === "function") {
            onWorkflow(event.metadata);
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

    function isRequestAvailabilityError(error) {
        const message = error && typeof error.message === "string" ? error.message.trim() : "";
        return error && (
            error.fallbackToChat === true
            || error.name === "TypeError"
            || error.name === "NetworkError"
            || message === "Load failed"
            || message === "Failed to fetch"
            || message.includes("NetworkError")
            || message.includes("network")
        );
    }

    async function requestChat(message, sessionId, clientRequestId) {
        const response = await fetch(new URL("./api/chat", window.location.href), {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                sessionId: sessionId,
                clientRequestId: clientRequestId,
                message: message,
                retrievedMemoriesLimit: activeRetrievedMemoriesLimit(),
                apiCachingEnabled: isApiCachingEnabled(),
                semanticCachingEnabled: isSemanticCachingEnabled(),
                rateLimitingEnabled: isRateLimitingEnabled(),
                requireApprovalEnabled: isRequireApprovalEnabled(),
                approvalRequiredTools: approvalRequiredToolsPayload()
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

    async function requestApprovalDecision(workflowId, approvalId, action) {
        const response = await fetch(new URL(
            "./api/workflows/"
            + encodeURIComponent(workflowId)
            + "/approvals/"
            + encodeURIComponent(approvalId)
            + "/"
            + encodeURIComponent(action),
            window.location.href
        ), {
            method: "POST"
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
                rateLimitingEnabled: isRateLimitingEnabled(),
                requireApprovalEnabled: isRequireApprovalEnabled(),
                approvalRequiredTools: approvalRequiredToolsPayload()
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

    async function fetchCircuitBreakers() {
        const response = await fetch(new URL("./api/circuit-breakers", window.location.href));
        const contentType = response.headers.get("content-type") || "";
        const rawBody = await response.text();
        const body = contentType.includes("application/json") ? safeParseJson(rawBody) : null;

        if (!response.ok) {
            if (handleUnauthorizedResponse(response)) {
                throw new Error("Login is required.");
            }
            throw new Error(extractErrorMessage(body, rawBody, response.status));
        }

        return Array.isArray(body) ? body : [];
    }

    async function fetchProviderDeadLetter() {
        const response = await fetch(new URL("./api/circuit-breakers/dead-letter", window.location.href));
        const contentType = response.headers.get("content-type") || "";
        const rawBody = await response.text();
        const body = contentType.includes("application/json") ? safeParseJson(rawBody) : null;

        if (!response.ok) {
            if (handleUnauthorizedResponse(response)) {
                throw new Error("Login is required.");
            }
            throw new Error(extractErrorMessage(body, rawBody, response.status));
        }

        return Array.isArray(body) ? body : [];
    }

    async function updateFailureSimulation(providerId, enabled) {
        const response = await fetch(new URL(
            "./api/circuit-breakers/" + encodeURIComponent(providerId) + "/simulation",
            window.location.href
        ), {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ enabled: Boolean(enabled) })
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

    async function updateLatencySimulation(providerId, enabled) {
        const response = await fetch(new URL(
            "./api/circuit-breakers/" + encodeURIComponent(providerId) + "/latency",
            window.location.href
        ), {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ enabled: Boolean(enabled) })
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
            const sessionItems = body && Array.isArray(body.sessionDetails) && body.sessionDetails.length > 0
                ? body.sessionDetails
                : body && body.sessions;
            state.sessions = normalizeRemoteSessionList(sessionItems);
            if (reportStatus) {
                setStatus("Sessions refreshed");
            }
        } catch (error) {
            if (requestedUserId !== activeUserId()) {
                return;
            }
            state.sessions = normalizeRemoteSessionList(state.sessions);
            if (reportStatus) {
                setStatus("Session refresh failed", "error");
            }
        } finally {
            setSessionsRefreshing(false);
        }

        renderSessions();
        renderIdentity();
    }

    async function openCache() {
        closeMemories();
        closeMobileSessions();
        closeMobileTools();
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
        closeMobileSessions();
        closeMobileTools();
        setMemoriesOpen(true);
        await refreshMemories(true);
    }

    function closeMemories() {
        closeMemoryCreatePopup();
        setMemoriesOpen(false);
    }

    function setMemoriesOpen(isOpen) {
        state.memoriesOpen = Boolean(isOpen);
        elements.memorySidebar.hidden = !state.memoriesOpen;
        elements.memoriesButton.setAttribute("aria-expanded", String(state.memoriesOpen));
        elements.chatApp.classList.toggle("chat-app--memories-open", state.memoriesOpen);
        if (!state.memoriesOpen) {
            setMemoryCreateOpen(false);
        }
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
            closeMemoryCreatePopup();
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
            applySessionWorkflowMetadata(state.sessionId, body.metadata);
            setActiveSessionMessages(mergeSessionMessages(
                sessionMessages(state.sessionId),
                normalizeSessionMessages(body.messages)
            ));
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

    async function refreshActiveSessionFromServer() {
        if (!shouldRefreshActiveSession()) {
            return;
        }

        const requestedSessionId = normalizeSessionValue(state.sessionId);
        const requestedUserId = activeUserId();
        state.sessionRefreshInFlight = true;

        try {
            const sessionUrl = new URL("./api/chat/session/" + encodeURIComponent(requestedSessionId), window.location.href);
            const response = await fetch(sessionUrl);
            if (!response.ok) {
                handleUnauthorizedResponse(response);
                return;
            }

            const body = safeParseJson(await response.text()) || {};
            if (requestedUserId !== activeUserId() || requestedSessionId !== normalizeSessionValue(state.sessionId)) {
                return;
            }

            const currentMessages = sessionMessages(requestedSessionId);
            const nextMessages = mergeSessionMessages(
                currentMessages,
                normalizeSessionMessages(body.messages)
            );
            const workflowChanged = applySessionWorkflowMetadata(requestedSessionId, body.metadata);
            if (nextMessages.length === 0 && currentMessages.length > 0) {
                return;
            }
            const messagesChanged = messageListSignature(currentMessages) !== messageListSignature(nextMessages);
            if (!workflowChanged && !messagesChanged) {
                return;
            }

            state.userId = body.userId || activeUserId();
            state.sessionId = normalizeSessionValue(body.sessionId) || requestedSessionId;
            if (messagesChanged) {
                setActiveSessionMessages(nextMessages);
            }
            persistSessionId(state.sessionId);
            syncLoadingState();
            renderIdentity();
            renderSessions();
            renderMessages();
            refreshSessions(true, false);
            if (isSessionPending(state.sessionId)) {
                setStatus("Recovering workflow");
            }
        } catch (error) {
            // Keep polling silent. Recovery can complete on a later interval.
        } finally {
            state.sessionRefreshInFlight = false;
        }
    }

    function shouldRefreshActiveSession() {
        if (!isLoggedIn() || !isSessionManagementEnabled()) {
            return false;
        }
        if (elements.chatApp.hidden || document.hidden) {
            return false;
        }
        if (state.sessionRefreshInFlight) {
            return false;
        }
        const activeSessionId = normalizeSessionValue(state.sessionId);
        return Boolean(activeSessionId && shouldPollSession(activeSessionId));
    }

    function shouldPollSession(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return false;
        }
        if (isSessionPending(targetSessionId)) {
            return true;
        }
        const workflow = state.sessionWorkflowBySession[targetSessionId] || null;
        return Boolean(workflow && isWorkflowActive(workflow.status));
    }

    function messageListSignature(messages) {
        const list = Array.isArray(messages) ? messages : [];
        return JSON.stringify(list.map(function (message) {
            return {
                role: message && message.role,
                content: message && message.content,
                memories: stringListSignature(message && message.memories),
                fromSemanticCache: Boolean(message && message.fromSemanticCache),
                fromSemanticGuardrail: Boolean(message && message.fromSemanticGuardrail),
                pendingApproval: approvalSignature(message && message.pendingApproval),
                steps: stepListSignature(message && message.executionSteps),
                tokenUsage: tokenUsageSignature(message && message.tokenUsage)
            };
        }));
    }

    function stringListSignature(values) {
        if (!Array.isArray(values)) {
            return [];
        }

        return values.map(function (value) {
            return typeof value === "string" ? value.trim() : "";
        }).filter(Boolean);
    }

    function stepListSignature(steps) {
        if (!Array.isArray(steps)) {
            return [];
        }

        return steps.map(function (step) {
            return [
                step && step.id,
                step && step.status,
                step && step.summary,
                step && step.durationMs,
                step && step.recovered
            ];
        });
    }

    function tokenUsageSignature(tokenUsage) {
        if (!tokenUsage || typeof tokenUsage !== "object") {
            return null;
        }

        return {
            promptTokens: tokenUsage.promptTokens,
            completionTokens: tokenUsage.completionTokens,
            totalTokens: tokenUsage.totalTokens
        };
    }

    function approvalSignature(approval) {
        const pendingApproval = normalizePendingApproval(approval);
        if (!pendingApproval) {
            return null;
        }
        return {
            approvalId: pendingApproval.approvalId,
            workflowId: pendingApproval.workflowId,
            status: pendingApproval.status
        };
    }

    function applySessionWorkflowMetadata(sessionId, metadata) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return false;
        }

        const nextWorkflow = normalizeSessionWorkflow(metadata);
        const previousWorkflow = state.sessionWorkflowBySession[targetSessionId] || null;
        const changed = workflowSignature(previousWorkflow) !== workflowSignature(nextWorkflow);
        if (nextWorkflow) {
            state.sessionWorkflowBySession[targetSessionId] = nextWorkflow;
        } else {
            delete state.sessionWorkflowBySession[targetSessionId];
        }

        if (nextWorkflow && isWorkflowActive(nextWorkflow.status)) {
            setRecoverySessionLoading(targetSessionId, nextWorkflow);
        } else {
            clearRecoverySessionLoading(targetSessionId);
        }

        return changed;
    }

    function normalizeSessionWorkflow(metadata) {
        if (!metadata || typeof metadata !== "object") {
            return null;
        }

        const workflowId = cleanProgressText(metadata.latestWorkflowId);
        const status = cleanProgressText(metadata.latestWorkflowStatus).toUpperCase();
        if (!workflowId || !status) {
            return null;
        }

        return {
            workflowId: workflowId,
            status: status,
            recoveredFromWorkflowId: cleanProgressText(metadata.recoveredFromWorkflowId),
            replayCheckpointId: cleanProgressText(metadata.replayCheckpointId),
            steps: normalizeWorkflowSteps(metadata.latestWorkflowSteps, metadata)
        };
    }

    function workflowSignature(workflow) {
        if (!workflow) {
            return "";
        }
        return JSON.stringify({
            workflowId: workflow.workflowId,
            status: workflow.status,
            recoveredFromWorkflowId: workflow.recoveredFromWorkflowId,
            replayCheckpointId: workflow.replayCheckpointId,
            steps: stepListSignature(workflow.steps)
        });
    }

    function isWorkflowActive(status) {
        return status === "RUNNING" || status === "RECOVERING";
    }

    function setRecoverySessionLoading(sessionId, workflow) {
        state.pendingSessions[sessionId] = true;
        const steps = workflow.steps.length > 0 ? workflow.steps : [recoveryFallbackStep(workflow)];
        setSessionProgress(sessionId, steps);
        syncLoadingState();
        renderSessions();
    }

    function normalizeWorkflowSteps(steps, metadata) {
        if (!Array.isArray(steps)) {
            return [];
        }

        const recoveredFromWorkflowId = cleanProgressText(metadata && metadata.recoveredFromWorkflowId);
        const replayCheckpointId = cleanProgressText(metadata && metadata.replayCheckpointId);
        const checkpointStepId = checkpointedStepId(replayCheckpointId);
        let inferRecovered = Boolean(
            recoveredFromWorkflowId
            && checkpointStepId
            && containsCheckpointBoundary(steps, checkpointStepId)
        );
        let allowCheckpointEvent = false;

        return steps.map(function (step) {
            const normalizedStep = normalizeProgressStep({
                id: step && step.id,
                label: step && step.label,
                kind: step && step.kind,
                actorType: step && step.actorType,
                actorName: step && step.actorName,
                status: step && step.status,
                summary: step && step.summary,
                durationMs: step && step.durationMs,
                recovered: step && step.recovered
            });

            if (!normalizedStep) {
                return null;
            }

            const inferredRecovered = inferRecoveredForStep(normalizedStep, checkpointStepId, {
                inferRecovered: inferRecovered,
                allowCheckpointEvent: allowCheckpointEvent
            });
            inferRecovered = inferredRecovered.inferRecovered;
            allowCheckpointEvent = inferredRecovered.allowCheckpointEvent;

            if (inferredRecovered.recovered) {
                return Object.assign({}, normalizedStep, { recovered: true });
            }

            return normalizedStep;
        }).filter(function (message) {
            return Boolean(message) && !isApprovalHidden(message.pendingApproval);
        });
    }

    function inferRecoveredForStep(step, checkpointStepId, stateValue) {
        if (!stateValue.inferRecovered) {
            return {
                recovered: false,
                inferRecovered: false,
                allowCheckpointEvent: false
            };
        }

        if (stateValue.allowCheckpointEvent) {
            if (isCheckpointForStep(step, checkpointStepId)) {
                return {
                    recovered: true,
                    inferRecovered: false,
                    allowCheckpointEvent: false
                };
            }
            return {
                recovered: false,
                inferRecovered: false,
                allowCheckpointEvent: false
            };
        }

        if (isCheckpointForStep(step, checkpointStepId)) {
            return {
                recovered: true,
                inferRecovered: false,
                allowCheckpointEvent: false
            };
        }

        if (step.id === checkpointStepId && step.status === "completed") {
            return {
                recovered: true,
                inferRecovered: true,
                allowCheckpointEvent: true
            };
        }

        return {
            recovered: true,
            inferRecovered: true,
            allowCheckpointEvent: false
        };
    }

    function checkpointedStepId(checkpointId) {
        const value = cleanProgressText(checkpointId);
        if (!value) {
            return "";
        }

        const lastSeparator = value.lastIndexOf(":");
        if (lastSeparator < 0) {
            return value;
        }

        const actorSeparator = value.lastIndexOf(":", lastSeparator - 1);
        if (actorSeparator < 0) {
            return value;
        }

        return value.slice(0, actorSeparator);
    }

    function containsCheckpointBoundary(steps, checkpointStepId) {
        return steps.some(function (step) {
            return isCheckpointBoundaryStep({
                id: cleanProgressText(step && step.id),
                kind: cleanProgressText(step && step.kind),
                status: normalizeProgressStatus(step && step.status),
                summary: cleanProgressText(step && step.summary)
            }, checkpointStepId);
        });
    }

    function isCheckpointForStep(step, checkpointStepId) {
        if (!step || !checkpointStepId) {
            return false;
        }
        return isCheckpointBoundaryStep(step, checkpointStepId) && step.kind === "checkpoint";
    }

    function isCheckpointBoundaryStep(step, checkpointStepId) {
        if (!step || !checkpointStepId) {
            return false;
        }
        if (step.id === checkpointStepId) {
            return step.status === "completed";
        }
        if (step.id === "checkpoint:" + checkpointStepId) {
            return true;
        }
        return step.kind === "checkpoint" && cleanProgressText(step.summary).includes(checkpointStepId);
    }

    function recoveryFallbackStep(workflow) {
        return normalizeProgressStep({
            id: "WORKFLOW_RECOVERY",
            label: "Recovering workflow",
            kind: "system",
            actorType: "system",
            actorName: "system",
            status: "running",
            summary: "Recovering workflow " + workflow.workflowId + " from Redis state."
        });
    }

    function setSessionProgress(sessionId, steps) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        ensureSessionProgressStarted(targetSessionId);
        const currentSteps = sessionProgress(targetSessionId);
        const currentByKey = new Map();
        currentSteps.forEach(function (step) {
            const key = progressStepKey(step);
            if (key) {
                currentByKey.set(key, step);
            }
        });
        const now = Date.now();
        const nextKeys = new Set();
        const recoveredStepIds = new Set();
        steps.forEach(function (step) {
            if (step && step.recovered === true && step.id) {
                recoveredStepIds.add(step.id);
            }
        });
        const nextSteps = steps.map(function (step) {
            const key = progressStepKey(step);
            const existingStep = currentByKey.get(key);
            if (key) {
                nextKeys.add(key);
            }
            return Object.assign({}, step, {
                startedAt: existingStep && Number.isFinite(existingStep.startedAt) ? existingStep.startedAt : now,
                updatedAt: now
            });
        });
        currentSteps.forEach(function (step) {
            const key = progressStepKey(step);
            if (!key || nextKeys.has(key)) {
                return;
            }
            if (step.recovered !== true && recoveredStepIds.has(step.id)) {
                return;
            }
            if (step.id === "WORKFLOW_RECOVERY" && steps.length > 0) {
                return;
            }
            if (step.id === "REQUEST_WAITING_FOR_RECOVERY" && steps.length > 0) {
                return;
            }
            nextSteps.push(step);
        });

        state.pendingProgressBySession[targetSessionId] = nextSteps;

        if (targetSessionId === state.sessionId) {
            renderMessages();
        }
    }

    function clearRecoverySessionLoading(sessionId) {
        delete state.pendingSessions[sessionId];
        clearSessionProgress(sessionId);
        syncLoadingState();
        renderSessions();
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

    function mergeSessionMessages(currentMessages, nextMessages) {
        const current = Array.isArray(currentMessages) ? currentMessages : [];
        const next = Array.isArray(nextMessages) ? nextMessages : [];
        const usedCurrentIndexes = new Set();

        return next.map(function (nextMessage, index) {
            const localMessage = matchingLocalMessage(current, nextMessage, index, usedCurrentIndexes);
            return mergeMessageState(localMessage, nextMessage);
        });
    }

    function matchingLocalMessage(messages, nextMessage, index, usedIndexes) {
        const indexedMessage = messages[index];
        if (isSameChatMessage(indexedMessage, nextMessage)) {
            usedIndexes.add(index);
            return indexedMessage;
        }

        const foundIndex = messages.findIndex(function (message, candidateIndex) {
            return !usedIndexes.has(candidateIndex) && isSameChatMessage(message, nextMessage);
        });
        if (foundIndex < 0) {
            return null;
        }

        usedIndexes.add(foundIndex);
        return messages[foundIndex];
    }

    function isSameChatMessage(left, right) {
        return Boolean(left && right && left.role === right.role && left.content === right.content);
    }

    function mergeMessageState(localMessage, nextMessage) {
        if (!localMessage) {
            return nextMessage;
        }

        const localMemories = Array.isArray(localMessage.memories) ? localMessage.memories : [];
        const nextMemories = Array.isArray(nextMessage.memories) ? nextMessage.memories : [];
        const localExecutionSteps = Array.isArray(localMessage.executionSteps) ? localMessage.executionSteps : [];
        const nextExecutionSteps = Array.isArray(nextMessage.executionSteps) ? nextMessage.executionSteps : [];
        const localActivitySteps = Array.isArray(localMessage.activitySteps) ? localMessage.activitySteps : [];
        const nextActivitySteps = Array.isArray(nextMessage.activitySteps) ? nextMessage.activitySteps : [];
        const nextApproval = normalizePendingApproval(nextMessage.pendingApproval);
        const localApproval = normalizePendingApproval(localMessage.pendingApproval);

        return Object.assign({}, nextMessage, {
            memories: nextMemories.length > 0 ? nextMemories : localMemories,
            fromSemanticCache: Boolean(nextMessage.fromSemanticCache || localMessage.fromSemanticCache),
            fromSemanticGuardrail: Boolean(nextMessage.fromSemanticGuardrail || localMessage.fromSemanticGuardrail),
            tokenUsage: nextMessage.tokenUsage || localMessage.tokenUsage || null,
            responseTimeMs: nextMessage.responseTimeMs != null ? nextMessage.responseTimeMs : localMessage.responseTimeMs,
            executionSteps: nextExecutionSteps.length > 0 ? nextExecutionSteps : localExecutionSteps,
            activitySteps: nextActivitySteps.length > 0 ? nextActivitySteps : localActivitySteps,
            pendingApproval: nextApproval || localApproval
        });
    }

    function renderMessages() {
        const stickToBottom = shouldStickToBottom();
        elements.messages.replaceChildren();

        const visibleMessages = state.messages.filter(function (message) {
            return !isApprovalHidden(message.pendingApproval);
        });

        if (visibleMessages.length === 0 && !state.loading) {
            elements.messages.appendChild(buildEmptyState());
            return;
        }

        for (const message of visibleMessages) {
            elements.messages.appendChild(buildMessage(message));
        }

        if (state.loading) {
            elements.messages.appendChild(buildTypingIndicator(sessionProgress(state.sessionId), state.sessionId));
        }

        if (stickToBottom) {
            scrollMessagesToBottom();
        }
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
        renderApprovalTools();
        renderRateLimitStatus();
        renderProviderUsage();
        renderCircuitBreakers();
        if (state.memoriesOpen) {
            renderMemories();
        }
    }

    function renderRateLimitStatus() {
        const enabled = isRateLimitingEnabled();
        const limit = normalizeRateLimitValue(state.rateLimitLimit, defaultRateLimitLimit);
        const remaining = Math.min(limit, normalizeRateLimitValue(state.rateLimitRemaining, limit));
        elements.rateLimitStatus.classList.toggle("is-off", !enabled);
        elements.rateLimitStatusValue.textContent = enabled ? remaining + " / " + limit : "Off";
    }

    function renderApprovalTools() {
        if (!elements.requireApprovalTools) {
            return;
        }
        elements.requireApprovalTools.replaceChildren();
        approvalToolOptions.forEach(function (option) {
            const button = document.createElement("button");
            const enabled = isApprovalToolRequired(option.toolName);
            button.type = "button";
            button.className = "approval-tool" + (enabled ? "" : " is-off");
            button.dataset.approvalTool = option.toolName;
            button.setAttribute("aria-pressed", String(enabled));
            button.textContent = option.label;
            elements.requireApprovalTools.appendChild(button);
        });
    }

    function renderProviderUsage() {
        elements.secApiHitsValue.textContent = String(state.providerUsage.secApiHits);
        elements.tavilyApiHitsValue.textContent = String(state.providerUsage.tavilyApiHits);
        elements.twelveDataApiHitsValue.textContent = String(state.providerUsage.twelveDataApiHits);
    }

    function renderCircuitBreakers() {
        const statuses = normalizeCircuitBreakerStatuses(state.circuitBreakers);
        elements.circuitBreakerList.replaceChildren();
        elements.refreshCircuitBreakersButton.disabled = state.circuitBreakersLoading;

        for (const status of statuses) {
            elements.circuitBreakerList.appendChild(buildCircuitBreakerRow(status));
        }
        renderProviderDeadLetter();
    }

    function buildCircuitBreakerRow(status) {
        const providerId = normalizeProviderId(status.providerId);
        const breaker = status.circuitBreaker || {};
        const capacity = status.capacity || {};
        const stateName = normalizeCircuitState(breaker.state);
        const row = document.createElement("div");
        row.className = "circuit-provider";
        row.classList.toggle("is-open", stateName === "OPEN");
        row.classList.toggle("is-half-open", stateName === "HALF_OPEN");
        row.classList.toggle("is-simulated", Boolean(status.failureSimulationEnabled));
        row.classList.toggle("is-slow", normalizeCount(status.latencySimulationMs) > 0);
        row.setAttribute("role", "listitem");

        const header = document.createElement("div");
        header.className = "circuit-provider__header";

        const name = document.createElement("span");
        name.className = "circuit-provider__name";
        name.textContent = status.label || formatProviderLabel(providerId);
        header.appendChild(name);

        const badge = document.createElement("span");
        badge.className = "circuit-provider__state circuit-provider__state--" + stateName.toLowerCase().replace("_", "-");
        badge.textContent = formatCircuitState(stateName);
        header.appendChild(badge);
        row.appendChild(header);

        const meta = document.createElement("div");
        meta.className = "circuit-provider__meta";
        meta.appendChild(circuitMetaItem("Failures", String(normalizeCount(breaker.failureCount))));
        meta.appendChild(circuitMetaItem("Blocked", String(normalizeCount(breaker.blockedCallCount))));
        meta.appendChild(circuitMetaItem("Active", formatCapacityActive(capacity)));
        meta.appendChild(circuitMetaItem("Waiting", String(normalizeCount(capacity.waitingCount))));
        meta.appendChild(circuitMetaItem("Timeouts", String(normalizeCount(capacity.timeoutCount))));
        const openUntil = formatTimestamp(breaker.openUntil);
        if (openUntil) {
            meta.appendChild(circuitMetaItem("Retry", openUntil));
        }
        row.appendChild(meta);

        const toggle = document.createElement("button");
        toggle.className = "identity-toggle circuit-provider__toggle";
        toggle.type = "button";
        toggle.dataset.circuitProvider = providerId;
        toggle.setAttribute("aria-pressed", String(Boolean(status.failureSimulationEnabled)));
        toggle.classList.toggle("is-off", !status.failureSimulationEnabled);
        toggle.innerHTML = ""
            + "<span class=\"identity-toggle__text\">Outage</span>"
            + "<span class=\"identity-toggle__state\"></span>"
            + "<span class=\"identity-toggle__switch\" aria-hidden=\"true\"><span class=\"identity-toggle__knob\"></span></span>";
        const toggleState = toggle.querySelector(".identity-toggle__state");
        if (toggleState) {
            toggleState.textContent = status.failureSimulationEnabled ? "On" : "Off";
        }
        toggle.title = (status.failureSimulationEnabled ? "Disable" : "Enable") + " simulated outage for " + (status.label || providerId);
        row.appendChild(toggle);

        const latencyToggle = document.createElement("button");
        latencyToggle.className = "identity-toggle circuit-provider__toggle circuit-provider__toggle--slow";
        latencyToggle.type = "button";
        latencyToggle.dataset.latencyProvider = providerId;
        const latencyEnabled = normalizeCount(status.latencySimulationMs) > 0;
        latencyToggle.setAttribute("aria-pressed", String(latencyEnabled));
        latencyToggle.classList.toggle("is-off", !latencyEnabled);
        latencyToggle.innerHTML = ""
            + "<span class=\"identity-toggle__text\">Slow</span>"
            + "<span class=\"identity-toggle__state\"></span>"
            + "<span class=\"identity-toggle__switch\" aria-hidden=\"true\"><span class=\"identity-toggle__knob\"></span></span>";
        const latencyState = latencyToggle.querySelector(".identity-toggle__state");
        if (latencyState) {
            latencyState.textContent = latencyEnabled ? formatDurationMs(status.latencySimulationMs) : "Off";
        }
        latencyToggle.title = (latencyEnabled ? "Disable" : "Enable") + " simulated latency for " + (status.label || providerId);
        row.appendChild(latencyToggle);

        const approvalTools = approvalToolsForProvider(providerId);
        if (approvalTools.length > 0) {
            const approvalToggle = document.createElement("button");
            const approvalState = providerApprovalState(approvalTools);
            approvalToggle.className = "identity-toggle circuit-provider__toggle circuit-provider__toggle--approval";
            approvalToggle.type = "button";
            approvalToggle.dataset.approvalProvider = providerId;
            approvalToggle.setAttribute("aria-pressed", approvalState === "partial" ? "mixed" : String(approvalState === "on"));
            approvalToggle.classList.toggle("is-off", approvalState === "off");
            approvalToggle.classList.toggle("is-on", approvalState === "on");
            approvalToggle.classList.toggle("is-partial", approvalState === "partial");
            approvalToggle.innerHTML = ""
                + "<span class=\"identity-toggle__text\">Require approval</span>"
                + "<span class=\"identity-toggle__state\"></span>"
                + "<span class=\"identity-toggle__switch\" aria-hidden=\"true\"><span class=\"identity-toggle__knob\"></span></span>";
            const approvalStateElement = approvalToggle.querySelector(".identity-toggle__state");
            if (approvalStateElement) {
                approvalStateElement.textContent = formatProviderApprovalState(approvalState);
            }
            approvalToggle.title = providerApprovalTitle(status.label || providerId, approvalTools, approvalState);
            row.appendChild(approvalToggle);
        }

        return row;
    }

    function renderProviderDeadLetter() {
        const failures = normalizeProviderDeadLetter(state.providerDeadLetter);
        elements.providerDeadLetterCount.textContent = String(failures.length);
        elements.providerDeadLetterList.replaceChildren();

        if (failures.length === 0) {
            const empty = document.createElement("p");
            empty.className = "provider-dead-letter__empty";
            empty.textContent = "No failed provider steps.";
            elements.providerDeadLetterList.appendChild(empty);
            return;
        }

        for (const failure of failures.slice(0, 5)) {
            elements.providerDeadLetterList.appendChild(buildProviderFailureRow(failure));
        }
    }

    function buildProviderFailureRow(failure) {
        const row = document.createElement("div");
        row.className = "provider-dead-letter__row";

        const title = document.createElement("div");
        title.className = "provider-dead-letter__title";
        title.textContent = (failure.providerLabel || formatProviderLabel(failure.providerId))
            + " failed after "
            + failure.attempts
            + " attempt"
            + (failure.attempts === 1 ? "" : "s");
        row.appendChild(title);

        const reason = document.createElement("div");
        reason.className = "provider-dead-letter__reason";
        reason.textContent = failure.reason || "Provider call failed.";
        row.appendChild(reason);

        const meta = document.createElement("div");
        meta.className = "provider-dead-letter__meta";
        meta.textContent = [failure.stepId, formatTimestamp(failure.failedAt)].filter(Boolean).join(" | ");
        row.appendChild(meta);

        return row;
    }

    function circuitMetaItem(label, value) {
        const item = document.createElement("span");
        item.className = "circuit-provider__meta-item";
        item.textContent = label + " " + value;
        return item;
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
        renderMemoryTabs();

        if (!isSessionManagementEnabled()) {
            state.memories = [];
            elements.memoriesList.replaceChildren();
            elements.memoriesEmpty.hidden = true;
            elements.memoryTextInput.disabled = true;
            elements.memoryTypeInput.disabled = true;
            elements.memoryTopicsInput.disabled = true;
            elements.createMemoryButton.disabled = true;
            elements.addMemoryButton.disabled = true;
            return;
        }

        const allMemories = normalizeMemories(state.memories);
        const memories = visibleMemories(allMemories);
        elements.memoriesList.replaceChildren();

        for (const memory of memories) {
            elements.memoriesList.appendChild(buildMemoryListItem(memory));
        }

        elements.memoriesEmpty.textContent = emptyMemoriesLabel();
        elements.memoriesEmpty.hidden = memories.length > 0 || state.memoriesLoading;
        elements.flushMemoriesButton.disabled = state.memoriesLoading || allMemories.length === 0;
        elements.memoryTextInput.disabled = state.memoriesLoading;
        elements.memoryTypeInput.disabled = state.memoriesLoading;
        elements.memoryTopicsInput.disabled = state.memoriesLoading;
        elements.createMemoryButton.disabled = state.memoriesLoading;
        elements.addMemoryButton.disabled = state.memoriesLoading;
    }

    function renderMemoryTabs() {
        state.memoryTab = normalizeMemoryTab(state.memoryTab);
        elements.memoryTabButtons.forEach(function (button) {
            const isActive = normalizeMemoryTab(button.dataset.memoryTab) === state.memoryTab;
            button.classList.toggle("is-active", isActive);
            button.setAttribute("aria-selected", String(isActive));
        });
    }

    function normalizeMemoryTab(tab) {
        return tab === memoryTabCurrentChat ? memoryTabCurrentChat : memoryTabAll;
    }

    function visibleMemories(memories) {
        if (state.memoryTab !== memoryTabCurrentChat) {
            return memories;
        }

        const currentSessionId = normalizeSessionValue(state.sessionId);
        if (!currentSessionId) {
            return [];
        }

        return memories.filter(function (memory) {
            return memoryBelongsToCurrentChat(memory, currentSessionId);
        });
    }

    function memoryBelongsToCurrentChat(memory, currentSessionId) {
        const memorySessionId = normalizeSessionValue(memory && memory.sessionId);
        if (!memorySessionId) {
            return false;
        }

        if (memorySessionId === currentSessionId) {
            return true;
        }

        const currentUserId = activeUserId();
        return Boolean(currentUserId) && memorySessionId === currentUserId + ":" + currentSessionId;
    }

    function emptyMemoriesLabel() {
        if (state.memoryTab === memoryTabCurrentChat) {
            return "No memories extracted from this chat yet.";
        }

        return "No long term memories yet.";
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
        term.dataset.label = label;
        const detail = document.createElement("dd");
        detail.textContent = normalizedValue;
        detail.dataset.label = label;
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
                    rateLimitingEnabled: isRateLimitingEnabled(),
                    requireApprovalEnabled: isRequireApprovalEnabled(),
                    approvalRequiredTools: approvalRequiredToolsPayload()
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
        state.approvalRequiredTools = normalizeApprovalRequiredTools(
            context.approvalRequiredTools,
            context.requireApprovalEnabled
        );
        state.rateLimitLimit = normalizeRateLimitValue(context.rateLimitLimit, defaultRateLimitLimit);
        state.rateLimitRemaining = Math.min(
            state.rateLimitLimit,
            normalizeRateLimitValue(context.rateLimitRemaining, state.rateLimitLimit)
        );
        applyProviderUsage(context.providerUsage);
        state.sessionManagementEnabled = context.sessionManagementEnabled !== false;
        applySessionManagementState();
        renderCircuitBreakers();
        syncCircuitBreakerRefreshLoop();
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

    function defaultCircuitBreakers() {
        return circuitBreakerProviders.map(function (provider) {
            return {
                providerId: provider.providerId,
                label: provider.label,
                failureSimulationEnabled: false,
                latencySimulationMs: 0,
                circuitBreaker: {
                    providerId: provider.providerId,
                    state: "CLOSED",
                    failureCount: 0,
                    blockedCallCount: 0,
                    callsAllowed: true
                },
                capacity: {
                    providerId: provider.providerId,
                    limit: 2,
                    activeCount: 0,
                    waitingCount: 0,
                    timeoutCount: 0,
                    capacityAvailable: true
                }
            };
        });
    }

    function normalizeCircuitBreakerStatuses(statuses) {
        const byProvider = {};
        const list = Array.isArray(statuses) ? statuses : [];
        for (const status of list) {
            const normalized = normalizeCircuitBreakerStatus(status);
            if (normalized) {
                byProvider[normalized.providerId] = normalized;
            }
        }

        return circuitBreakerProviders.map(function (provider) {
            return byProvider[provider.providerId] || normalizeCircuitBreakerStatus(provider);
        }).filter(Boolean);
    }

    function normalizeCircuitBreakerStatus(status) {
        if (!status || typeof status !== "object") {
            return null;
        }

        const providerId = normalizeProviderId(status.providerId);
        if (!providerId) {
            return null;
        }

        const breaker = status.circuitBreaker && typeof status.circuitBreaker === "object"
            ? status.circuitBreaker
            : {};
        return {
            providerId,
            label: typeof status.label === "string" && status.label.trim()
                ? status.label.trim()
                : formatProviderLabel(providerId),
            failureSimulationEnabled: Boolean(status.failureSimulationEnabled),
            latencySimulationMs: normalizeCount(status.latencySimulationMs),
            circuitBreaker: {
                providerId,
                state: normalizeCircuitState(breaker.state),
                failureCount: normalizeCount(breaker.failureCount),
                blockedCallCount: normalizeCount(breaker.blockedCallCount),
                openedAt: breaker.openedAt || null,
                openUntil: breaker.openUntil || null,
                halfOpenAt: breaker.halfOpenAt || null,
                lastFailureAt: breaker.lastFailureAt || null,
                lastSuccessAt: breaker.lastSuccessAt || null,
                reason: typeof breaker.reason === "string" ? breaker.reason : "",
                callsAllowed: breaker.callsAllowed !== false
            },
            capacity: normalizeProviderCapacity(providerId, status.capacity)
        };
    }

    function normalizeProviderCapacity(providerId, capacity) {
        const raw = capacity && typeof capacity === "object" ? capacity : {};
        const limit = normalizePositiveCount(raw.limit, 2);
        const activeCount = normalizeCount(raw.activeCount);
        return {
            providerId,
            limit,
            activeCount,
            waitingCount: normalizeCount(raw.waitingCount),
            timeoutCount: normalizeCount(raw.timeoutCount),
            capacityAvailable: raw.capacityAvailable !== false && activeCount < limit
        };
    }

    function normalizeProviderDeadLetter(records) {
        return (Array.isArray(records) ? records : [])
            .map(normalizeProviderFailure)
            .filter(Boolean);
    }

    function normalizeProviderFailure(record) {
        if (!record || typeof record !== "object") {
            return null;
        }
        return {
            failureId: textValue(record.failureId),
            workflowId: textValue(record.workflowId),
            stepId: textValue(record.stepId),
            providerId: normalizeProviderId(record.providerId),
            providerLabel: textValue(record.providerLabel),
            cacheName: textValue(record.cacheName),
            cacheKey: textValue(record.cacheKey),
            attempts: normalizeCount(record.attempts),
            reason: textValue(record.reason),
            failedAt: textValue(record.failedAt)
        };
    }

    function textValue(value) {
        return value === null || value === undefined ? "" : String(value);
    }

    function formatCapacityActive(capacity) {
        return normalizeCount(capacity && capacity.activeCount) + "/" + normalizePositiveCount(capacity && capacity.limit, 2);
    }

    function formatDurationMs(value) {
        const ms = normalizeCount(value);
        if (ms >= 1000 && ms % 1000 === 0) {
            return (ms / 1000) + "s";
        }
        return ms + "ms";
    }

    function mergeCircuitBreakerStatus(status) {
        const normalized = normalizeCircuitBreakerStatus(status);
        if (!normalized) {
            return;
        }

        const statuses = normalizeCircuitBreakerStatuses(state.circuitBreakers);
        const index = statuses.findIndex(function (existing) {
            return existing.providerId === normalized.providerId;
        });
        if (index >= 0) {
            statuses[index] = normalized;
        } else {
            statuses.push(normalized);
        }
        state.circuitBreakers = statuses;
    }

    function circuitBreakerByProvider(providerId) {
        const normalizedProvider = normalizeProviderId(providerId);
        return normalizeCircuitBreakerStatuses(state.circuitBreakers).find(function (status) {
            return status.providerId === normalizedProvider;
        });
    }

    function normalizeProviderId(providerId) {
        return typeof providerId === "string"
            ? providerId.trim().toLowerCase().replace(/[^a-z0-9_.-]+/g, "_")
            : "";
    }

    function normalizeCircuitState(value) {
        const stateName = typeof value === "string" ? value.trim().toUpperCase() : "";
        if (stateName === "OPEN" || stateName === "HALF_OPEN") {
            return stateName;
        }
        return "CLOSED";
    }

    function normalizeCount(value) {
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
    }

    function normalizePositiveCount(value, fallback) {
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
    }

    function formatCircuitState(value) {
        const stateName = normalizeCircuitState(value);
        if (stateName === "HALF_OPEN") {
            return "Half open";
        }
        return stateName.charAt(0) + stateName.slice(1).toLowerCase();
    }

    function formatProviderLabel(providerId) {
        const normalized = normalizeProviderId(providerId);
        const configured = circuitBreakerProviders.find(function (provider) {
            return provider.providerId === normalized;
        });
        return configured ? configured.label : normalized;
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
        elements.mobileSessionsButton.disabled = !enabled;
        elements.mobileNewSessionButton.disabled = !enabled;
        elements.refreshSessionsButton.disabled = state.sessionsRefreshing || !enabled;
        elements.memoriesButton.disabled = !enabled;
        if (!enabled) {
            closeMobileSessions();
        }
        syncSessionRefreshLoop();
    }

    function handleUnauthorizedResponse(response) {
        if (!response || response.status !== 401) {
            return false;
        }

        stopSessionRefreshLoop();
        stopCircuitBreakerRefreshLoop();
        state.userId = null;
        state.sessionId = null;
        state.messages = [];
        state.messagesBySession = {};
        state.hiddenApprovalIds = new Set();
        state.sessionWorkflowBySession = {};
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
        state.approvalRequiredTools = [];
        state.circuitBreakers = defaultCircuitBreakers();
        resetRateLimitStatus();
        clearStoredSession();
        showLogin();
        return true;
    }

    function syncSessionRefreshLoop() {
        if (isLoggedIn() && isSessionManagementEnabled() && !elements.chatApp.hidden && !document.hidden) {
            startSessionRefreshLoop();
        } else {
            stopSessionRefreshLoop();
        }
    }

    async function refreshCircuitBreakers(reportStatus) {
        if (!isLoggedIn() || state.circuitBreakerRefreshInFlight || elements.chatApp.hidden || document.hidden) {
            return;
        }

        state.circuitBreakerRefreshInFlight = true;
        state.circuitBreakersLoading = true;
        renderCircuitBreakers();
        try {
            const results = await Promise.all([
                fetchCircuitBreakers(),
                fetchProviderDeadLetter()
            ]);
            state.circuitBreakers = normalizeCircuitBreakerStatuses(results[0]);
            state.providerDeadLetter = normalizeProviderDeadLetter(results[1]);
            renderCircuitBreakers();
            if (reportStatus) {
                setStatus("Provider health refreshed");
            }
        } catch (error) {
            if (reportStatus) {
                setStatus("Provider health failed", "error");
            }
        } finally {
            state.circuitBreakerRefreshInFlight = false;
            state.circuitBreakersLoading = false;
            renderCircuitBreakers();
        }
    }

    function syncCircuitBreakerRefreshLoop() {
        if (isLoggedIn() && !elements.chatApp.hidden && !document.hidden) {
            startCircuitBreakerRefreshLoop();
        } else {
            stopCircuitBreakerRefreshLoop();
        }
    }

    function startCircuitBreakerRefreshLoop() {
        if (state.circuitBreakerRefreshTimer) {
            return;
        }

        state.circuitBreakerRefreshTimer = window.setInterval(function () {
            refreshCircuitBreakers(false);
        }, circuitBreakerRefreshIntervalMs);
    }

    function stopCircuitBreakerRefreshLoop() {
        if (state.circuitBreakerRefreshTimer) {
            window.clearInterval(state.circuitBreakerRefreshTimer);
            state.circuitBreakerRefreshTimer = null;
        }
        state.circuitBreakerRefreshInFlight = false;
        state.circuitBreakersLoading = false;
    }

    function startSessionRefreshLoop() {
        if (state.sessionRefreshTimer) {
            return;
        }

        state.sessionRefreshTimer = window.setInterval(refreshActiveSessionFromServer, sessionRefreshIntervalMs);
    }

    function stopSessionRefreshLoop() {
        if (state.sessionRefreshTimer) {
            window.clearInterval(state.sessionRefreshTimer);
            state.sessionRefreshTimer = null;
        }
        state.sessionRefreshInFlight = false;
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

    function isRequireApprovalEnabled() {
        return approvalRequiredToolsPayload().length > 0;
    }

    function isApprovalToolRequired(toolName) {
        return approvalRequiredToolsPayload().includes(normalizeApprovalToolName(toolName));
    }

    function areAllApprovalToolsRequired(toolNames) {
        const normalized = normalizeApprovalRequiredTools(toolNames);
        return normalized.length > 0 && normalized.every(isApprovalToolRequired);
    }

    function areAnyApprovalToolsRequired(toolNames) {
        return normalizeApprovalRequiredTools(toolNames).some(isApprovalToolRequired);
    }

    function providerApprovalState(toolNames) {
        if (areAllApprovalToolsRequired(toolNames)) {
            return "on";
        }
        if (areAnyApprovalToolsRequired(toolNames)) {
            return "partial";
        }
        return "off";
    }

    function formatProviderApprovalState(approvalState) {
        if (approvalState === "on") {
            return "On";
        }
        if (approvalState === "partial") {
            return "Some";
        }
        return "Off";
    }

    function providerApprovalTitle(providerLabel, toolNames, approvalState) {
        const action = approvalState === "on" ? "Disable" : "Require";
        return action + " approval for " + providerLabel + ": " + approvalToolLabels(toolNames).join(", ");
    }

    function approvalToolLabels(toolNames) {
        const normalized = normalizeApprovalRequiredTools(toolNames);
        return normalized.map(function (toolName) {
            const option = approvalToolOptions.find(function (candidate) {
                return candidate.toolName === toolName;
            });
            return option ? option.label : toolName;
        });
    }

    function approvalToolsForProvider(providerId) {
        const normalizedProvider = normalizeProviderId(providerId);
        const provider = circuitBreakerProviders.find(function (candidate) {
            return candidate.providerId === normalizedProvider;
        });
        return normalizeApprovalRequiredTools(provider && provider.approvalTools);
    }

    function approvalRequiredToolsPayload() {
        return normalizeApprovalRequiredTools(state.approvalRequiredTools);
    }

    function normalizeApprovalRequiredTools(toolNames, requireApprovalEnabled) {
        const source = Array.isArray(toolNames) ? toolNames : [];
        const normalized = approvalToolOptions
            .map(function (option) {
                return option.toolName;
            })
            .filter(function (toolName) {
                return source.some(function (selectedToolName) {
                    return normalizeApprovalToolName(selectedToolName) === toolName;
                });
            });
        if (normalized.length > 0 || requireApprovalEnabled !== true) {
            return normalized;
        }
        return approvalToolOptions.map(function (option) {
            return option.toolName;
        });
    }

    function normalizeApprovalToolName(toolName) {
        const value = typeof toolName === "string" ? toolName.trim() : "";
        const option = approvalToolOptions.find(function (candidate) {
            return candidate.toolName === value;
        });
        return option ? option.toolName : "";
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
        const approvalActions = buildApprovalActions(message);
        if (approvalActions) {
            const approvalArticle = document.createElement("article");
            approvalArticle.className = "message message--assistant message--approval-only";
            approvalArticle.appendChild(approvalActions);
            return approvalArticle;
        }

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
        const responseTimeMs = message.role === "assistant" ? resolveMessageResponseTime(message) : null;
        if (message.fromSemanticGuardrail || message.fromSemanticCache || responseTimeMs != null || tokenUsage) {
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

            if (responseTimeMs != null) {
                const durationBadge = document.createElement("span");
                durationBadge.className = "badge badge--timing";
                durationBadge.textContent = formatTimeSpentBadge(responseTimeMs);
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

    function buildApprovalActions(message) {
        if (!message || message.role !== "assistant") {
            return null;
        }
        const approval = normalizePendingApproval(message.pendingApproval);
        if (!approval) {
            return null;
        }

        const wrapper = document.createElement("div");
        wrapper.className = "message__approval";

        const label = document.createElement("div");
        label.className = "message__approval-label";
        label.textContent = approvalLabel(approval);
        wrapper.appendChild(label);

        const actions = document.createElement("div");
        actions.className = "message__approval-actions";

        if (approval.status === "PENDING") {
            actions.appendChild(buildApprovalButton("approve", "Approve", approval));
            actions.appendChild(buildApprovalButton("reject", "Reject", approval));
        } else {
            const status = document.createElement("span");
            status.className = "badge badge--approval";
            status.textContent = approval.status === "APPROVED" ? "Approved" : "Rejected";
            actions.appendChild(status);
        }

        wrapper.appendChild(actions);
        return wrapper;
    }

    function buildApprovalButton(action, label, approval) {
        const button = document.createElement("button");
        button.className = "btn " + (action === "approve" ? "btn-primary" : "btn-secondary") + " message__approval-button";
        button.type = "button";
        button.dataset.approvalAction = action;
        button.dataset.approvalId = approval.approvalId;
        button.dataset.workflowId = approval.workflowId;
        button.dataset.sessionId = approval.sessionId || state.sessionId || "";
        button.textContent = label;
        return button;
    }

    function approvalLabel(approval) {
        const tool = approval.toolName || "tool";
        const ticker = approval.ticker ? " for " + approval.ticker : "";
        return "Approval required before running " + tool + ticker + ".";
    }

    function buildSupplementPanels(message) {
        const panels = [];
        const memories = Array.isArray(message.memories) ? message.memories : [];
        const hasExecutionMetadata = Array.isArray(message.executionSteps);
        const executionSteps = hasExecutionMetadata ? message.executionSteps : [];
        const activitySteps = Array.isArray(message.activitySteps) ? message.activitySteps : [];

        if (memories.length > 0) {
            panels.push(buildDisclosurePanel("Retrieved memories", memories, function (memory) {
                const item = document.createElement("li");
                item.textContent = memory;
                return item;
            }));
        }

        if (message.role === "assistant" && activitySteps.length > 0) {
            panels.push(buildActivityDisclosurePanel(activitySteps, message.responseTimeMs));
        } else if (message.role === "assistant" && hasExecutionMetadata) {
            panels.push(buildExecutionDisclosurePanel(executionSteps, message.responseTimeMs));
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

        const actor = formatStepActor(step);
        if (actor) {
            const actorBadge = document.createElement("span");
            actorBadge.className = "message__step-actor";
            actorBadge.textContent = actor;
            heading.appendChild(actorBadge);
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

    function buildExecutionDisclosurePanel(executionSteps, responseTimeMs) {
        const steps = Array.isArray(executionSteps) ? executionSteps : [];
        const durationMs = Number.isFinite(responseTimeMs) ? responseTimeMs : sumStepDurations(steps);
        const tokenUsage = sumStepTokenUsage(steps);
        const panel = buildDisclosurePanel(
            formatWorkSummaryLabel(durationMs, tokenUsage, "Worked"),
            buildExecutionItems(steps),
            renderExecutionBreakdownItem,
            "No activity recorded.",
            "message__disclosure-list--steps"
        );
        panel.classList.add("message__disclosure--activity");
        return panel;
    }

    function buildActivityDisclosurePanel(activitySteps, responseTimeMs) {
        const steps = visibleProgressSteps(activitySteps);
        const durationMs = Number.isFinite(responseTimeMs) ? responseTimeMs : sumStepDurations(steps);
        const tokenUsage = sumStepTokenUsage(steps);
        const panel = buildDisclosureShell(formatWorkSummaryLabel(durationMs, tokenUsage, "Worked"), steps.length);
        panel.classList.add("message__disclosure--activity");

        if (steps.length === 0) {
            const empty = document.createElement("p");
            empty.className = "message__disclosure-empty";
            empty.textContent = "No activity recorded.";
            panel.appendChild(empty);
            return panel;
        }

        const content = document.createElement("div");
        content.className = "message__content message__activity-content";
        content.appendChild(buildLiveActivityHeadline(steps));
        content.appendChild(buildLiveActivityList(steps));
        panel.appendChild(content);

        return panel;
    }

    function buildDisclosureShell(title, countValue) {
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
        count.textContent = String(countValue);
        summary.appendChild(count);

        wrapper.appendChild(summary);
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

    function buildTypingIndicator(progressSteps, sessionId) {
        const article = document.createElement("article");
        article.className = "message message--assistant message--activity";

        const header = document.createElement("div");
        header.className = "message__header";

        const role = document.createElement("span");
        role.className = "message__role";
        role.textContent = "Agent";

        const timestamp = document.createElement("span");
        timestamp.className = "message__timestamp";
        timestamp.textContent = formatLiveWorkedLabel(sessionId, progressSteps);
        timestamp.dataset.progressSessionId = normalizeSessionValue(sessionId) || "";

        header.append(role, timestamp);
        article.appendChild(header);

        const content = document.createElement("div");
        content.className = "message__content message__activity-content";

        const steps = Array.isArray(progressSteps) ? progressSteps : [];
        if (steps.length > 0) {
            const visibleSteps = visibleProgressSteps(steps);
            const workflow = state.sessionWorkflowBySession[normalizeSessionValue(sessionId)] || null;
            const workflowBadges = workflow ? buildWorkflowBadges(workflow) : null;
            if (workflowBadges) {
                content.appendChild(workflowBadges);
            }
            content.appendChild(buildLiveActivityHeadline(visibleSteps));
            content.appendChild(buildLiveActivityList(visibleSteps));
        } else {
            const dots = document.createElement("div");
            dots.className = "typing-indicator";
            dots.innerHTML = "<span></span><span></span><span></span>";
            content.appendChild(dots);
        }
        article.appendChild(content);

        return article;
    }

    function buildWorkflowBadges(workflow) {
        const wrapper = document.createElement("div");
        wrapper.className = "workflow-badges";

        const recoveredFrom = cleanProgressText(workflow && workflow.recoveredFromWorkflowId);
        if (recoveredFrom) {
            appendWorkflowBadge(wrapper, "Recovered from: " + recoveredFrom, "source");
        }

        return wrapper.hasChildNodes() ? wrapper : null;
    }

    function appendWorkflowBadge(wrapper, label, variant) {
        const badge = document.createElement("span");
        badge.className = "workflow-badge workflow-badge--" + variant;
        badge.textContent = label;
        wrapper.appendChild(badge);
    }

    function buildLiveActivityHeadline(steps) {
        const activeStep = activeProgressStep(steps);
        const headline = document.createElement("div");
        headline.className = "live-activity-headline";

        const label = document.createElement("p");
        label.className = "live-activity-headline__label";
        label.textContent = activeStep
            ? cleanProgressText(activeStep.label) || cleanProgressText(activeStep.id) || "Working"
            : "Finishing response";
        headline.appendChild(label);

        const summary = activeStep ? cleanProgressText(activeStep.summary) : "";
        if (summary) {
            const detail = document.createElement("p");
            detail.className = "live-activity-headline__summary";
            detail.textContent = summary;
            headline.appendChild(detail);
        }

        return headline;
    }

    function buildLiveActivityList(steps) {
        const list = document.createElement("ol");
        list.className = "live-activity-list";
        steps.forEach(function (step) {
            const item = document.createElement("li");
            item.className = "live-activity-list__item live-activity-list__item--" + normalizeProgressStatus(step.status);

            const labelRow = document.createElement("span");
            labelRow.className = "live-activity-list__label-row";

            const label = document.createElement("span");
            label.className = "live-activity-list__label";
            label.textContent = cleanProgressText(step.label) || cleanProgressText(step.id) || "Working";
            labelRow.appendChild(label);

            const stepBadges = buildStepBadges(step);
            if (stepBadges) {
                labelRow.appendChild(stepBadges);
            }

            const meta = document.createElement("span");
            meta.className = "live-activity-list__meta";
            meta.textContent = formatLiveActivityMeta(step);

            item.append(labelRow, meta);

            const summary = cleanProgressText(step.summary);
            if (summary) {
                const summaryElement = document.createElement("span");
                summaryElement.className = "live-activity-list__summary";
                summaryElement.textContent = summary;
                item.appendChild(summaryElement);
            }

            const tokenUsage = resolveTokenUsage(step);
            if (tokenUsage) {
                const tokenElement = document.createElement("span");
                tokenElement.className = "live-activity-list__summary live-activity-list__summary--tokens";
                tokenElement.textContent = formatTokenBreakdown(tokenUsage);
                item.appendChild(tokenElement);
            }

            list.appendChild(item);
        });

        return list;
    }

    function buildStepBadges(step) {
        if (!step || step.recovered !== true) {
            return null;
        }

        const wrapper = document.createElement("span");
        wrapper.className = "step-badges";
        const element = document.createElement("span");
        element.className = "step-badge step-badge--source";
        element.textContent = "Recovered";
        wrapper.appendChild(element);
        return wrapper;
    }

    function updateSessionProgress(sessionId, step) {
        const targetSessionId = normalizeSessionValue(sessionId);
        const progressStep = normalizeProgressStep(step);
        if (!targetSessionId || !progressStep) {
            return;
        }

        ensureSessionProgressStarted(targetSessionId);
        const currentSteps = sessionProgress(targetSessionId);
        const nextSteps = currentSteps.slice();
        const progressKey = progressStepKey(progressStep);
        const existingIndex = nextSteps.findIndex(function (currentStep) {
            return progressStepKey(currentStep) === progressKey;
        });
        const now = Date.now();
        const existingStep = existingIndex >= 0 ? nextSteps[existingIndex] : null;
        const nextStep = Object.assign({}, progressStep, {
            startedAt: existingStep && Number.isFinite(existingStep.startedAt) ? existingStep.startedAt : now,
            updatedAt: now
        });

        if (existingIndex >= 0) {
            nextSteps[existingIndex] = nextStep;
        } else {
            nextSteps.push(nextStep);
        }

        state.pendingProgressBySession[targetSessionId] = nextSteps;
        if (targetSessionId === state.sessionId) {
            setStatus(progressStep.label || "Analyzing");
            renderMessages();
        }
    }

    function progressStepKey(step) {
        if (!step || !step.id) {
            return "";
        }
        return step.id + "::" + (step.recovered === true ? "recovered" : "live");
    }

    function clearSessionProgress(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        delete state.pendingProgressBySession[targetSessionId];
        delete state.pendingProgressStartedAtBySession[targetSessionId];
        syncProgressClock();
    }

    function sessionProgress(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return [];
        }

        const steps = state.pendingProgressBySession[targetSessionId];
        return Array.isArray(steps) ? steps : [];
    }

    function assistantActivitySteps(sessionId, fallbackSteps) {
        const progressSteps = visibleProgressSteps(snapshotSessionProgress(sessionId));
        const fallbackActivitySteps = executionStepsToActivitySteps(fallbackSteps);
        if (progressSteps.length > 0) {
            return mergeActivitySteps(progressSteps, fallbackActivitySteps);
        }

        return fallbackActivitySteps;
    }

    function mergeActivitySteps(activitySteps, fallbackSteps) {
        const fallbackByKey = new Map();
        fallbackSteps.forEach(function (step) {
            const key = progressStepKey(step);
            if (key) {
                fallbackByKey.set(key, step);
            }
        });

        const seen = new Set();
        const merged = activitySteps.map(function (step) {
            const key = progressStepKey(step);
            const fallback = fallbackByKey.get(key);
            seen.add(key);
            if (!fallback) {
                return step;
            }

            return Object.assign({}, fallback, step, {
                tokenUsage: resolveTokenUsage(step) || resolveTokenUsage(fallback),
                dataAccesses: resolveDataAccesses(step).length > 0
                    ? resolveDataAccesses(step)
                    : resolveDataAccesses(fallback)
            });
        });

        fallbackSteps.forEach(function (step) {
            const key = progressStepKey(step);
            if (key && !seen.has(key)) {
                merged.push(step);
            }
        });
        return merged;
    }

    function executionStepsToActivitySteps(executionSteps) {
        if (!Array.isArray(executionSteps)) {
            return [];
        }

        return executionSteps.map(function (step) {
            return normalizeProgressStep({
                id: step && step.id,
                label: step && step.label,
                kind: step && step.kind,
                actorType: step && step.actorType,
                actorName: step && step.actorName,
                status: "completed",
                summary: step && step.summary,
                durationMs: step && step.durationMs,
                tokenUsage: step && step.tokenUsage,
                dataAccesses: step && step.dataAccesses
            });
        }).filter(Boolean);
    }

    function snapshotSessionProgress(sessionId) {
        return sessionProgress(sessionId).map(function (step) {
            return {
                id: step.id,
                label: step.label,
                kind: step.kind,
                actorType: step.actorType,
                actorName: step.actorName,
                status: step.status,
                summary: step.summary,
                durationMs: step.durationMs,
                recovered: step.recovered === true,
                tokenUsage: step.tokenUsage,
                dataAccesses: resolveDataAccesses(step)
            };
        });
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
            actorType: cleanProgressText(step.actorType),
            actorName: cleanProgressText(step.actorName),
            status: normalizeProgressStatus(step.status),
            summary: cleanProgressText(step.summary),
            durationMs: Number.isFinite(step.durationMs) ? step.durationMs : null,
            recovered: step.recovered === true,
            tokenUsage: resolveTokenUsage(step),
            dataAccesses: resolveDataAccesses(step)
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

    function activeProgressStep(steps) {
        const progressSteps = visibleProgressSteps(steps);
        return progressSteps.reduce(function (latestStep, step) {
            if (!latestStep) {
                return step;
            }

            const latestUpdatedAt = Number.isFinite(latestStep.updatedAt) ? latestStep.updatedAt : 0;
            const stepUpdatedAt = Number.isFinite(step.updatedAt) ? step.updatedAt : 0;
            return stepUpdatedAt >= latestUpdatedAt ? step : latestStep;
        }, null);
    }

    function visibleProgressSteps(steps) {
        const progressSteps = Array.isArray(steps) ? steps : [];
        const detailedSteps = progressSteps.filter(function (step) {
            return step && step.id !== "REQUEST_ANALYSIS" && step.id !== "WORKFLOW_START";
        });

        return detailedSteps.length > 0 ? detailedSteps : progressSteps;
    }

    function formatLiveActivityMeta(step) {
        const parts = [];
        const actor = formatStepActor(step);
        if (actor) {
            parts.push(actor);
        }
        if (step.kind) {
            parts.push(step.kind);
        }
        parts.push(step.status === "completed" ? "done" : step.status);
        if (step.durationMs != null) {
            parts.push(formatDuration(step.durationMs));
        }
        const tokenUsage = resolveTokenUsage(step);
        if (tokenUsage) {
            parts.push(formatTokenBadge(tokenUsage));
        }
        return parts.join(" ");
    }

    function formatStepActor(step) {
        if (!step || typeof step !== "object") {
            return "";
        }

        const actor = cleanProgressText(step.actorName) || cleanProgressText(step.actorType);
        return actor ? formatProgressToken(actor) : "";
    }

    function formatProgressToken(value) {
        return cleanProgressText(value).replace(/_/g, " ");
    }

    function setSessionLoading(sessionId, isLoading) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        if (isLoading) {
            state.pendingSessions[targetSessionId] = true;
            ensureSessionProgressStarted(targetSessionId);
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

    function markSessionWaitingForRecovery(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        state.pendingSessions[targetSessionId] = true;
        ensureSessionProgressStarted(targetSessionId);
        updateSessionProgress(targetSessionId, {
            id: "REQUEST_WAITING_FOR_RECOVERY",
            label: "Waiting for backend",
            kind: "system",
            actorType: "system",
            actorName: "system",
            status: "running",
            summary: "The application stopped responding. Keep this request open. Redis recovery should resume it within a minute."
        });
        syncLoadingState();
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
        syncProgressClock();
    }

    function ensureSessionProgressStarted(sessionId) {
        const targetSessionId = normalizeSessionValue(sessionId);
        if (!targetSessionId) {
            return;
        }

        if (!Number.isFinite(state.pendingProgressStartedAtBySession[targetSessionId])) {
            state.pendingProgressStartedAtBySession[targetSessionId] = Date.now();
        }
        syncProgressClock();
    }

    function syncProgressClock() {
        if (state.loading && !state.progressClock) {
            state.progressClock = window.setInterval(function () {
                const timestamp = elements.messages.querySelector("[data-progress-session-id]");
                if (state.loading && timestamp) {
                    const sessionId = timestamp.dataset.progressSessionId;
                    timestamp.textContent = formatLiveWorkedLabel(sessionId, sessionProgress(sessionId));
                }
            }, 1000);
            return;
        }

        if (!state.loading && state.progressClock) {
            window.clearInterval(state.progressClock);
            state.progressClock = null;
        }
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

    function shouldStickToBottom() {
        const remaining = elements.messages.scrollHeight
            - elements.messages.scrollTop
            - elements.messages.clientHeight;
        return remaining < 96;
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
        if (value === null || value === undefined || String(value).trim() === "") {
            return "";
        }

        const parsed = new Date(value);
        if (Number.isNaN(parsed.getTime())) {
            return "";
        }

        const today = new Date();
        const options = {
            hour: "numeric",
            minute: "2-digit"
        };
        if (!isSameLocalDate(parsed, today)) {
            options.month = "short";
            options.day = "numeric";
            if (parsed.getFullYear() !== today.getFullYear()) {
                options.year = "numeric";
            }
        }

        return new Intl.DateTimeFormat(undefined, options).format(parsed);
    }

    function isSameLocalDate(left, right) {
        return left.getFullYear() === right.getFullYear()
            && left.getMonth() === right.getMonth()
            && left.getDate() === right.getDate();
    }

    function normalizeSessionList(sessions) {
        const normalized = [];
        const seen = new Set();

        const activeSessionId = normalizeSessionValue(state.sessionId);
        if (activeSessionId && hasLocalSessionState(activeSessionId)) {
            addSessionId(activeSessionId);
        }
        for (const sessionId of Object.keys(state.pendingSessions)) {
            addSessionId(sessionId);
        }
        for (const sessionId of Object.keys(state.messagesBySession)) {
            if (sessionMessages(sessionId).length > 0) {
                addSessionId(sessionId);
            }
        }
        if (Array.isArray(sessions)) {
            for (const session of sessions) {
                addSessionId(sessionIdFromSessionListItem(session));
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

    function normalizeRemoteSessionList(sessions) {
        const normalized = [];
        const seen = new Set();
        if (!Array.isArray(sessions)) {
            return normalized;
        }

        for (const session of sessions) {
            const sessionId = sessionIdFromSessionListItem(session);
            const createdAt = sessionCreatedAtFromSessionListItem(session);
            if (createdAt) {
                storeSessionLabel(sessionId, createdAt);
            }
            if (!sessionId || seen.has(sessionId)) {
                continue;
            }
            seen.add(sessionId);
            normalized.push(sessionId);
        }
        return normalized;
    }

    function hasLocalSessionState(sessionId) {
        return isSessionPending(sessionId) || sessionMessages(sessionId).length > 0;
    }

    function sessionIdFromSessionListItem(session) {
        if (typeof session === "string") {
            return normalizeSessionValue(session);
        }

        if (!session || typeof session !== "object") {
            return "";
        }

        return normalizeSessionValue(session.sessionId);
    }

    function sessionCreatedAtFromSessionListItem(session) {
        if (!session || typeof session !== "object") {
            return "";
        }

        return normalizeTimestamp(session.createdAt) || normalizeTimestamp(session.timestamp);
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
            const timestamp = normalizeTimestamp(message && message.timestamp)
                || normalizeTimestamp(message && message.createdAt)
                || loadedAt;
            const executionSteps = Array.isArray(message && message.executionSteps)
                ? message.executionSteps
                : [];
            const responseTimeMs = role === "assistant"
                ? normalizeResponseTimeMs(message && message.responseTimeMs)
                    ?? normalizeResponseTimeMs(message && message.durationMs)
                    ?? normalizeResponseTimeMs(message && message.elapsedMs)
                    ?? sumStepDurations(executionSteps)
                : null;
            const tokenUsage = role === "assistant"
                ? normalizeTokenUsage(message && message.tokenUsage) || sumStepTokenUsage(executionSteps)
                : null;
            if (!content) {
                return null;
            }

            return {
                role: role,
                content: content,
                timestamp: timestamp,
                memories: normalizeStringList(
                    message && (message.retrievedMemories || message.memories)
                ),
                fromSemanticCache: Boolean(message && message.fromSemanticCache),
                fromSemanticGuardrail: Boolean(message && message.fromSemanticGuardrail),
                tokenUsage: tokenUsage,
                responseTimeMs: responseTimeMs,
                executionSteps: executionSteps,
                activitySteps: role === "assistant" ? executionStepsToActivitySteps(executionSteps) : [],
                pendingApproval: normalizePendingApproval(message && message.pendingApproval)
            };
        }).filter(function (message) {
            return Boolean(message) && !isApprovalHidden(message.pendingApproval);
        });
    }

    function normalizePendingApproval(approval) {
        if (!approval || typeof approval !== "object") {
            return null;
        }
        const approvalId = normalizeSessionValue(approval.approvalId);
        const workflowId = normalizeSessionValue(approval.workflowId);
        if (!approvalId || !workflowId) {
            return null;
        }
        return {
            approvalId: approvalId,
            workflowId: workflowId,
            activeWorkflowId: normalizeSessionValue(approval.activeWorkflowId),
            userId: normalizeUserId(approval.userId),
            sessionId: normalizeSessionValue(approval.sessionId),
            conversationId: typeof approval.conversationId === "string" ? approval.conversationId.trim() : "",
            toolName: typeof approval.toolName === "string" ? approval.toolName.trim() : "",
            agentType: typeof approval.agentType === "string" ? approval.agentType.trim() : "",
            ticker: typeof approval.ticker === "string" ? approval.ticker.trim() : "",
            question: typeof approval.question === "string" ? approval.question.trim() : "",
            arguments: typeof approval.arguments === "string" ? approval.arguments.trim() : "",
            status: typeof approval.status === "string" && approval.status.trim()
                ? approval.status.trim().toUpperCase()
                : "PENDING",
            createdAt: normalizeTimestamp(approval.createdAt),
            updatedAt: normalizeTimestamp(approval.updatedAt),
            decidedAt: normalizeTimestamp(approval.decidedAt),
            resumedWorkflowId: normalizeSessionValue(approval.resumedWorkflowId)
        };
    }

    function cssEscape(value) {
        if (window.CSS && typeof window.CSS.escape === "function") {
            return window.CSS.escape(String(value));
        }
        return String(value).replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
    }

    function isApprovalHidden(approval) {
        const pendingApproval = normalizePendingApproval(approval);
        return Boolean(pendingApproval && state.hiddenApprovalIds.has(pendingApproval.approvalId));
    }

    function hideApprovalMessage(approvalId) {
        const targetApprovalId = normalizeSessionValue(approvalId);
        if (!targetApprovalId) {
            return [];
        }

        const removedMessages = removeApprovalMessages(targetApprovalId);
        state.hiddenApprovalIds.add(targetApprovalId);
        renderMessages();
        return removedMessages;
    }

    function restoreApprovalMessages(approvalId, removedMessages) {
        const targetApprovalId = normalizeSessionValue(approvalId);
        if (!targetApprovalId) {
            return;
        }

        state.hiddenApprovalIds.delete(targetApprovalId);
        restoreRemovedApprovalMessages(removedMessages);
        renderMessages();
    }

    function removeApprovalMessages(approvalId) {
        const removed = [];
        Object.keys(state.messagesBySession || {}).forEach(function (sessionId) {
            const messages = sessionMessages(sessionId);
            const kept = [];
            messages.forEach(function (message, index) {
                if (approvalMessageId(message) === approvalId) {
                    removed.push({ sessionId: sessionId, index: index, message: message });
                } else {
                    kept.push(message);
                }
            });
            state.messagesBySession[sessionId] = kept;
            if (sessionId === state.sessionId) {
                state.messages = kept;
            }
        });

        if (!normalizeSessionValue(state.sessionId)) {
            state.messages = state.messages.filter(function (message, index) {
                if (approvalMessageId(message) === approvalId) {
                    removed.push({ sessionId: "", index: index, message: message });
                    return false;
                }
                return true;
            });
        }

        return removed;
    }

    function restoreRemovedApprovalMessages(removedMessages) {
        if (!Array.isArray(removedMessages) || removedMessages.length === 0) {
            return;
        }

        removedMessages.forEach(function (entry) {
            if (!entry || !entry.message) {
                return;
            }
            const sessionId = normalizeSessionValue(entry.sessionId);
            if (!sessionId) {
                state.messages.splice(entry.index, 0, entry.message);
                return;
            }
            const messages = sessionMessages(sessionId).slice();
            messages.splice(entry.index, 0, entry.message);
            state.messagesBySession[sessionId] = messages;
            if (sessionId === state.sessionId) {
                state.messages = messages;
            }
        });
    }

    function approvalMessageId(message) {
        const approval = normalizePendingApproval(message && message.pendingApproval);
        return approval ? approval.approvalId : "";
    }

    function setApprovalButtonsDisabled(approvalId, disabled) {
        const targetApprovalId = normalizeSessionValue(approvalId);
        if (!targetApprovalId) {
            return;
        }
        elements.messages.querySelectorAll("[data-approval-id=\"" + cssEscape(targetApprovalId) + "\"]").forEach(function (button) {
            button.disabled = Boolean(disabled);
        });
    }

    function normalizeStringList(values) {
        if (!Array.isArray(values)) {
            return [];
        }

        return values.map(function (value) {
            return typeof value === "string" ? value.trim() : "";
        }).filter(Boolean);
    }

    function normalizeTimestamp(value) {
        if (typeof value !== "string" || !value.trim()) {
            return "";
        }

        const parsed = new Date(value);
        if (Number.isNaN(parsed.getTime())) {
            return "";
        }

        return parsed.toISOString();
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

    function storeSessionLabel(sessionId, createdAt) {
        const targetSessionId = normalizeSessionValue(sessionId);
        const timestamp = normalizeTimestamp(createdAt);
        if (!targetSessionId || !timestamp) {
            return;
        }

        const labels = readSessionLabels();
        labels[targetSessionId] = formatSessionTimestamp(timestamp);
        writeSessionLabels(labels);
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

    function formatTimeSpentBadge(durationMs) {
        if (!Number.isFinite(durationMs) || durationMs < 0) {
            return "";
        }

        const roundedMs = Math.round(durationMs);
        if (roundedMs < 1000) {
            return roundedMs + " ms";
        }

        const totalSeconds = Math.max(1, Math.round(roundedMs / 1000));
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }

        return totalSeconds + "s";
    }

    function formatWorkedLabel(durationMs, verb) {
        const labelVerb = verb || "Worked";
        if (!Number.isFinite(durationMs) || durationMs < 0) {
            return labelVerb;
        }

        const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;

        if (minutes > 0) {
            return labelVerb + " for " + minutes + "m " + seconds + "s";
        }

        return labelVerb + " for " + seconds + "s";
    }

    function formatWorkSummaryLabel(durationMs, tokenUsage, verb) {
        const parts = [formatWorkedLabel(durationMs, verb)];
        const resolvedTokenUsage = resolveTokenUsage(tokenUsage);
        if (resolvedTokenUsage) {
            parts.push(formatTokenBadge(resolvedTokenUsage));
        }
        return parts.join(" · ");
    }

    function formatLiveWorkedLabel(sessionId, steps) {
        const targetSessionId = normalizeSessionValue(sessionId);
        const startedAt = targetSessionId ? state.pendingProgressStartedAtBySession[targetSessionId] : null;
        const tokenUsage = sumStepTokenUsage(visibleProgressSteps(
                Array.isArray(steps) ? steps : sessionProgress(targetSessionId)
        ));
        if (!Number.isFinite(startedAt)) {
            return formatWorkSummaryLabel(null, tokenUsage, "Working");
        }

        return formatWorkSummaryLabel(Date.now() - startedAt, tokenUsage, "Working");
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
        return createId("session");
    }

    function createClientRequestId() {
        return createId("request");
    }

    function createId(prefix) {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
            return window.crypto.randomUUID();
        }

        return prefix + "-" + Date.now();
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

    function resolveMessageResponseTime(message) {
        if (!message || typeof message !== "object") {
            return null;
        }

        const explicitResponseTime = normalizeResponseTimeMs(message.responseTimeMs)
            ?? normalizeResponseTimeMs(message.durationMs)
            ?? normalizeResponseTimeMs(message.elapsedMs);
        if (explicitResponseTime != null) {
            return explicitResponseTime;
        }

        return sumStepDurations(
            Array.isArray(message.activitySteps) && message.activitySteps.length > 0
                ? message.activitySteps
                : Array.isArray(message.executionSteps)
                    ? message.executionSteps
                    : []
        );
    }

    function normalizeResponseTimeMs(value) {
        const parsed = Number(value);
        return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
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
