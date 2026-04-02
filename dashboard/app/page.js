"use client";

import { useState, useRef, useEffect } from "react";
import Sidebar from "@/components/Sidebar";
import ChatWindow from "@/components/ChatWindow";
import ChatInput from "@/components/ChatInput";

const GATEWAY_URL = process.env.NEXT_PUBLIC_GATEWAY_URL || "http://localhost:8080/api/v1/chat";

export default function Home() {
  const [messages, setMessages] = useState([]);
  const [metrics, setMetrics] = useState({
    cache_hit: false,
    model_routed: "None",
    fallback_triggered: false,
    qc_score: 0,
    qc_passed: true,
    latency_ms: 0,
  });
  const [loading, setLoading] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
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
          return { ...s, title, messages };
        }
        return s;
      });
      localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
      return updated;
    });
  }, [messages]);

  const handleNewChat = () => {
    setSessions(prev => {
      const newSession = { id: Date.now().toString(), title: "New Chat", messages: [] };
      const updated = [newSession, ...prev];
      localStorage.setItem("diplomatAI_sessions", JSON.stringify(updated));
      setCurrentSessionId(newSession.id);
      setMessages([]);
      setMetrics({
        cache_hit: false, model_routed: "-", fallback_triggered: false,
        qc_score: 0, qc_passed: true, latency_ms: 0
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
        } else {
          const newSession = { id: Date.now().toString(), title: "New Chat", messages: [] };
          updated.push(newSession);
          setCurrentSessionId(newSession.id);
          setMessages([]);
          setMetrics({
            cache_hit: null, model_routed: "-", fallback_triggered: false,
            qc_score: 0, qc_passed: true, latency_ms: 0
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
        qc_score: 0, qc_passed: true, latency_ms: 0
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

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const handleSend = async (prompt, forceBypassCache = false) => {
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

      setMetrics({
        cache_hit: m.cache_hit || false,
        model_routed: m.model_routed || "Unknown",
        fallback_triggered: m.fallback_triggered || false,
        qc_score: m.qc_score || 0,
        qc_passed: m.qc_passed !== undefined ? m.qc_passed : true,
        latency_ms: m.latency_ms || 0,
      });

      setMessages((prev) => [
        ...prev, 
        { 
          role: "assistant", 
          content: answer, 
          model: m.model_routed || "Unknown",
          isCachedHit: m.cache_hit || false
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
      {isSidebarOpen && (
        <Sidebar
          metrics={metrics}
          gatewayUrl={GATEWAY_URL}
          onClearCache={handleClearCache}
          onRegistryUpdate={fetchRegistry}
          sessions={sessions}
          currentSessionId={currentSessionId}
          onNewChat={handleNewChat}
          onSelectSession={handleSelectSession}
          onDeleteSession={handleDeleteSession}
          onRenameSession={handleRenameSession}
        />
      )}
      <div className="main-area">
        <div className="top-nav">
          <button 
            className="btn btn-icon" 
            onClick={() => setIsSidebarOpen(!isSidebarOpen)} 
            title={isSidebarOpen ? "Close Sidebar" : "Open Sidebar"}
            style={{ fontSize: '1.2rem', padding: '4px 8px' }}
          >
            {isSidebarOpen ? "◀" : "☰"}
          </button>
        </div>
        <ChatWindow 
          messages={messages} 
          chatEndRef={chatEndRef} 
          onRegenerate={(prompt) => handleSend(prompt, true)}
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
    </div>
  );
}
