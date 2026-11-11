"""Cache behaviour: scoped per user and per selected model, conversation-aware, never poisoned."""
from conftest import unique


def metrics(reply):
    return reply.json()["metrics"]


def test_a_repeated_standalone_prompt_is_served_from_cache(user, provider, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")
    prompt = unique("repeat")

    first = user.chat(prompt)
    second = user.chat(prompt)

    assert metrics(first)["cache_hit"] is False
    assert metrics(second)["cache_hit"] is True
    assert metrics(second)["model_routed"] == "cache"
    assert metrics(second)["total_tokens"] == 0
    assert second.json()["answer"] == first.json()["answer"]
    assert len(provider.calls_for(model_id)) == 1


def test_use_cache_false_bypasses_the_cache(user, provider, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")
    prompt = unique("bypass")

    user.chat(prompt)
    again = user.chat(prompt, use_cache=False)

    assert metrics(again)["cache_hit"] is False
    assert len(provider.calls_for(model_id)) == 2


def test_cache_is_not_shared_between_users(user, other_user, provider, add_mock_model):
    add_mock_model(user, "Groq")
    add_mock_model(other_user, "Groq")
    prompt = unique("private")

    user.chat(prompt)
    theirs = other_user.chat(prompt)

    assert metrics(theirs)["cache_hit"] is False
    assert provider.total_calls == 2


def test_cache_is_scoped_to_the_selected_model(user, provider, add_mock_model):
    groq_id, _ = add_mock_model(user, "Groq")
    mistral_id, _ = add_mock_model(user, "Mistral")
    prompt = unique("pinned")

    user.chat(prompt, model_id=groq_id)
    pinned_other = user.chat(prompt, model_id=mistral_id)
    pinned_same = user.chat(prompt, model_id=groq_id)

    assert metrics(pinned_other)["cache_hit"] is False
    assert pinned_other.json()["answer"].startswith(f"mock answer from {mistral_id}")
    assert metrics(pinned_same)["cache_hit"] is True


def test_a_follow_up_is_not_answered_from_another_conversations_cache(user, provider, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")
    follow_up = unique("explain more")

    standalone = user.chat(follow_up)  # cached as a standalone prompt
    in_context = user.chat(follow_up, history=[
        {"role": "user", "content": "what is redis"},
        {"role": "assistant", "content": "an in-memory data store"},
    ])

    assert metrics(standalone)["cache_hit"] is False
    assert metrics(in_context)["cache_hit"] is False
    calls = provider.calls_for(model_id)
    assert len(calls) == 2
    assert len(calls[1]["messages"]) == 3  # the history really was forwarded to the provider


def test_a_follow_up_answer_is_never_written_to_the_cache(user, provider, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")
    follow_up = unique("and then")

    user.chat(follow_up, history=[
        {"role": "user", "content": "tell me about rome"},
        {"role": "assistant", "content": "it is a city"},
    ])
    standalone = user.chat(follow_up)

    assert metrics(standalone)["cache_hit"] is False
    assert len(provider.calls_for(model_id)) == 2


def test_the_gateways_no_models_fallback_text_is_not_cached(user, provider, add_mock_model):
    prompt = unique("fallback")

    before = user.chat(prompt)  # no models registered yet -> internal fallback message
    assert metrics(before)["provider"] == "internal"

    model_id, _ = add_mock_model(user, "Groq")
    after = user.chat(prompt)

    assert metrics(after)["cache_hit"] is False
    assert after.json()["answer"].startswith(f"mock answer from {model_id}")


def test_clear_cache_only_clears_my_entries(user, other_user, provider, add_mock_model):
    add_mock_model(user, "Groq")
    add_mock_model(other_user, "Groq")
    prompt = unique("clear")
    user.chat(prompt)
    other_user.chat(prompt)

    cleared = user.delete("/chat/cache")

    assert cleared.status_code == 200
    assert cleared.json()["entries_removed"] >= 1
    assert metrics(user.chat(prompt))["cache_hit"] is False
    assert metrics(other_user.chat(prompt))["cache_hit"] is True


def test_a_cache_hit_is_saved_to_the_chat_history(user, provider, add_mock_model):
    add_mock_model(user, "Groq")
    prompt = unique("history")
    user.chat(prompt)

    sid = user.new_session()
    user.chat(prompt, session_id=sid)

    msgs = user.messages(sid)
    assert [m["role"] for m in msgs] == ["user", "assistant"]
    assert msgs[1]["cachedHit"] is True
    assert msgs[1]["model"] == "cache"


def test_a_reworded_prompt_hits_the_semantic_cache(user, provider, add_mock_model, semantic_service_ready):
    model_id, _ = add_mock_model(user, "Groq")

    user.chat("write bubble sort in python")
    reworded = user.chat("can you write me bubble sort in python")

    assert metrics(reworded)["cache_hit"] is True
    assert metrics(reworded)["model_routed"] == "semantic-cache"
    assert metrics(reworded)["semantic_similarity"] >= 0.8
    assert len(provider.calls_for(model_id)) == 1


def test_the_semantic_cache_does_not_match_across_users(user, other_user, provider, add_mock_model, semantic_service_ready):
    add_mock_model(user, "Groq")
    add_mock_model(other_user, "Groq")

    user.chat("write bubble sort in python")
    theirs = other_user.chat("can you write me bubble sort in python")

    assert metrics(theirs)["cache_hit"] is False
