"use client";

export default function ChatWindow({ messages, chatEndRef }) {
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
        <div key={i} className={`chat-message ${msg.role}`}>
          <div className="message-inner">
            <div className={`message-role ${msg.role}`}>
              {msg.role === "user" ? "You" : "diplomatAI"}
            </div>
            <div className="message-content">{msg.content}</div>
          </div>
        </div>
      ))}
      <div ref={chatEndRef} />
    </div>
  );
}
