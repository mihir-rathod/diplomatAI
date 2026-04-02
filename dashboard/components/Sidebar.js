"use client";

import { useState } from "react";
import ModelRegistry from "./ModelRegistry";
import MetricsPanel from "./MetricsPanel";

const PROVIDERS = ["OpenAI", "Gemini", "Anthropic", "Groq", "Mistral", "OpenRouter", "Together"];

export default function Sidebar({ 
  metrics, gatewayUrl, onClearCache, onRegistryUpdate,
  sessions = [], currentSessionId, onNewChat, onSelectSession, onDeleteSession, onRenameSession
}) {
  const [provider, setProvider] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [status, setStatus] = useState(null);
  const [registering, setRegistering] = useState(false);
  const [registryKey, setRegistryKey] = useState(0);
  const [editingId, setEditingId] = useState(null);
  const [editTitle, setEditTitle] = useState("");

  const startEdit = (id, title, e) => {
    e.stopPropagation();
    setEditingId(id);
    setEditTitle(title || "New Chat");
  };

  const handleEditKeyDown = (e, id) => {
    if (e.key === "Enter") {
      onRenameSession(id, editTitle);
      setEditingId(null);
    } else if (e.key === "Escape") {
      setEditingId(null);
    }
  };

  const handleRegister = async (e) => {
    e.preventDefault();
    if (!provider || !apiKey) {
      setStatus({ type: "error", msg: "Select a provider and enter an API key." });
      return;
    }

    setRegistering(true);
    setStatus(null);

    try {
      const res = await fetch(`${gatewayUrl}/models`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ provider, apiKey }),
      });

      if (res.status === 401) {
        setStatus({ type: "error", msg: `Invalid API key for ${provider}.` });
      } else if (res.status === 400) {
        const data = await res.json();
        setStatus({ type: "error", msg: data.error || "Bad request." });
      } else {
        const models = await res.json();
        setStatus({ type: "success", msg: `Registered ${models.length} models from ${provider}.` });
        setApiKey("");
        setProvider("");
        setRegistryKey((k) => k + 1);
        if (onRegistryUpdate) {
          onRegistryUpdate();
        }
      }
    } catch (err) {
      setStatus({ type: "error", msg: "Could not reach the Gateway." });
    } finally {
      setRegistering(false);
    }
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <h1>diplomatAI</h1>
        <p>Multi-LLM Gateway</p>
      </div>

      {/* 1. Chat History */}
      <div className="sidebar-section" style={{ flex: 1, minHeight: 0, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
        <button className="btn btn-new-chat" onClick={onNewChat} style={{ flexShrink: 0 }}>
          <span style={{ fontSize: '1.2rem', lineHeight: '1' }}>+</span> New Chat
        </button>
        <div className="history-list">
          {sessions.map(s => (
            <div 
              key={s.id} 
              className={`history-item ${s.id === currentSessionId ? 'active' : ''}`}
              onClick={() => onSelectSession(s.id)}
            >
              {editingId === s.id ? (
                <input 
                  type="text" 
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => handleEditKeyDown(e, s.id)}
                  onBlur={() => { onRenameSession(s.id, editTitle); setEditingId(null); }}
                  autoFocus
                  style={{ flex: 1, fontSize: '0.8rem', background: 'var(--bg-primary)', color: 'var(--text-primary)', border: '1px solid var(--border)', borderRadius: '4px', padding: '2px 4px', marginRight: '4px' }}
                />
              ) : (
                <div className="history-title" onDoubleClick={(e) => startEdit(s.id, s.title, e)}>
                  {s.title || "New Chat"}
                </div>
              )}
              <div style={{ display: 'flex', gap: '2px', alignItems: 'center' }}>
                <button 
                  className="btn-icon" 
                  onClick={(e) => startEdit(s.id, s.title, e)}
                  title="Rename"
                  style={{ fontSize: '0.7rem', padding: '2px 4px' }}
                >
                  ✎
                </button>
                <button 
                  className="btn-icon" 
                  onClick={(e) => {
                    e.stopPropagation();
                    if (window.confirm("Are you sure you want to delete this chat session?")) {
                      onDeleteSession(s.id);
                    }
                  }}
                  title="Delete"
                  style={{ fontSize: '0.7rem', padding: '2px 4px' }}
                >
                  ✕
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 2. API Keys & Models */}
      <div className="sidebar-section" style={{ flexShrink: 0, borderTop: '1px solid var(--border)', paddingTop: '16px', marginTop: 'auto' }}>
        <div className="sidebar-section-title" style={{ margin: 0, marginBottom: '12px' }}>
          API Keys & Models
        </div>
        <div>
          <form className="provider-form" onSubmit={handleRegister} style={{ marginBottom: "16px" }}>
            <select
              className="form-select"
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
            >
              <option value="" disabled>Select API Provider...</option>
              {PROVIDERS.map((p) => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
            <input
              className="form-input"
              type="password"
              placeholder="API Key"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
            <button className="btn btn-primary" type="submit" disabled={registering}>
              {registering ? "Validating..." : "Register Models"}
            </button>
          </form>
          {status && (
            <div className={`status-msg ${status.type}`} style={{ marginBottom: "12px" }}>{status.msg}</div>
          )}
          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '8px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Registered Models</div>
          <div style={{ maxHeight: '150px', overflowY: 'auto', paddingRight: '4px' }}>
            <ModelRegistry gatewayUrl={gatewayUrl} refreshKey={registryKey} onRegistryUpdate={onRegistryUpdate} />
          </div>
        </div>
      </div>

      {/* 3. Performance Status (Collapsable) */}
      <div className="sidebar-section" style={{ flexShrink: 0 }}>
        <details>
          <summary className="sidebar-section-title" style={{ cursor: "pointer", outline: "none", margin: 0 }}>
            Performance Status
          </summary>
          <div style={{ marginTop: "12px" }}>
            <MetricsPanel metrics={metrics} />
            <button className="btn btn-secondary" style={{width: '100%', marginTop: '12px'}} onClick={onClearCache}>Clear Cache</button>
          </div>
        </details>
      </div>
    </aside>
  );
}
