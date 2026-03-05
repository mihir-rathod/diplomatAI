import streamlit as st
import requests
import json
import os
from dotenv import load_dotenv

load_dotenv()

GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/chat")

st.set_page_config(
    page_title="diplomatAI Dashboard",
    page_icon="🤖",
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
if "available_models" not in st.session_state:
    st.session_state.available_models = []
if "api_key_configured" not in st.session_state:
    st.session_state.api_key_configured = False

with st.sidebar:
    st.title("🔍 diplomatAI Control")
    
    with st.expander("🔑 API Key & Model Configuration", expanded=not st.session_state.api_key_configured):
        st.markdown("Fetch available dynamic models:")
        provider = st.selectbox("Provider", ["OpenAI", "Gemini"])
        api_key_input = st.text_input("API Key", type="password", help="Sent securely to the Gateway")
        
        if st.button("Fetch Models"):
            if not api_key_input:
                st.warning("Please enter an API Key.")
            else:
                with st.spinner("Fetching from Gateway..."):
                    try:
                        response = requests.post(
                            f"{GATEWAY_URL}/models", 
                            json={"provider": provider, "apiKey": api_key_input},
                            timeout=10
                        )
                        response.raise_for_status()
                        models = response.json()
                        
                        st.session_state.available_models = models
                        st.session_state.api_key_configured = True
                        st.success(f"Successfully loaded {len(models)} models!")
                        
                    except requests.exceptions.RequestException as e:
                        st.error(f"Failed to reach Gateway: {e}")
                        
        if st.session_state.api_key_configured and st.session_state.available_models:
            st.markdown("### Loaded Models")
            for m in st.session_state.available_models:
                st.caption(f"• **{m['name']}** (`{m['id']}`)")
                
    st.divider()

    st.subheader("Transparency Panel")
    st.markdown("Real-time metrics for the last request.")
    
    st.subheader("Routing & Cache")
    m = st.session_state.metrics
    
    cache_status = "✅ Hit" if m["cache_hit"] else "❌ Miss"
    st.markdown(f"**Cache Status:** {cache_status}")
    
    st.markdown(f"**Routed Model:** `{m['model_routed']}`")
    
    fallback_status = "⚠️ Yes" if m["fallback_triggered"] else "✅ No"
    st.markdown(f"**Fallback Triggered:** {fallback_status}")
    
    st.divider()
    
    st.subheader("Security & Performance")
    qc_status = "✅ Pass" if m["qc_passed"] else "❌ Fail"
    st.markdown(f"**QC Status:** {qc_status} (Score: {m['qc_score']}/100)")
    
    st.markdown(f"**Gateway Latency:** `{m['latency_ms']} ms`")
    
    if st.button("Clear Chat History"):
        st.session_state.messages = []
        st.rerun()

st.title("🤖 diplomatAI")
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
            import time
            import random
            time.sleep(1)
            data = {
                "answer": f"This is a mocked response to: '{prompt}'. The Java Gateway is not connected yet.",
                "metrics": {
                    "cache_hit": random.choice([True, False]),
                    "model_routed": random.choice(["Llama-3-8b", "Deepseek-Coder", "Mistral-7b"]),
                    "fallback_triggered": random.choice([True, False, False]),
                    "qc_score": random.randint(85, 100),
                    "qc_passed": True,
                    "latency_ms": random.randint(150, 1200)
                }
            }
            
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
                st.markdown("⚠️ I'm sorry, I couldn't reach the AI gateway. Please check if the services are running.")
