"use client";

import { useState, useEffect } from "react";

export default function ModelRegistry({ gatewayUrl, refreshKey }) {
  const [models, setModels] = useState([]);

  const fetchRegistry = async () => {
    try {
      const res = await fetch(`${gatewayUrl}/models/registry`);
      const data = await res.json();
      setModels(data || []);
    } catch {
      setModels([]);
    }
  };

  useEffect(() => {
    fetchRegistry();
  }, [refreshKey]);

  const handleDelete = async (modelId) => {
    try {
      await fetch(`${gatewayUrl}/models/registry/${modelId}`, { method: "DELETE" });
      fetchRegistry();
    } catch (err) {
      console.error("Delete failed:", err);
    }
  };

  if (models.length === 0) {
    return <p className="registry-empty">No models registered yet.</p>;
  }

  return (
    <div>
      {models.map((m) => (
        <div key={m.id} className="registry-item">
          <div>
            <div className="registry-model-name">{m.name || m.id}</div>
            <div className="registry-model-meta">
              {m.provider} &middot; {m.apiKey ? "••••••" : "No key"}
            </div>
          </div>
          <button className="btn btn-danger" onClick={() => handleDelete(m.id)}>
            Delete
          </button>
        </div>
      ))}
    </div>
  );
}
