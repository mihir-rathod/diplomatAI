"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import ChatWindow from "@/components/ChatWindow";
import ChatInput from "@/components/ChatInput";
import LeftSidebar from "@/components/LeftSidebar";
import RightSidebar from "@/components/RightSidebar";
import {
  isLoggedIn,
  getUser,
  logout,
  apiFetch,
  fetchSessions,
  createSession,
  renameSession,
  deleteSession as deleteSessionApi,
  fetchMessages,
  GATEWAY_URL,
} from "@/lib/api";

export default function Home() {
  const router = useRouter();
  const [authReady, setAuthReady] = useState(false);
  const [user, setUser] = useState(null);

  const [messages, setMessages] = useState([]);
  const [metrics, setMetrics] = useState({
    cache_hit: null,
    model_routed: "-",
    fallback_triggered: false,
    original_model: null,
    prompt_tokens: 0,
    completion_tokens: 0,
    total_tokens: 0,
    provider: null,
    latency_ms: 0,
    sessionUsage: {}
  });
  const [toast, setToast] = useState(null);
  const [loading, setLoading] = useState(false);
  const [isLeftSidebarOpen, setIsLeftSidebarOpen] = useState(true);
  const [isRightSidebarOpen, setIsRightSidebarOpen] = useState(true);

  // Resizing state
  const [leftWidth, setLeftWidth] = useState(300);
  const [rightWidth, setRightWidth] = useState(300);
  const isDraggingLeft = useRef(false);
  const isDraggingRight = useRef(false);

  const [selectedModel, setSelectedModel] = useState("auto");
  const [useCache, setUseCache] = useState(true);
  const [registryModels, setRegistryModels] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [currentSessionId, setCurrentSessionId] = useState(null);
  const chatEndRef = useRef(null);

  // ── Auth guard ──────────────────────────────────────────────────────────────
  useEffect(() => {
    if (!isLoggedIn()) {
      router.push("/login");
      return;
    }
    setUser(getUser());
    setAuthReady(true);
  }, [router]);

  // ── Load sessions from server ───────────────────────────────────────────────
  const loadSessions = useCallback(async () => {
    try {
      const data = await fetchSessions();
      setSessions(data || []);
      if (data && data.length > 0) {
        const first = data[0];
        setCurrentSessionId(first.id);
        const msgs = await fetchMessages(first.id);
        setMessages(msgs.map(m => ({
          id: String(m.id),
          role: m.role,
          content: m.content,
          model: m.model,
          provider: m.provider,
          isCachedHit: m.cachedHit,
        })));
      }
    } catch (err) {
      console.error("Failed to load sessions:", err);
    }
  }, []);

  useEffect(() => {
    if (authReady) {
      loadSessions();
    }
  }, [authReady, loadSessions]);

  const fetchRegistry = useCallback(async () => {
    try {
      const res = await apiFetch(`${GATEWAY_URL}/models/registry`);
      const data = await res.json();
      setRegistryModels(data || []);
    } catch {
      setRegistryModels([]);
    }
  }, []);

  useEffect(() => {
    if (authReady) fetchRegistry();
  }, [authReady, fetchRegistry]);

  // ── Mouse listeners for sidebar resize ─────────────────────────────────────
  useEffect(() => {
    const handleMouseMove = (e) => {
      if (isDraggingLeft.current) {
        setLeftWidth(Math.max(200, Math.min(e.clientX, 600)));
      } else if (isDraggingRight.current) {
        setRightWidth(Math.max(200, Math.min(window.innerWidth - e.clientX, 600)));
      }
    };
    const handleMouseUp = () => {
      if (isDraggingLeft.current || isDraggingRight.current) {
        isDraggingLeft.current = false;
        isDraggingRight.current = false;
        document.body.style.cursor = "default";
        document.body.style.userSelect = "auto";
      }
    };
    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);
    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
    };
  }, []);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(null), 5000);
  };

  const resetMetrics = () => setMetrics({
    cache_hit: null, model_routed: "-", fallback_triggered: false,
    original_model: null, prompt_tokens: 0, completion_tokens: 0,
    total_tokens: 0, provider: null, latency_ms: 0, sessionUsage: {}
  });

  // ── New chat ────────────────────────────────────────────────────────────────
  const handleNewChat = async () => {
    try {
      const session = await createSession("New Chat");
      setSessions(prev => [session, ...prev]);
      setCurrentSessionId(session.id);
      setMessages([]);
      resetMetrics();
    } catch (err) {
      console.error("Failed to create session:", err);
    }
  };

  // ── Select session (load messages from server) ──────────────────────────────
  const handleSelectSession = async (id) => {
    setCurrentSessionId(id);
    resetMetrics();
    try {
      const msgs = await fetchMessages(id);
      setMessages(msgs.map(m => ({
        id: String(m.id),
        role: m.role,
        content: m.content,
        model: m.model,
        provider: m.provider,
        isCachedHit: m.cachedHit,
      })));
    } catch (err) {
      console.error("Failed to load messages:", err);
      setMessages([]);
    }
  };

  // ── Delete session ──────────────────────────────────────────────────────────
  const handleDeleteSession = async (id) => {
    try {
      await deleteSessionApi(id);
      const updated = sessions.filter(s => s.id !== id);
      setSessions(updated);
      if (currentSessionId === id) {
        if (updated.length > 0) {
          handleSelectSession(updated[0].id);
        } else {
          handleNewChat();
        }
      }
    } catch (err) {
      console.error("Failed to delete session:", err);
    }
  };

  // ── Rename session ──────────────────────────────────────────────────────────
  const handleRenameSession = async (id, newTitle) => {
    try {
      await renameSession(id, newTitle);
      setSessions(prev => prev.map(s => s.id === id ? { ...s, title: newTitle } : s));
    } catch (err) {
      console.error("Failed to rename session:", err);
    }
  };

  // ── Auto-generate title for new sessions ───────────────────────────────────
  const generateTitleForSession = async (firstPrompt, sessionId) => {
    try {
      const titlePrompt = `Generate a concise 3 to 5 word title for the following request. Return ONLY the string without quotes, markdown, or punctuation:\n\n${firstPrompt}`;
      const res = await apiFetch(GATEWAY_URL, {
        method: "POST",
        body: JSON.stringify({ prompt: titlePrompt, modelId: "auto", useCache: false }),
      });
      const data = await res.json();
      // "internal"/"none" means no real model answered (gateway fallback or error text) — not a usable title
      const answeredByRealModel = !["internal", "none"].includes((data.metrics?.provider || "").toLowerCase());
      if (answeredByRealModel && data.answer && !data.answer.toLowerCase().includes("system error") && !data.answer.toLowerCase().includes("error:")) {
        let title = data.answer.replace(/["'*`_]/g, "").trim();
        if (title.endsWith(".")) title = title.slice(0, -1);
        await renameSession(sessionId, title);
        setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, title } : s));
      }
    } catch (err) {
      console.error("Silent fail on background title generation:", err);
    }
  };

  // ── Send message ────────────────────────────────────────────────────────────
  const handleSend = async (prompt, forceBypassCache = false) => {
    const isFirstMessage = messages.length === 0;

    // Auto-create a session if somehow there isn't one
    let activeSessionId = currentSessionId;
    if (!activeSessionId) {
      try {
        const session = await createSession("New Chat");
        setSessions(prev => [session, ...prev]);
        setCurrentSessionId(session.id);
        activeSessionId = session.id;
      } catch {
        activeSessionId = null;
      }
    }

    if (isFirstMessage && activeSessionId) {
      generateTitleForSession(prompt, activeSessionId);
    }

    const userMsg = { id: crypto.randomUUID(), role: "user", content: prompt };
    setMessages(prev => [...prev, userMsg]);
    setLoading(true);

    try {
      const conversationHistory = [
        ...messages.map(m => ({ role: m.role, content: m.content })),
        { role: "user", content: prompt }
      ];

      const reqBody = {
        prompt,
        messages: conversationHistory,
        sessionId: activeSessionId,
        useCache: forceBypassCache ? false : useCache
      };
      if (selectedModel !== "auto") reqBody.modelId = selectedModel;

      const res = await apiFetch(GATEWAY_URL, {
        method: "POST",
        body: JSON.stringify(reqBody),
      });
      const data = await res.json();

      const answer = data.answer || "No response received.";
      const m = data.metrics || {};

      const currentUsage = metrics.sessionUsage || {};
      const provider = (m.provider || "cache").toLowerCase();
      const newUsage = { ...currentUsage };
      if (m.total_tokens > 0) newUsage[provider] = (newUsage[provider] || 0) + m.total_tokens;

      const currentLiveLimits = metrics.liveLimits || {};
      const newLiveLimits = { ...currentLiveLimits };
      if (m.rate_limit_max && m.rate_limit_max > 0) newLiveLimits[provider] = m.rate_limit_max;

      setMetrics({
        cache_hit: m.cache_hit || false,
        model_routed: m.model_routed || "Unknown",
        fallback_triggered: m.fallback_triggered || false,
        original_model: m.original_model,
        prompt_tokens: m.prompt_tokens || 0,
        completion_tokens: m.completion_tokens || 0,
        total_tokens: m.total_tokens || 0,
        provider: m.provider,
        latency_ms: m.latency_ms || 0,
        sessionUsage: newUsage,
        liveLimits: newLiveLimits
      });

      if (m.fallback_triggered && m.model_routed) {
        showToast(`⚡ ${m.original_model || "Requested model"} was unavailable. Switched to ${m.model_routed}.`);
        setSelectedModel(m.model_routed);
      }

      setMessages(prev => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: "assistant",
          content: answer,
          model: m.model_routed || "Unknown",
          isCachedHit: m.cache_hit || false,
          provider: m.provider,
          wasRerouted: m.fallback_triggered,
          originalModel: m.original_model
        }
      ]);

      // Update session updatedAt in sidebar
      setSessions(prev => prev.map(s =>
        s.id === activeSessionId ? { ...s, updatedAt: new Date().toISOString() } : s
      ));

    } catch (err) {
      setMessages(prev => [
        ...prev,
        { id: crypto.randomUUID(), role: "assistant", content: "Gateway is unreachable. Please check if the services are running." },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleClearCache = async () => {
    try {
      await apiFetch(`${GATEWAY_URL}/cache`, { method: "DELETE" });
    } catch (err) {
      console.error("Failed to clear cache:", err);
    }
  };

  // Don't render until auth check is complete
  if (!authReady) {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100vh", background: "var(--bg-primary)" }}>
        <div className="spinner" style={{ width: "24px", height: "24px", borderWidth: "3px" }} />
      </div>
    );
  }

  return (
    <div className="app-layout">
      {toast && (
        <div className="toast-container">
          <div className="toast">{toast}</div>
        </div>
      )}

      {isLeftSidebarOpen && (
        <>
          <LeftSidebar
            sessions={sessions}
            currentSessionId={currentSessionId}
            onNewChat={handleNewChat}
            onSelectSession={handleSelectSession}
            onDeleteSession={handleDeleteSession}
            onRenameSession={handleRenameSession}
            width={leftWidth}
            user={user}
            onSignOut={logout}
          />
          <div
            className="resizer"
            onMouseDown={() => {
              isDraggingLeft.current = true;
              document.body.style.cursor = "col-resize";
              document.body.style.userSelect = "none";
            }}
          />
        </>
      )}

      <div className="main-area">
        <div className="top-nav">
          <button
            className="btn btn-icon"
            onClick={() => setIsLeftSidebarOpen(!isLeftSidebarOpen)}
            title={isLeftSidebarOpen ? "Close Context Menu" : "Open Context Menu"}
            style={{ fontSize: "1.2rem", padding: "4px 8px" }}
          >
            {isLeftSidebarOpen ? "◀" : "☰"}
          </button>

          <button
            className="btn btn-icon"
            onClick={() => setIsRightSidebarOpen(!isRightSidebarOpen)}
            title={isRightSidebarOpen ? "Close Inspector" : "Open Inspector"}
            style={{ fontSize: "1.2rem", padding: "4px 8px" }}
          >
            {isRightSidebarOpen ? "▶" : "☷"}
          </button>
        </div>

        <ChatWindow
          messages={messages}
          chatEndRef={chatEndRef}
          onRegenerate={(prompt) => handleSend(prompt, true)}
          loading={loading}
        />
        <ChatInput
          onSend={handleSend}
          loading={loading}
          registryModels={registryModels}
          selectedModel={selectedModel}
          setSelectedModel={setSelectedModel}
          useCache={useCache}
          setUseCache={setUseCache}
        />
      </div>

      {isRightSidebarOpen && (
        <>
          <div
            className="resizer"
            onMouseDown={() => {
              isDraggingRight.current = true;
              document.body.style.cursor = "col-resize";
              document.body.style.userSelect = "none";
            }}
          />
          <RightSidebar
            metrics={metrics}
            gatewayUrl={GATEWAY_URL}
            onClearCache={handleClearCache}
            onRegistryUpdate={fetchRegistry}
            width={rightWidth}
          />
        </>
      )}
    </div>
  );
}
