from conftest import add_model, psql, unique


def test_api_key_is_encrypted_at_rest_and_only_a_masked_hint_is_returned(user, add_mock_model):
    api_key = f"gsk_{unique('plainkey')}abcd"
    model_id, _ = add_mock_model(user, "Groq", api_key=api_key)

    stored = psql(f"select api_key_encrypted from registered_models where user_id={user.id} and model_id='{model_id}'")
    assert api_key not in stored
    assert stored

    registry = user.get("/chat/models/registry").json()
    assert len(registry) == 1
    entry = registry[0]
    assert entry["id"] == model_id
    assert entry["apiKeyHint"] == f"{api_key[:3]}...{api_key[-4:]}"
    assert "apiKey" not in entry
    assert api_key not in str(entry)


def test_registry_is_scoped_per_user(user, other_user, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")

    assert [m["id"] for m in user.get("/chat/models/registry").json()] == [model_id]
    assert other_user.get("/chat/models/registry").json() == []


def test_another_user_cannot_delete_my_model_but_I_can(user, other_user, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")

    assert other_user.delete(f"/chat/models/registry/{model_id}").status_code == 404
    assert len(user.get("/chat/models/registry").json()) == 1

    assert user.delete(f"/chat/models/registry/{model_id}").status_code == 200
    assert user.get("/chat/models/registry").json() == []
    assert user.delete(f"/chat/models/registry/{model_id}").status_code == 404


def test_model_ids_containing_slashes_and_colons_can_be_deleted(user, add_mock_model):
    # OpenRouter / Together ids look like this
    model_id, _ = add_mock_model(user, "OpenRouter", model_id=f"meta-llama/{unique('llama')}:free")

    assert user.delete(f"/chat/models/registry/{model_id}").status_code == 200
    assert user.get("/chat/models/registry").json() == []


def test_unsupported_provider_is_a_400(user):
    r = user.post("/chat/models", {"provider": "NotAProvider", "apiKey": "x"})
    assert r.status_code == 400


def test_an_invalid_provider_key_is_rejected_and_nothing_is_stored(user):
    r = user.post("/chat/models", {"provider": "Groq", "apiKey": "gsk_definitely_not_valid"})
    assert r.status_code == 401
    assert user.get("/chat/models/registry").json() == []
