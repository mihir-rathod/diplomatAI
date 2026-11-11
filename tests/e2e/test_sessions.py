from conftest import unique


def test_session_lifecycle(user):
    sid = user.new_session()
    assert [s["id"] for s in user.get("/sessions").json()] == [sid]

    renamed = user.patch(f"/sessions/{sid}", {"title": "Renamed"})
    assert renamed.status_code == 200
    assert renamed.json()["title"] == "Renamed"
    assert user.get("/sessions").json()[0]["title"] == "Renamed"

    assert user.delete(f"/sessions/{sid}").status_code == 200
    assert user.get("/sessions").json() == []
    assert user.get(f"/sessions/{sid}/messages").status_code == 404


def test_chat_turn_is_persisted_with_model_and_provider(user, add_mock_model):
    model_id, _ = add_mock_model(user, "Groq")
    sid = user.new_session()
    prompt = unique("persist")

    reply = user.chat(prompt, session_id=sid)
    assert reply.status_code == 200

    msgs = user.messages(sid)
    assert [m["role"] for m in msgs] == ["user", "assistant"]
    assert msgs[0]["content"] == prompt
    assert msgs[1]["content"] == reply.json()["answer"]
    assert msgs[1]["model"] == model_id
    assert msgs[1]["provider"] == "groq"
    assert msgs[1]["cachedHit"] is False


def test_another_user_cannot_read_rename_or_delete_a_session(user, other_user):
    sid = user.new_session("private")

    assert other_user.get(f"/sessions/{sid}/messages").status_code == 404
    assert other_user.patch(f"/sessions/{sid}", {"title": "hijacked"}).status_code == 404
    assert other_user.delete(f"/sessions/{sid}").status_code == 404
    assert other_user.get("/sessions").json() == []
    assert user.get("/sessions").json()[0]["title"] == "private"


def test_chatting_into_someone_elses_session_writes_nothing_to_it(user, other_user):
    sid = user.new_session()

    other_user.chat(unique("inject"), session_id=sid)

    assert user.messages(sid) == []
