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
  const chatEndRef = useRef(null);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const handleSend = async (prompt) => {
    const userMsg = { role: "user", content: prompt };
    setMessages((prev) => [...prev, userMsg]);
    setLoading(true);

    try {
      const res = await fetch(GATEWAY_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt }),
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

      setMessages((prev) => [...prev, { role: "assistant", content: answer }]);
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: "Gateway is unreachable. Please check if the services are running." },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleClearChat = () => setMessages([]);

  const handleClearCache = async () => {
    try {
      await fetch(`${GATEWAY_URL}/cache`, { method: "DELETE" });
    } catch (err) {
      console.error("Failed to clear cache:", err);
    }
  };

  return (
    <div className="app-layout">
      <Sidebar
        metrics={metrics}
        gatewayUrl={GATEWAY_URL}
        onClearChat={handleClearChat}
        onClearCache={handleClearCache}
      />
      <div className="main-area">
        <ChatWindow messages={messages} chatEndRef={chatEndRef} />
        <ChatInput onSend={handleSend} loading={loading} />
      </div>
    </div>
  );
}
