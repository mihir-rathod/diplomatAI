"use client";

import { useState, useRef, useEffect } from "react";
import ChatWindow from "@/components/ChatWindow";
import ChatInput from "@/components/ChatInput";
import LeftSidebar from "@/components/LeftSidebar";
import RightSidebar from "@/components/RightSidebar";

const GATEWAY_URL = process.env.NEXT_PUBLIC_GATEWAY_URL || "http://localhost:8080/api/v1/chat";

export default function Home() {
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

  // Load and save sessions
  useEffect(() => {
    const saved = localStorage.getItem("diplomatAI_sessions");
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        setSessions(parsed);
        if (parsed.length > 0) {
          setCurrentSessionId(parsed[0].id);
          setMessages(parsed[0].messages);
          setMetrics(prev => ({...prev, sessionUsage: parsed[0].sessionUsage || {}}));
        } else {
          handleNewChat();
        }
      } catch (e) {
        handleNewChat();
      }
    } else {
      handleNewChat();
    }
  }, []);

  useEffect(() => {
    if (!currentSessionId) return;
    setSessions((prev) => {
      const updated = prev.map(s => {
        if (s.id === currentSessionId) {
          let title = s.title;
          if (title === "New Chat" && messages.length > 0 && messages[0].role === "user") {
            // Generate a simple title-like string from the first few words, capitalized
            const firstWords = messages[0].content.split(" ").slice(0, 4);
            const capitalized = firstWords.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
            title = capitalized.replace(/[^a-zA-Z0-9 ]/g, "").trim() + (messages[0].content.split(" ").length > 4 ? "..." : "");
            if (!title) title = "New Chat";
          }
          return { ...s, title, messages, sessionUsage: metrics.sessionUsage || {} };
        }
        return s;
      });
      localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
      return updated;
    });
  }, [messages, metrics.sessionUsage]);

  const handleNewChat = () => {
    setSessions(prev => {
      const newSession = { id: Date.now().toString(), title: "New Chat", messages: [], sessionUsage: {} };
      const updated = [newSession, ...prev];
      localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
      setCurrentSessionId(newSession.id);
      setMessages([]);
      setMetrics({
        cache_hit: null, model_routed: "-", fallback_triggered: false,
        prompt_tokens: 0, completion_tokens: 0, total_tokens: 0, provider: null, latency_ms: 0, sessionUsage: {}
      });
      return updated;
    });
  };

  const handleDeleteSession = (id) => {
    setSessions(prev => {
      const updated = prev.filter(s => s.id !== id);
      
      if (currentSessionId === id) {
        if (updated.length > 0) {
          setCurrentSessionId(updated[0].id);
          setMessages(updated[0].messages);
          setMetrics(m => ({...m, sessionUsage: updated[0].sessionUsage || {}}));
        } else {
          const newSession = { id: Date.now().toString(), title: "New Chat", messages: [], sessionUsage: {} };
          updated.push(newSession);
          setCurrentSessionId(newSession.id);
          setMessages([]);
          setMetrics({
            cache_hit: null, model_routed: "-", fallback_triggered: false,
            prompt_tokens: 0, completion_tokens: 0, total_tokens: 0, provider: null, latency_ms: 0, sessionUsage: {}
          });
        }
      }
      localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
      return updated;
    });
  };

  const handleSelectSession = (id) => {
    const session = sessions.find(s => s.id === id);
    if (session) {
      setCurrentSessionId(id);
      setMessages(session.messages);
      setMetrics({
        cache_hit: null, model_routed: "-", fallback_triggered: false,
        prompt_tokens: 0, completion_tokens: 0, total_tokens: 0, provider: null, latency_ms: 0, sessionUsage: session.sessionUsage || {}
      });
    }
  };

  const handleRenameSession = (id, newTitle) => {
    setSessions(prev => {
      const updated = prev.map(s => s.id === id ? { ...s, title: newTitle } : s);
      localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
      return updated;
    });
  };

  const fetchRegistry = async () => {
    try {
      const res = await fetch(`${GATEWAY_URL}/models/registry`);
      const data = await res.json();
      setRegistryModels(data || []);
    } catch {
      setRegistryModels([]);
    }
  };

  useEffect(() => {
    fetchRegistry();
  }, []);

  // Global mouse listeners for sidebars
  useEffect(() => {
    const handleMouseMove = (e) => {
      if (isDraggingLeft.current) {
        const newWidth = Math.max(200, Math.min(e.clientX, 600));
        setLeftWidth(newWidth);
      } else if (isDraggingRight.current) {
        const newWidth = Math.max(200, Math.min(window.innerWidth - e.clientX, 600));
        setRightWidth(newWidth);
      }
    };
    
    const handleMouseUp = () => {
      if (isDraggingLeft.current || isDraggingRight.current) {
        isDraggingLeft.current = false;
        isDraggingRight.current = false;
        document.body.style.cursor = 'default';
        document.body.style.userSelect = 'auto';
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
    setTimeout(() => setToast(null), 5000); // 5 seconds
  };

  const generateTitleForSession = async (firstPrompt, sessionId) => {
    try {
      const titlePrompt = `Generate a concise 3 to 5 word title for the following request. Return ONLY the string without quotes, markdown, or punctuation:\n\n${firstPrompt}`;
      const res = await fetch(GATEWAY_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: titlePrompt, modelId: "auto", useCache: false }),
      });
      const data = await res.json();
      if (data.answer && !data.answer.toLowerCase().includes("system error") && !data.answer.toLowerCase().includes("error:")) {
        let generatedTitle = data.answer.replace(/["'*`_]/g, '').trim();
        if (generatedTitle.endsWith('.')) generatedTitle = generatedTitle.slice(0, -1);
        
        setSessions(prev => {
          const updated = prev.map(s => {
            if (s.id === sessionId) {
              return { ...s, title: generatedTitle };
            }
            return s;
          });
          localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
          return updated;
        });
      }
    } catch (err) {
      console.error("Silent fail on background title generation", err);
    }
  };

  const handleSend = async (prompt, forceBypassCache = false) => {
    const isFirstMessage = messages.length === 0;
    
    if (isFirstMessage) {
      generateTitleForSession(prompt, currentSessionId);
    }

    const userMsg = { role: "user", content: prompt };
    setMessages((prev) => [...prev, userMsg]);
    setLoading(true);

    try {
      const reqBody = { 
        prompt, 
        useCache: forceBypassCache ? false : useCache 
      };
      if (selectedModel !== "auto") {
        reqBody.modelId = selectedModel;
      }

      const res = await fetch(GATEWAY_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(reqBody),
      });
      const data = await res.json();

      const answer = data.answer || "No response received.";
      const m = data.metrics || {};

      const currentUsage = metrics.sessionUsage || {};
      const provider = (m.provider || "cache").toLowerCase();
      
      const newUsage = { ...currentUsage };
      if (m.total_tokens > 0) {
        newUsage[provider] = (newUsage[provider] || 0) + m.total_tokens;
      }

      const currentLiveLimits = metrics.liveLimits || {};
      const newLiveLimits = { ...currentLiveLimits };
      if (m.rate_limit_max && m.rate_limit_max > 0) {
        newLiveLimits[provider] = m.rate_limit_max;
      }

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

      // Show toast and switch model if rerouted
      if (m.fallback_triggered && m.model_routed) {
         showToast(`⚡ ${m.original_model || "Requested model"} was unavailable. Switched to ${m.model_routed}.`);
         setSelectedModel(m.model_routed);
      }

      setMessages((prev) => [
        ...prev, 
        { 
          role: "assistant", 
          content: answer, 
          model: m.model_routed || "Unknown",
          isCachedHit: m.cache_hit || false,
          provider: m.provider,
          wasRerouted: m.fallback_triggered,
          originalModel: m.original_model
        }
      ]);
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: "Gateway is unreachable. Please check if the services are running." },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleClearCache = async () => {
    try {
      await fetch(`${GATEWAY_URL}/cache`, { method: "DELETE" });
    } catch (err) {
      console.error("Failed to clear cache:", err);
    }
  };

  return (
    <div className="app-layout">
      {toast && (
        <div className="toast-container">
          <div className="toast">
            {toast}
          </div>
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
          />
          <div 
            className="resizer" 
            onMouseDown={() => { 
              isDraggingLeft.current = true; 
              document.body.style.cursor = 'col-resize'; 
              document.body.style.userSelect = 'none'; 
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
            style={{ fontSize: '1.2rem', padding: '4px 8px' }}
          >
            {isLeftSidebarOpen ? "◀" : "☰"}
          </button>
          
          <button 
            className="btn btn-icon" 
            onClick={() => setIsRightSidebarOpen(!isRightSidebarOpen)} 
            title={isRightSidebarOpen ? "Close Inspector" : "Open Inspector"}
            style={{ fontSize: '1.2rem', padding: '4px 8px' }}
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
              document.body.style.cursor = 'col-resize'; 
              document.body.style.userSelect = 'none'; 
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
