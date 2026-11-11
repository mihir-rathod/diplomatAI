import requests

from conftest import BASE, register_user, unique, EMAIL_DOMAIN


def test_chat_requires_a_token():
    r = requests.post(f"{BASE}/chat", json={"prompt": "hi"}, timeout=30)
    assert r.status_code == 401


def test_garbage_token_is_rejected():
    r = requests.get(f"{BASE}/sessions", headers={"Authorization": "Bearer not-a-real-token"}, timeout=30)
    assert r.status_code == 401


def test_health_is_public():
    assert requests.get(f"{BASE}/chat/health", timeout=30).status_code == 200


def test_malformed_json_is_a_400_not_masked_as_401():
    r = requests.post(f"{BASE}/auth/register", data=b"{not json", headers={"Content-Type": "application/json"}, timeout=30)
    assert r.status_code == 400


def test_short_password_is_rejected():
    r = requests.post(f"{BASE}/auth/register", json={"email": f"e2e-{unique()}@{EMAIL_DOMAIN}", "password": "abc"}, timeout=30)
    assert r.status_code == 400


def test_register_login_and_me():
    u = register_user()
    login = requests.post(f"{BASE}/auth/login", json={"email": u.email, "password": u.password}, timeout=30)
    assert login.status_code == 200
    assert login.json()["user"]["email"] == u.email

    me = u.get("/auth/me")
    assert me.status_code == 200
    assert me.json()["email"] == u.email


def test_duplicate_email_conflicts():
    u = register_user()
    r = requests.post(f"{BASE}/auth/register", json={"email": u.email, "password": "password123"}, timeout=30)
    assert r.status_code == 409


def test_wrong_password_and_unknown_email_are_both_401():
    u = register_user()
    wrong = requests.post(f"{BASE}/auth/login", json={"email": u.email, "password": "wrong-password"}, timeout=30)
    unknown = requests.post(f"{BASE}/auth/login", json={"email": f"nobody-{unique()}@{EMAIL_DOMAIN}", "password": "password123"}, timeout=30)
    assert wrong.status_code == 401
    assert unknown.status_code == 401
