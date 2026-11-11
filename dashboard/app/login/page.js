"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

const AUTH_URL = process.env.NEXT_PUBLIC_AUTH_URL || "http://localhost:8080/api/v1/auth";

export default function LoginPage() {
  const router = useRouter();
  const [tab, setTab] = useState("signin"); // "signin" | "register"
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    const endpoint = tab === "signin" ? `${AUTH_URL}/login` : `${AUTH_URL}/register`;

    try {
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });

      const data = await res.json();

      if (!res.ok) {
        setError(data.error || "Something went wrong.");
        return;
      }

      localStorage.setItem("diplomat_token", data.token);
      localStorage.setItem("diplomat_user", JSON.stringify(data.user));
      router.push("/");
    } catch {
      setError("Could not reach the gateway. Make sure the services are running.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-brand">
          <div className="auth-logo">⚡</div>
          <h1 className="auth-title">diplomatAI</h1>
          <p className="auth-subtitle">Multi-LLM Gateway</p>
        </div>

        <div className="auth-tabs">
          <button
            className={`auth-tab ${tab === "signin" ? "active" : ""}`}
            onClick={() => { setTab("signin"); setError(null); }}
          >
            Sign In
          </button>
          <button
            className={`auth-tab ${tab === "register" ? "active" : ""}`}
            onClick={() => { setTab("register"); setError(null); }}
          >
            Create Account
          </button>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          <div className="auth-field">
            <label className="auth-label">Email</label>
            <input
              id="auth-email"
              className="auth-input"
              type="email"
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoFocus
            />
          </div>

          <div className="auth-field">
            <label className="auth-label">Password</label>
            <input
              id="auth-password"
              className="auth-input"
              type="password"
              placeholder={tab === "register" ? "Min 8 characters" : "Your password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={tab === "register" ? 8 : 1}
            />
          </div>

          {error && <div className="auth-error">{error}</div>}

          <button className="auth-submit" type="submit" disabled={loading}>
            {loading ? (
              <span className="auth-loading">
                <span className="spinner" />
                {tab === "signin" ? "Signing in..." : "Creating account..."}
              </span>
            ) : (
              tab === "signin" ? "Sign In" : "Create Account"
            )}
          </button>
        </form>

        <p className="auth-footer">
          Your API keys and sessions are private to your account.
        </p>
      </div>
    </div>
  );
}
