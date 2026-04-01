import streamlit as st
import requests
import os
from dotenv import load_dotenv

load_dotenv()

GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/chat")

st.set_page_config(
    page_title="diplomatAI Dashboard",
    page_icon="🌐",
    layout="wide",
)

st.markdown("""
<style>
    .stChatFloatingInputContainer {
        padding-bottom: 2rem;
    }
    .metric-card {
        background-color: #f0f2f6;
        padding: 1rem;
        border-radius: 0.5rem;
        margin-bottom: 1rem;
    }
    .dark-mode .metric-card {
        background-color: #262730;
    }
    .success-text { color: #00cc66; font-weight: bold; }
    .warning-text { color: #ff9900; font-weight: bold; }
    .error-text { color: #cc0000; font-weight: bold; }
</style>
""", unsafe_allow_html=True)

if "messages" not in st.session_state:
    st.session_state.messages = []
if "metrics" not in st.session_state:
    st.session_state.metrics = {
        "cache_hit": False,
        "model_routed": "None",
        "fallback_triggered": False,
        "qc_score": 0,
        "qc_passed": True,
        "latency_ms": 0
    }

with st.sidebar:
    st.title("diplomatAI Control")

    # --- Add Provider & API Key ---
    with st.expander("Add Provider", expanded=True):
        with st.form("register_form", clear_on_submit=True):
            provider = st.selectbox("Provider", ["OpenAI", "Gemini", "Anthropic", "Groq", "Mistral", "OpenRouter", "Together"])
            api_key_input = st.text_input("API Key", type="password",
                                          help="Validated against the provider before registering")
            submitted = st.form_submit_button("Register Models")

        if submitted:
            if not api_key_input:
                st.warning("Please enter an API Key.")
            else:
                with st.spinner(f"Validating key with {provider}..."):
                    try:
                        response = requests.post(
                            f"{GATEWAY_URL}/models",
                            json={"provider": provider, "apiKey": api_key_input},
                            timeout=15
                        )
                        if response.status_code == 401:
                            st.error(f"Invalid API key for {provider}. Please check and try again.")
                        elif response.status_code == 400:
                            error_data = response.json()
                            st.error(f"{error_data.get('error', 'Bad request')}")
                        else:
                            response.raise_for_status()
                            models = response.json()
                            st.success(f"Key verified! Registered {len(models)} models from {provider}.")
                    except requests.exceptions.RequestException as e:
                        st.error(f"Failed to reach Gateway: {e}")

    st.divider()

    # --- Model Registry Management ---
    with st.expander("Model Registry", expanded=False):
        if st.button("Refresh Registry"):
            st.rerun()

        try:
            reg_response = requests.get(f"{GATEWAY_URL}/models/registry", timeout=5)
            reg_response.raise_for_status()
            registry = reg_response.json()
        except Exception:
            registry = []

        if not registry:
            st.caption("No models registered yet.")
        else:
            for model in registry:
                col1, col2 = st.columns([3, 1])
                with col1:
                    key_display = "••••••" if model.get("apiKey") else "N/A"
                    st.markdown(f"**{model['name']}** (`{model['id']}`)")
                    st.caption(f"Provider: {model.get('provider', '?')} | Key: {key_display}")
                with col2:
                    if st.button("Delete", key=f"del_{model['id']}"):
                        try:
                            del_resp = requests.delete(
                                f"{GATEWAY_URL}/models/registry/{model['id']}", timeout=5
                            )
                            del_resp.raise_for_status()
                            st.success(f"Removed {model['id']}")
                            st.rerun()
                        except Exception as e:
                            st.error(f"Failed: {e}")

    st.divider()

    # --- Transparency Panel ---
    st.subheader("Transparency Panel")
    m = st.session_state.metrics

    cache_status = "Hit" if m["cache_hit"] else "Miss"
    st.markdown(f"**Cache:** {cache_status}")
    st.markdown(f"**Routed Model:** `{m['model_routed']}`")

    fallback_status = "Yes" if m["fallback_triggered"] else "No"
    st.markdown(f"**Fallback:** {fallback_status}")

    st.divider()

    qc_status = "Pass" if m["qc_passed"] else "Fail"
    st.markdown(f"**QC:** {qc_status} (Score: {m['qc_score']}/100)")
    st.markdown(f"**Latency:** `{m['latency_ms']} ms`")

    if st.button("Clear Chat History"):
        st.session_state.messages = []
        st.rerun()

st.title("diplomatAI")
st.markdown("A resilient middle-tier AI API Gateway intercepting and routing your requests.")

for message in st.session_state.messages:
    with st.chat_message(message["role"]):
        st.markdown(message["content"])

if prompt := st.chat_input("Ask diplomatAI anything..."):
    st.chat_message("user").markdown(prompt)
    st.session_state.messages.append({"role": "user", "content": prompt})

    payload = {"prompt": prompt}

    with st.spinner("Processing through diplomatAI Gateway..."):
        try:
            response = requests.post(GATEWAY_URL, json=payload, timeout=30)
            response.raise_for_status()
            data = response.json()

            answer = data.get("answer", "No response received.")
            metrics = data.get("metrics", {})

            st.session_state.metrics = {
                "cache_hit": metrics.get("cache_hit", False),
                "model_routed": metrics.get("model_routed", "Unknown"),
                "fallback_triggered": metrics.get("fallback_triggered", False),
                "qc_score": metrics.get("qc_score", 0),
                "qc_passed": metrics.get("qc_passed", True),
                "latency_ms": metrics.get("latency_ms", 0)
            }

            with st.chat_message("assistant"):
                st.markdown(answer)
            st.session_state.messages.append({"role": "assistant", "content": answer})

            st.rerun()

        except requests.exceptions.RequestException as e:
            st.error(f"Gateway Error: {e}")
            with st.chat_message("assistant"):
                st.markdown("Couldn't reach the AI gateway. Please check if the services are running.")
