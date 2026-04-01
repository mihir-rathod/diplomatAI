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
    /* Chat container */
    .stChatFloatingInputContainer { padding-bottom: 2rem; }

    /* Sidebar metrics */
    .metric-label { font-size: 0.75rem; color: #888; text-transform: uppercase; letter-spacing: 0.05em; }
    .metric-value { font-size: 0.95rem; font-weight: 600; margin-bottom: 0.75rem; }
    .metric-value.positive { color: #10b981; }
    .metric-value.negative { color: #ef4444; }
    .metric-value.neutral { color: #6b7280; }

    /* Sidebar section headers */
    .sidebar-section { font-size: 0.8rem; font-weight: 600; color: #9ca3af; text-transform: uppercase;
                       letter-spacing: 0.08em; margin: 1rem 0 0.5rem 0; }
</style>
""", unsafe_allow_html=True)

# --- Session State ---
if "messages" not in st.session_state:
    st.session_state.messages = []
if "metrics" not in st.session_state:
    st.session_state.metrics = {
        "cache_hit": False, "model_routed": "None", "fallback_triggered": False,
        "qc_score": 0, "qc_passed": True, "latency_ms": 0
    }

# ─────────────────────────── SIDEBAR ───────────────────────────
with st.sidebar:
    st.title("diplomatAI")
    st.caption("AI Traffic Controller")

    # --- Add Provider ---
    with st.expander("Add Provider", expanded=True):
        with st.form("register_form", clear_on_submit=True):
            provider = st.selectbox("Provider", ["OpenAI", "Gemini", "Anthropic", "Groq", "Mistral", "OpenRouter", "Together"],
                                     index=None, placeholder="Select a provider...")
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

    # --- Model Registry ---
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
            for model_entry in registry:
                col1, col2 = st.columns([3, 1])
                with col1:
                    key_display = "••••••" if model_entry.get("apiKey") else "N/A"
                    st.markdown(f"**{model_entry['name']}** (`{model_entry['id']}`)")
                    st.caption(f"Provider: {model_entry.get('provider', '?')} | Key: {key_display}")
                with col2:
                    if st.button("Delete", key=f"del_{model_entry['id']}"):
                        try:
                            del_resp = requests.delete(
                                f"{GATEWAY_URL}/models/registry/{model_entry['id']}", timeout=5
                            )
                            del_resp.raise_for_status()
                            st.success(f"Removed {model_entry['id']}")
                            st.rerun()
                        except Exception as e:
                            st.error(f"Failed: {e}")

    st.divider()

    # --- Transparency Panel ---
    st.markdown('<p class="sidebar-section">Last Response Metrics</p>', unsafe_allow_html=True)
    m = st.session_state.metrics

    cache_class = "positive" if m["cache_hit"] else "neutral"
    cache_label = "Hit" if m["cache_hit"] else "Miss"
    st.markdown(f'<p class="metric-label">Cache</p><p class="metric-value {cache_class}">{cache_label}</p>', unsafe_allow_html=True)

    st.markdown(f'<p class="metric-label">Routed Model</p><p class="metric-value neutral">{m["model_routed"]}</p>', unsafe_allow_html=True)

    fb_class = "negative" if m["fallback_triggered"] else "positive"
    fb_label = "Yes" if m["fallback_triggered"] else "No"
    st.markdown(f'<p class="metric-label">Fallback Triggered</p><p class="metric-value {fb_class}">{fb_label}</p>', unsafe_allow_html=True)

    qc_class = "positive" if m["qc_passed"] else "negative"
    qc_label = "Pass" if m["qc_passed"] else "Fail"
    st.markdown(f'<p class="metric-label">Quality Check</p><p class="metric-value {qc_class}">{qc_label} ({m["qc_score"]}/100)</p>', unsafe_allow_html=True)

    st.markdown(f'<p class="metric-label">Latency</p><p class="metric-value neutral">{m["latency_ms"]} ms</p>', unsafe_allow_html=True)

    st.divider()

    # --- Actions ---
    col_a, col_b = st.columns(2)
    with col_a:
        if st.button("Clear Chat", use_container_width=True):
            st.session_state.messages = []
            st.rerun()
    with col_b:
        if st.button("Clear Cache", use_container_width=True):
            try:
                resp = requests.delete(f"{GATEWAY_URL}/cache", timeout=5)
                resp.raise_for_status()
                data = resp.json()
                st.toast(f"Cache cleared: {data.get('entries_removed', 0)} entries removed.")
            except Exception as e:
                st.error(f"Failed: {e}")

# ─────────────────────────── MAIN CHAT ───────────────────────────
st.title("diplomatAI")
st.caption("A resilient middle-tier AI Gateway intercepting and routing your requests.")

# Render chat history
for message in st.session_state.messages:
    with st.chat_message(message["role"]):
        st.markdown(message["content"])

# Chat input
if prompt := st.chat_input("Send a message..."):
    st.chat_message("user").markdown(prompt)
    st.session_state.messages.append({"role": "user", "content": prompt})

    payload = {"prompt": prompt}

    with st.spinner("Routing through Gateway..."):
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
