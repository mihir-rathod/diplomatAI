"use client";

import { useState } from "react";
import ModelRegistry from "./ModelRegistry";
import MetricsPanel from "./MetricsPanel";

const PROVIDERS = ["OpenAI", "Gemini", "Anthropic", "Groq", "Mistral", "OpenRouter", "Together"];

export default function RightSidebar({ 
  metrics, 
  gatewayUrl, 
  onClearCache, 
  onRegistryUpdate 
}) {
  const [provider, setProvider] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [status, setStatus] = useState(null);
  const [registering, setRegistering] = useState(false);
  const [registryKey, setRegistryKey] = useState(0);
  const [clearingCache, setClearingCache] = useState(false);

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

  const handleClearCache = async () => {
    setClearingCache(true);
    try {
      await onClearCache();
    } finally {
      setClearingCache(false);
    }
  };

  return (
    <aside className="sidebar right">
      {/* 2. API Keys & Models */}
      <div className="sidebar-section" style={{ flexShrink: 0, paddingTop: '20px' }}>
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
              {registering && <span className="spinner" />}
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
      <div className="sidebar-section" style={{ flexShrink: 0, marginTop: 'auto', borderTop: '1px solid var(--border)' }}>
        <details open>
          <summary className="sidebar-section-title" style={{ cursor: "pointer", outline: "none", margin: 0 }}>
            Routing & Usage
          </summary>
          <div style={{ marginTop: "12px" }}>
            <MetricsPanel metrics={metrics} sessionUsage={metrics.sessionUsage || {}} />
            <button 
              className="btn btn-secondary" 
              style={{width: '100%', marginTop: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px'}} 
              onClick={handleClearCache}
              disabled={clearingCache}
            >
              {clearingCache && <span className="spinner" />}
              {clearingCache ? "Clearing..." : "Clear Cache"}
            </button>
          </div>
        </details>
      </div>
    </aside>
  );
}
