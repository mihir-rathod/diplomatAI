"use client";

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { vscDarkPlus } from "react-syntax-highlighter/dist/esm/styles/prism";

export default function ChatWindow({ messages, chatEndRef, onRegenerate, loading }) {
  if (messages.length === 0) {
    return (
      <div className="chat-window">
        <div className="chat-empty">
          <h2>diplomatAI</h2>
          <p>Send a message to get started.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="chat-window">
      {messages.map((msg, i) => (
        <div key={msg.id || i} className={`chat-message ${msg.role}`}>
          <div className="message-inner">
            <div className={`message-role ${msg.role}`}>
              {msg.role === "user" ? "You" : (
                <>diplomatAI <span className={`message-model-tag tag-${(msg.provider || "cache").toLowerCase()}`}>{msg.model || ""}</span></>
              )}
            </div>
            <div className="message-content">
              {msg.role !== "user" && msg.wasRerouted && (
                <div className="badge-reroute">
                  ⚡ Rerouted from {msg.originalModel} → {msg.model}
                </div>
              )}
              {msg.role === "user" ? (
                msg.content
              ) : (
                <>
                  <ReactMarkdown
                    remarkPlugins={[remarkGfm]}
                    components={{
                      code({ node, inline, className, children, ...props }) {
                        const match = /language-(\w+)/.exec(className || "");
                        return !inline && match ? (
                          <SyntaxHighlighter
                            {...props}
                            style={vscDarkPlus}
                            language={match[1]}
                            PreTag="div"
                            className="md-code-block"
                          >
                            {String(children).replace(/\n$/, "")}
                          </SyntaxHighlighter>
                        ) : (
                          <code {...props} className={className}>
                            {children}
                          </code>
                        );
                      },
                    }}
                  >
                    {msg.content}
                  </ReactMarkdown>
                  {msg.isCachedHit && onRegenerate && (
                    <div style={{ marginTop: '12px' }}>
                      <button 
                        onClick={() => onRegenerate(messages[i - 1]?.content || "")}
                        className="btn" 
                        style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border)', fontSize: '0.75rem', padding: '4px 8px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer', borderRadius: '4px', color: 'var(--text-primary)' }}
                        onMouseOver={(e) => e.currentTarget.style.background = 'var(--border)'}
                        onMouseOut={(e) => e.currentTarget.style.background = 'var(--bg-secondary)'}
                      >
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M21 2v6h-6"></path><path d="M3 12a9 9 0 0 1 15-6.7L21 8"></path><path d="M3 22v-6h6"></path><path d="M21 12a9 9 0 0 1-15 6.7L3 16"></path></svg>
                        Regenerate without Cache
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      ))}
      
      {loading && (
        <div className="chat-message assistant generating">
          <div className="message-inner">
            <div className="message-role assistant">
              diplomatAI
            </div>
            <div className="message-content">
              <div className="typing-indicator">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </div>
        </div>
      )}
      
      <div ref={chatEndRef} />
    </div>
  );
}
