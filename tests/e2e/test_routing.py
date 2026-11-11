"""Routing, per-user keys and failover, driven against the mock provider.

Prompts are chosen around the router's keyword lists: "python function" is a coding prompt
(priority groq > mistral), "Describe the Roman empire" matches no keyword so it is general
(priority mistral > groq among these two).
"""
from conftest import unique

CODING = "write a python function to {}"
GENERAL = "Describe the Roman empire {}"


def metrics(reply):
    return reply.json()["metrics"]


def test_routing_only_uses_the_callers_own_models(user, other_user, provider, add_mock_model):
    add_mock_model(user, "Groq")

    theirs = other_user.chat(unique("nomodels"))

    assert metrics(theirs)["provider"] == "internal"
    assert provider.total_calls == 0


def test_each_user_calls_the_provider_with_their_own_key(user, other_user, provider, add_mock_model):
    model_a, key_a = add_mock_model(user, "Groq")
    model_b, key_b = add_mock_model(other_user, "Groq")

    user.chat(unique("keyA"))
    other_user.chat(unique("keyB"))

    assert provider.calls_for(model_a)[0]["authorization"] == f"Bearer {key_a}"
    assert provider.calls_for(model_b)[0]["authorization"] == f"Bearer {key_b}"


def test_prompt_type_decides_the_provider(user, provider, add_mock_model):
    groq_id, _ = add_mock_model(user, "Groq")
    mistral_id, _ = add_mock_model(user, "Mistral")

    coding = user.chat(CODING.format(unique("sort")))
    # not "history": it contains "hi", which the router's fast-prompt keyword list matches by substring
    general = user.chat(GENERAL.format(unique("rome")))

    assert metrics(coding)["model_routed"] == groq_id
    assert metrics(general)["model_routed"] == mistral_id


def test_a_pinned_model_is_honoured(user, provider, add_mock_model):
    add_mock_model(user, "Groq")
    mistral_id, _ = add_mock_model(user, "Mistral")

    reply = user.chat(CODING.format(unique("pinned")), model_id=mistral_id)

    assert metrics(reply)["model_routed"] == mistral_id


def test_failover_moves_to_the_next_model_when_the_first_fails(user, provider, add_mock_model):
    groq_id, _ = add_mock_model(user, "Groq")
    mistral_id, _ = add_mock_model(user, "Mistral")
    provider.fail(groq_id, 500)

    reply = user.chat(CODING.format(unique("failover")))

    m = metrics(reply)
    assert reply.json()["answer"].startswith(f"mock answer from {mistral_id}")
    assert m["fallback_triggered"] is True
    assert m["original_model"] == groq_id
    assert m["model_routed"] == mistral_id
    assert len(provider.calls_for(groq_id)) >= 1
    assert len(provider.calls_for(mistral_id)) == 1


def test_when_every_model_fails_the_error_is_not_saved_or_cached(user, provider, add_mock_model):
    groq_id, _ = add_mock_model(user, "Groq")
    mistral_id, _ = add_mock_model(user, "Mistral")
    provider.fail(groq_id, 500)
    provider.fail(mistral_id, 500)
    sid = user.new_session()
    prompt = CODING.format(unique("allfail"))

    reply = user.chat(prompt, session_id=sid)

    assert reply.status_code == 200
    assert reply.json()["answer"].startswith("System Error")
    assert user.messages(sid) == []

    # the failure must not have been cached: once the providers recover, the same prompt gets a real answer
    provider.failing.clear()
    recovered = user.chat(prompt)
    assert metrics(recovered)["cache_hit"] is False
    assert recovered.json()["answer"].startswith("mock answer from")


def test_a_repeatedly_failing_model_only_trips_its_own_circuit_breaker(user, other_user, provider, add_mock_model):
    bad_id, _ = add_mock_model(user, "Groq")
    good_id, _ = add_mock_model(user, "Mistral")
    theirs_id, _ = add_mock_model(other_user, "Groq")
    provider.fail(bad_id, 500)

    # Enough failures to open the breaker for this user's Groq model. Each request is still
    # answered, by failing over to Mistral.
    for _ in range(8):
        reply = user.chat(CODING.format(unique("trip")), model_id=bad_id, use_cache=False)
        assert reply.json()["answer"].startswith(f"mock answer from {good_id}")

    # Once open, the breaker stops sending traffic to the bad model instead of hammering it
    assert len(provider.calls_for(bad_id)) < 8

    # The same user's other model is unaffected
    ok = user.chat(unique("still-fine"), model_id=good_id, use_cache=False)
    assert ok.json()["answer"].startswith(f"mock answer from {good_id}")
    assert metrics(ok)["fallback_triggered"] is False

    # Another account using the same provider is unaffected
    theirs = other_user.chat(CODING.format(unique("unaffected")), use_cache=False)
    assert theirs.json()["answer"].startswith(f"mock answer from {theirs_id}")
    assert metrics(theirs)["fallback_triggered"] is False


def test_provider_usage_and_rate_limit_headers_are_surfaced(user, provider, add_mock_model):
    add_mock_model(user, "Groq")

    m = metrics(user.chat(unique("usage")))

    assert m["prompt_tokens"] == 10
    assert m["completion_tokens"] == 5
    assert m["total_tokens"] == 15
    assert m["provider"] == "groq"
    assert m["rate_limit_max"] == 1000
    assert m["rate_limit_remaining"] == 900


def test_the_full_conversation_is_forwarded_to_the_provider(user, provider, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")
    history = [
        {"role": "user", "content": "first question"},
        {"role": "assistant", "content": "first answer"},
    ]

    user.chat(unique("second question"), history=history)

    sent = provider.calls_for(model_id)[0]["messages"]
    assert [m["role"] for m in sent] == ["user", "assistant", "user"]
    assert sent[0]["content"] == "first question"
