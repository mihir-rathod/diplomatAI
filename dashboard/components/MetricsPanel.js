"use client";

export default function MetricsPanel({ metrics, sessionUsage }) {
  const m = metrics || {};
  
  // Clean provider names for styling mappings
  const getProviderClass = (provider) => {
    if (!provider) return "";
    const p = provider.toLowerCase();
    return `tag-${p}`;
  };

  const PROVIDER_LIMITS = {
    groq: 14400,
    openai: 30000,
    gemini: 32000,
    anthropic: 40000,
    mistral: 500000,
    openrouter: 100000,
    together: 100000,
    cache: 1000000 
  };

  const hasUsage = sessionUsage && Object.keys(sessionUsage).length > 0;

  const formatTokens = (val) => {
    if (val >= 1000) return (val / 1000).toFixed(1) + "k";
    return val;
  };

  return (
    <div className="metrics-panel">
      
      {/* ─── ROUTING STATUS ─── */}
      <div className="sidebar-section-title" style={{ marginTop: '0' }}>Routing Status</div>
      <div className="metric-row">
        <span className="metric-label">Model</span>
        <span className={`metric-value ${m.model_routed ? 'positive' : 'neutral'}`}>{m.model_routed || "-"}</span>
      </div>
      <div className="metric-row">
        <span className="metric-label">Cache</span>
        <span className={`metric-value ${m.cache_hit === null ? 'neutral' : (m.cache_hit ? 'positive' : 'neutral')}`}>
          {m.cache_hit === null ? "N/A" : (m.cache_hit ? "Hit" : "Miss")}
        </span>
      </div>
      
      {m.fallback_triggered && (
        <div className="metric-row">
          <span className="metric-label">Rerouted</span>
          <span className="metric-value warning" style={{color: "var(--warning)"}}>
            {m.original_model} <span style={{fontSize: "0.6rem"}}>→</span> {m.model_routed}
          </span>
        </div>
      )}

      <div className="metric-row">
        <span className="metric-label">Latency</span>
        <span className="metric-value neutral">{m.latency_ms > 0 ? `${m.latency_ms} ms` : "-"}</span>
      </div>

      {/* ─── TOKEN USAGE (THIS REQUEST) ─── */}
      <div className="sidebar-section-title" style={{ marginTop: '16px' }}>Token Usage (Current)</div>
      <div className="metric-row">
        <span className="metric-label">Prompt</span>
        <span className="metric-value">{m.prompt_tokens || 0}</span>
      </div>
      <div className="metric-row">
        <span className="metric-label">Completion</span>
        <span className="metric-value">{m.completion_tokens || 0}</span>
      </div>
      <div className="metric-row">
        <span className="metric-label">Total</span>
        <span className="metric-value" style={{fontWeight: '600'}}>{m.total_tokens || 0}</span>
      </div>

      {/* ─── SESSION USAGE BY PROVIDER ─── */}
      {hasUsage && (
        <>
          <div className="sidebar-section-title" style={{ marginTop: '16px' }}>Session Usage</div>
          <div style={{ marginTop: '8px' }}>
            {Object.entries(sessionUsage).sort((a, b) => b[1] - a[1]).map(([provider, tokens]) => {
              // 1. Try dynamic live limit, 2. Hardcoded fallback, 3. Absolute fallback
              const liveLimit = m.liveLimits ? m.liveLimits[provider] : null;
              const limit = liveLimit || PROVIDER_LIMITS[provider] || 50000;
              const ratio = tokens / limit;
              const width = Math.min(100, Math.max(2, ratio * 100));
              
              // Change color to warning if > 75%, error if > 95%
              let bgStyle = { width: `${width}%` };
              if (ratio >= 0.95) {
                bgStyle.backgroundColor = "var(--error)";
              } else if (ratio >= 0.75) {
                bgStyle.backgroundColor = "var(--warning)";
              }

              return (
                <div key={provider} className="provider-row">
                  <div className="provider-stats">
                    <span style={{textTransform: 'capitalize'}}>{provider}</span>
                    <span>{formatTokens(tokens)} / {liveLimit ? formatTokens(liveLimit) : formatTokens(limit)}</span>
                  </div>
                  <div className="usage-bar-container">
                    <div 
                      className={`usage-bar-fill ${ratio < 0.75 ? getProviderClass(provider) : ''}`} 
                      style={bgStyle} 
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}

    </div>
  );
}
