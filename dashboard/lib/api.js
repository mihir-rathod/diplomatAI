// Auth utility — token management and API calls with JWT

const GATEWAY_URL = process.env.NEXT_PUBLIC_GATEWAY_URL || "http://localhost:8080/api/v1/chat";
const AUTH_URL = process.env.NEXT_PUBLIC_AUTH_URL || "http://localhost:8080/api/v1/auth";

// ── Token storage ──────────────────────────────────────────────────────────────

export function getToken() {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("diplomat_token");
}

export function getUser() {
  if (typeof window === "undefined") return null;
  try {
    return JSON.parse(localStorage.getItem("diplomat_user") || "null");
  } catch {
    return null;
  }
}

export function isLoggedIn() {
  return !!getToken();
}

export function logout() {
  localStorage.removeItem("diplomat_token");
  localStorage.removeItem("diplomat_user");
  window.location.href = "/login";
}

// ── Authenticated fetch ────────────────────────────────────────────────────────

export async function apiFetch(url, options = {}) {
  const token = getToken();
  const headers = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  };

  const res = await fetch(url, { ...options, headers });

  // If the gateway says 401, the token expired — redirect to login
  if (res.status === 401) {
    logout();
    throw new Error("Session expired. Please sign in again.");
  }

  return res;
}

// ── Sessions API ───────────────────────────────────────────────────────────────

export async function fetchSessions() {
  const res = await apiFetch(`${GATEWAY_URL.replace("/chat", "/sessions")}`);
  if (!res.ok) throw new Error("Failed to fetch sessions");
  return res.json();
}

export async function createSession(title = "New Chat") {
  const res = await apiFetch(`${GATEWAY_URL.replace("/chat", "/sessions")}`, {
    method: "POST",
    body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error("Failed to create session");
  return res.json();
}

export async function renameSession(sessionId, title) {
  const res = await apiFetch(`${GATEWAY_URL.replace("/chat", "/sessions")}/${sessionId}`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error("Failed to rename session");
  return res.json();
}

export async function deleteSession(sessionId) {
  const res = await apiFetch(`${GATEWAY_URL.replace("/chat", "/sessions")}/${sessionId}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error("Failed to delete session");
  return res.json();
}

// ── Messages API ───────────────────────────────────────────────────────────────

export async function fetchMessages(sessionId) {
  const res = await apiFetch(`${GATEWAY_URL.replace("/chat", "/sessions")}/${sessionId}/messages`);
  if (!res.ok) throw new Error("Failed to fetch messages");
  return res.json();
}

// ── Gateway chat ───────────────────────────────────────────────────────────────

export async function sendMessage(prompt, messages, sessionId, modelId, useCache) {
  const res = await apiFetch(GATEWAY_URL, {
    method: "POST",
    body: JSON.stringify({
      prompt,
      messages,
      sessionId: sessionId || null,
      modelId: modelId || null,
      useCache: useCache !== false,
    }),
  });
  if (!res.ok) {
    const err = await res.text();
    throw new Error(err || "Gateway error");
  }
  return res.json();
}

// ── Model registry ─────────────────────────────────────────────────────────────

export async function fetchRegistry() {
  const res = await apiFetch(`${GATEWAY_URL}/models/registry`);
  return res.ok ? res.json() : [];
}

export { GATEWAY_URL, AUTH_URL };
