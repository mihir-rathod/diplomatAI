"use client";

import { useState } from "react";

export default function LeftSidebar({
  sessions = [], 
  currentSessionId, 
  onNewChat, 
  onSelectSession, 
  onDeleteSession, 
  onRenameSession,
  width = 300
}) {
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

  return (
    <aside className="sidebar left" style={{ width: `${width}px`, minWidth: `${width}px` }}>
      <div className="sidebar-header">
        <h1>diplomatAI</h1>
        <p>Multi-LLM Gateway</p>
      </div>

      {/* 1. Chat History */}
      <div className="sidebar-section" style={{ flex: 1, minHeight: 0, overflowY: 'hidden', display: 'flex', flexDirection: 'column' }}>
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

      {/* Future Account & Memory Section */}
      <div className="profile-placeholder">
        <div className="profile-avatar">M</div>
        <div className="profile-info">
          <span className="profile-name">My Account</span>
          <span className="profile-role">Pro Tier</span>
        </div>
      </div>
    </aside>
  );
}
