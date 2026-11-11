"""End-to-end test harness for the diplomatAI gateway.

Tests drive the real, running stack (docker compose up) over HTTP. No real LLM keys are needed:
a mock OpenAI-compatible provider is started on the host, and models pointing at it are inserted
directly into Postgres (with the API key encrypted exactly the way the gateway does it).

Environment overrides (defaults match docker-compose.yml):
  GATEWAY_BASE, API_KEY_ENCRYPTION_SECRET, POSTGRES_CONTAINER, QC_HEALTH_URL,
  MOCK_PROVIDER_PORT, MOCK_PROVIDER_HOST (how the gateway container reaches this machine)
"""
import base64
import hashlib
import json
import os
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BASE = os.getenv("GATEWAY_BASE", "http://localhost:8080/api/v1")
ENC_SECRET = os.getenv("API_KEY_ENCRYPTION_SECRET", "diplomatai_api_key_encryption_secret_change_in_production")
POSTGRES_CONTAINER = os.getenv("POSTGRES_CONTAINER", "diplomat-postgres")
QC_HEALTH_URL = os.getenv("QC_HEALTH_URL", "http://localhost:8000/health")
MOCK_PORT = int(os.getenv("MOCK_PROVIDER_PORT", "18080"))
MOCK_HOST = os.getenv("MOCK_PROVIDER_HOST", "host.docker.internal")
EMAIL_DOMAIN = "e2e.test"


# ───────────────────────── mock provider ─────────────────────────

class MockProvider:
    """OpenAI-compatible chat endpoint that records every call and can be told to fail per model."""

    def __init__(self):
        self.calls = []
        self.failing = {}  # model id -> HTTP status to return
        self._lock = threading.Lock()

    def reset(self):
        with self._lock:
            self.calls.clear()
            self.failing.clear()

    def fail(self, model_id, status=500):
        self.failing[model_id] = status

    def calls_for(self, model_id):
        with self._lock:
            return [c for c in self.calls if c["model"] == model_id]

    @property
    def total_calls(self):
        with self._lock:
            return len(self.calls)

    @property
    def endpoint(self):
        return f"http://{MOCK_HOST}:{MOCK_PORT}/v1/chat/completions"


def _make_handler(provider):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _read_body(self):
            # The gateway's HTTP client may send the body chunked, with no Content-Length
            if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
                data = b""
                while True:
                    size = int(self.rfile.readline().strip().split(b";")[0] or b"0", 16)
                    if size == 0:
                        self.rfile.readline()
                        return data
                    data += self.rfile.read(size)
                    self.rfile.readline()
            return self.rfile.read(int(self.headers.get("Content-Length", 0)))

        def do_POST(self):
            body = json.loads(self._read_body() or b"{}")
            model = body.get("model")
            messages = body.get("messages", [])
            with provider._lock:
                provider.calls.append({
                    "model": model,
                    "messages": messages,
                    "authorization": self.headers.get("Authorization"),
                })

            status = provider.failing.get(model)
            if status:
                payload = json.dumps({"error": {"message": f"mock failure {status}"}}).encode()
                self.send_response(status)
            else:
                last_user = next((m["content"] for m in reversed(messages) if m["role"] == "user"), "")
                payload = json.dumps({
                    "choices": [{"message": {"role": "assistant", "content": f"mock answer from {model}: {last_user}"}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
                }).encode()
                self.send_response(200)
                self.send_header("x-ratelimit-limit-tokens", "1000")
                self.send_header("x-ratelimit-remaining-tokens", "900")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


@pytest.fixture(scope="session")
def _mock_server():
    provider = MockProvider()
    server = ThreadingHTTPServer(("0.0.0.0", MOCK_PORT), _make_handler(provider))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield provider
    server.shutdown()


@pytest.fixture
def provider(_mock_server):
    """The mock provider with call history and failure switches cleared for this test."""
    _mock_server.reset()
    return _mock_server


# ───────────────────────── stack + database helpers ─────────────────────────

def psql(sql):
    out = subprocess.run(
        ["docker", "exec", POSTGRES_CONTAINER, "psql", "-U", "diplomat", "-d", "diplomatai", "-tA", "-c", sql],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(f"psql failed: {out.stderr}")
    return out.stdout.strip()


def encrypt_api_key(plain):
    """Mirror of the gateway's ApiKeyCipher: AES-GCM, key = SHA-256(secret), base64(iv || ciphertext)."""
    key = hashlib.sha256(ENC_SECRET.encode()).digest()
    iv = os.urandom(12)
    return base64.b64encode(iv + AESGCM(key).encrypt(iv, plain.encode(), None)).decode()


@pytest.fixture(scope="session", autouse=True)
def _stack_ready():
    try:
        r = requests.get(f"{BASE}/chat/health", timeout=5)
        r.raise_for_status()
    except Exception as e:
        pytest.exit(f"Gateway not reachable at {BASE} ({e}). Start the stack with: docker compose up --build -d", returncode=2)
    yield
    _cleanup_test_users()


def _cleanup_test_users():
    users = f"(select id from users where email like 'e2e-%@{EMAIL_DOMAIN}')"
    psql(f"delete from messages where session_id in (select id from sessions where user_id in {users})")
    psql(f"delete from sessions where user_id in {users}")
    psql(f"delete from registered_model_capabilities where registered_model_id in "
         f"(select id from registered_models where user_id in {users})")
    psql(f"delete from registered_models where user_id in {users}")
    psql(f"delete from users where email like 'e2e-%@{EMAIL_DOMAIN}'")


@pytest.fixture(scope="session")
def semantic_service_ready():
    deadline = time.time() + 90
    while time.time() < deadline:
        try:
            if requests.get(QC_HEALTH_URL, timeout=3).status_code == 200:
                return
        except requests.RequestException:
            pass
        time.sleep(2)
    pytest.skip(f"Semantic cache service not reachable at {QC_HEALTH_URL}")


# ───────────────────────── users ─────────────────────────

class User:
    def __init__(self, email, password, token, user_id):
        self.email, self.password, self.token, self.id = email, password, token, user_id

    @property
    def headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def get(self, path, **kw):
        return requests.get(BASE + path, headers=self.headers, timeout=60, **kw)

    def post(self, path, json=None, **kw):
        return requests.post(BASE + path, json=json, headers=self.headers, timeout=60, **kw)

    def patch(self, path, json=None, **kw):
        return requests.patch(BASE + path, json=json, headers=self.headers, timeout=60, **kw)

    def delete(self, path, **kw):
        return requests.delete(BASE + path, headers=self.headers, timeout=60, **kw)

    def new_session(self, title="New Chat"):
        return self.post("/sessions", {"title": title}).json()["id"]

    def chat(self, prompt, *, session_id=None, model_id=None, use_cache=True, history=None):
        """history = earlier turns [{"role","content"}, ...]; the new prompt is appended like the dashboard does."""
        body = {
            "prompt": prompt,
            "messages": (history or []) + [{"role": "user", "content": prompt}],
            "useCache": use_cache,
        }
        if session_id is not None:
            body["sessionId"] = session_id
        if model_id is not None:
            body["modelId"] = model_id
        return self.post("/chat", body)

    def messages(self, session_id):
        return self.get(f"/sessions/{session_id}/messages").json()


def unique(label="t"):
    return f"{label}-{uuid.uuid4().hex[:8]}"


def register_user(password="password123"):
    email = f"e2e-{uuid.uuid4().hex[:10]}@{EMAIL_DOMAIN}"
    r = requests.post(f"{BASE}/auth/register", json={"email": email, "password": password}, timeout=30)
    assert r.status_code == 200, r.text
    data = r.json()
    return User(email, password, data["token"], data["user"]["id"])


@pytest.fixture
def make_user():
    return register_user


@pytest.fixture
def user():
    return register_user()


@pytest.fixture
def other_user():
    return register_user()


def add_model(owner, provider_name, model_id=None, api_key=None, endpoint=None, mock=None):
    """Register a model for `owner` that points at the mock provider (bypasses real-provider key validation)."""
    model_id = model_id or unique(provider_name.lower())
    api_key = api_key or f"sk-test-{uuid.uuid4().hex[:12]}"
    endpoint = endpoint or (mock.endpoint if mock else None)
    assert endpoint, "pass mock=provider or endpoint="
    psql(
        "insert into registered_models (user_id, model_id, name, provider, endpoint, api_key_encrypted, timeout_ms, created_at) "
        f"values ({owner.id}, '{model_id}', '{model_id} (test)', '{provider_name}', '{endpoint}', "
        f"'{encrypt_api_key(api_key)}', 15000, now())"
    )
    return model_id, api_key


@pytest.fixture
def add_mock_model(provider):
    def _add(owner, provider_name="Groq", **kw):
        return add_model(owner, provider_name, mock=provider, **kw)
    return _add
