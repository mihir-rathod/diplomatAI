"use client";

export default function MetricsPanel({ metrics }) {
  const m = metrics;

  const rows = [
    { label: "Cache", value: m.cache_hit === null ? "N/A" : (m.cache_hit ? "Hit" : "Miss"), cls: m.cache_hit === null ? "neutral" : (m.cache_hit ? "positive" : "neutral") },
    { label: "Model Routed", value: m.model_routed, cls: "neutral" },
    { label: "Fallback", value: m.model_routed === "-" ? "N/A" : (m.fallback_triggered ? "Yes" : "No"), cls: m.model_routed === "-" ? "neutral" : (m.fallback_triggered ? "negative" : "positive") },
    { label: "QC Score", value: m.model_routed === "-" ? "N/A" : `${m.qc_passed ? "Pass" : "Fail"} (${m.qc_score}/100)`, cls: m.model_routed === "-" ? "neutral" : (m.qc_passed ? "positive" : "negative") },
    { label: "Latency", value: m.latency_ms === 0 && m.model_routed === "-" ? "N/A" : `${m.latency_ms} ms`, cls: "neutral" },
  ];

  return (
    <div>
      {rows.map((r) => (
        <div key={r.label} className="metric-row">
          <span className="metric-label">{r.label}</span>
          <span className={`metric-value ${r.cls}`}>{r.value}</span>
        </div>
      ))}
    </div>
  );
}
