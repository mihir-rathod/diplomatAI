"use client";

import { useState } from "react";

export default function ChatInput({ 
  onSend, 
  loading, 
  registryModels = [], 
  selectedModel, 
  setSelectedModel,
  useCache,
  setUseCache
}) {
  const [input, setInput] = useState("");

  const handleSubmit = (e) => {
    e.preventDefault();
    const trimmed = input.trim();
    if (!trimmed || loading) return;
    onSend(trimmed);
    setInput("");
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  return (
    <div className="chat-input-area">
      <div style={{ display: 'flex', justifyContent: 'flex-start', alignItems: 'center', marginBottom: '8px', paddingLeft: '16px', gap: '16px' }}>
        <select 
          value={selectedModel} 
          onChange={(e) => setSelectedModel(e.target.value)}
          disabled={loading}
          style={{ 
            background: 'transparent', 
            color: 'var(--text-muted)', 
            border: 'none', 
            fontSize: '0.8rem', 
            cursor: 'pointer', 
            outline: 'none',
            fontWeight: '600',
            textTransform: 'uppercase',
            letterSpacing: '0.5px'
          }}
        >
          <option value="auto" style={{ background: 'var(--bg-primary)', color: 'var(--text-primary)' }}>✓ Auto (Semantic Routing)</option>
          {registryModels.map(m => (
            <option key={m.id} value={m.id} style={{ background: 'var(--bg-primary)', color: 'var(--text-primary)' }}>
              {m.name || m.id}
            </option>
          ))}
        </select>
        <label style={{ display: 'flex', alignItems: 'center', fontSize: '0.8rem', color: 'var(--text-muted)', cursor: 'pointer', fontWeight: '600', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
          <input 
            type="checkbox" 
            checked={useCache} 
            onChange={(e) => setUseCache(e.target.checked)}
            disabled={loading}
            style={{ marginRight: '6px', cursor: 'pointer' }}
          />
          Use Cache
        </label>
      </div>
      <form className="chat-input-wrapper" onSubmit={handleSubmit}>
        <input
          className="chat-input"
          type="text"
          placeholder="Send a message..."
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={loading}
          autoFocus
        />
        <button className="chat-send-btn" type="submit" disabled={loading || !input.trim()}>
          {loading ? "..." : "Send"}
        </button>
      </form>
    </div>
  );
}
