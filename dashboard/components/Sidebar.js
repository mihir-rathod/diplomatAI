"use client";

import { useState } from "react";
import ModelRegistry from "./ModelRegistry";
import MetricsPanel from "./MetricsPanel";

const PROVIDERS = ["OpenAI", "Gemini", "Anthropic", "Groq", "Mistral", "OpenRouter", "Together"];

export default function Sidebar({ metrics, gatewayUrl, onClearChat, onClearCache }) {
  const [provider, setProvider] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [status, setStatus] = useState(null);
  const [registering, setRegistering] = useState(false);
  const [registryKey, setRegistryKey] = useState(0);

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
        <p>AI Traffic Controller</p>
      </div>

      {/* Provider Registration */}
      <div className="sidebar-section">
        <div className="sidebar-section-title">Add Provider</div>
        <form className="provider-form" onSubmit={handleRegister}>
          <select
            className="form-select"
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            <option value="" disabled>Select a provider...</option>
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
          <div className={`status-msg ${status.type}`}>{status.msg}</div>
        )}
      </div>

      {/* Model Registry */}
      <div className="sidebar-section">
        <div className="sidebar-section-title">Model Registry</div>
        <ModelRegistry gatewayUrl={gatewayUrl} refreshKey={registryKey} />
      </div>

      {/* Metrics */}
      <div className="sidebar-section">
        <div className="sidebar-section-title">Last Response</div>
        <MetricsPanel metrics={metrics} />
      </div>

      {/* Actions */}
      <div className="sidebar-actions">
        <button className="btn btn-secondary" onClick={onClearChat}>Clear Chat</button>
        <button className="btn btn-secondary" onClick={onClearCache}>Clear Cache</button>
      </div>
    </aside>
  );
}
