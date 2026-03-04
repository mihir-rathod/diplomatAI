import streamlit as st
import requests
import json
import os
from dotenv import load_dotenv

load_dotenv()

# Constants
GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8080/api/v1/chat")

st.set_page_config(
    page_title="diplomatAI Dashboard",
    page_icon="🤖",
    layout="wide",
)

# Custom CSS for a better UI look
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

# Initialize Session State
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

# --- SIDEBAR: Transparency Panel ---
with st.sidebar:
    st.title("🔍 Transparency Panel")
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

# --- MAIN WORKSPACE: Chat Interface ---
st.title("🤖 diplomatAI")
st.markdown("A resilient middle-tier AI API Gateway intercepting and routing your requests.")

# Display chat messages from history on app rerun
for message in st.session_state.messages:
    with st.chat_message(message["role"]):
        st.markdown(message["content"])

# React to user input
if prompt := st.chat_input("Ask diplomatAI anything..."):
    # Display user message in chat message container
    st.chat_message("user").markdown(prompt)
    # Add user message to chat history
    st.session_state.messages.append({"role": "user", "content": prompt})

    # Prepare request to gateway
    payload = {"prompt": prompt}
    
    with st.spinner("Processing through diplomatAI Gateway..."):
        try:
            # Simulate a request for UI building purposes (will connect to real API later)
            # response = requests.post(GATEWAY_URL, json=payload, timeout=20)
            # response.raise_for_status()
            # data = response.json()
            
            # --- MOCK DATA TEMPORARY UNTIL GATEWAY IS BUILT ---
            import time
            import random
            time.sleep(1) # simulate latency
            data = {
                "answer": f"This is a mocked response to: '{prompt}'. The Java Gateway is not connected yet.",
                "metrics": {
                    "cache_hit": random.choice([True, False]),
                    "model_routed": random.choice(["Llama-3-8b", "Deepseek-Coder", "Mistral-7b"]),
                    "fallback_triggered": random.choice([True, False, False]), # 33% chance of fallback
                    "qc_score": random.randint(85, 100),
                    "qc_passed": True,
                    "latency_ms": random.randint(150, 1200)
                }
            }
            # --------------------------------------------------
            
            answer = data.get("answer", "No response received.")
            metrics = data.get("metrics", {})
            
            # Update metrics in session state
            st.session_state.metrics = {
                "cache_hit": metrics.get("cache_hit", False),
                "model_routed": metrics.get("model_routed", "Unknown"),
                "fallback_triggered": metrics.get("fallback_triggered", False),
                "qc_score": metrics.get("qc_score", 0),
                "qc_passed": metrics.get("qc_passed", True),
                "latency_ms": metrics.get("latency_ms", 0)
            }
            
            # Display assistant response in chat message container
            with st.chat_message("assistant"):
                st.markdown(answer)
            # Add assistant response to chat history
            st.session_state.messages.append({"role": "assistant", "content": answer})
            
            # Rerun to update sidebar metrics
            st.rerun()

        except requests.exceptions.RequestException as e:
            st.error(f"Gateway Error: {e}")
            with st.chat_message("assistant"):
                st.markdown("⚠️ I'm sorry, I couldn't reach the AI gateway. Please check if the services are running.")
