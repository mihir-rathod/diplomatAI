# 🤖 diplomatAI (AI Traffic Controller)

**diplomatAI** is a resilient, autonomous AI API Gateway that sits between your applications (or AI Agents) and external LLM providers (like OpenAI, Gemini, or local models). It acts as a highly intelligent "Traffic Cop," ensuring your AI infrastructure is secure, fast, and cost-effective.

## 🌟 Why Use an AI Gateway?

When building AI applications or deploying swarms of autonomous agents, you typically make direct calls to an API (e.g., `api.openai.com`). This creates several massive vulnerabilities that diplomatAI solves:

1. **The Cost Trap**: If an agent gets stuck in a loop asking the same question 1,000 times, you pay for 1,000 requests. 
   - *diplomatAI Solution:* **Semantic Caching**. It intercepts the duplicate prompt, returns a cached answer instantly from Redis, and routes $0 to OpenAI.
2. **The 429 Rate Limit Crash**: When your agent swarm scales, OpenAI will eventually block you with a `429 Too Many Requests` error, crashing your system.
   - *diplomatAI Solution:* **Resilience4j Rate Limiting**. diplomatAI tracks your API limits. If OpenAI throws a 429, diplomatAI instantly catches the error and silently reroutes the request to a cheaper backup model (like Llama 3) without your app ever knowing a failure occurred.
3. **The Hallucination/Safety Risk**: Models can go rogue or generate toxic/harmful content.
   - *diplomatAI Solution:* **Quality Check Service**. Every response is intercepted and mathematically scored by a Python microservice before being allowed back to the user/agent.

---

## 🏗️ Architecture

The system is deployed as three decoupled, containerized microservices:

1. **Dashboard (Python / Streamlit)**: A visual chat interface and transparency console. Users can chat with the AI, input their dynamic API keys, and watch real-time metrics (Cache Hits, Latency, Fallback routing) in the sidebar.
2. **Main Gate (Java 21 / Spring Boot)**: The core Brain. Contains the **Intelligent Router** (keyword heuristics to send coding questions to a coding model, and chat queries to a chat model) and the **Circuit Breaker** logic. 
3. **Quality & Memory (Redis + FastAPI)**: A high-speed Redis data store catches duplicate prompts before they cost you money.

---

## 🚀 How to Run Locally 

You do **not** need a cloud provider like AWS to run this! The entire infrastructure is containerized using Docker, meaning it runs flawlessly on your local machine.

### Prerequisites
- [Docker & Docker Compose](https://www.docker.com/products/docker-desktop/) installed on your machine.
- Git.

### Start the Engines
```bash
# 1. Clone the repository
git clone https://github.com/mihir-rathod/diplomatAI.git
cd diplomatAI

# 2. Build and run the entire suite
docker-compose up --build -d
```
*Note: We are actively building the Docker files right now. Once we finish the next phase, this command will instantly spin up everything.*

### Accessing the System
- **Streamlit UI**: Navigate your browser to `http://localhost:8501`.
- **API Gateway**: Running on `http://localhost:8080/api/v1/chat`.

---

## 🔌 How AI Agents Can Use This

The true power of `diplomatAI` is its drop-in compatibility. 

To use this Gateway, an organization or an AI Developer does **not** need to install any new SDKs. They simply change the base URL in their existing code from the Provider URL to the Gateway URL.

**Before (Direct to OpenAI):**
```python
import requests

response = requests.post(
    url="https://api.openai.com/v1/chat/completions",
    json={"prompt": "Write a python script"},
    headers={"Authorization": "Bearer YOUR_OPENAI_KEY"}
)
```

**After (Using diplomatAI):**
```python
import requests

response = requests.post(
    # Just change this one URL!
    url="http://localhost:8080/api/v1/chat", 
    json={"prompt": "Write a python script"},
    headers={"Authorization": "Bearer YOUR_OPENAI_KEY"}
)
```
The Gateway accepts the request, handles all the routing, caching, and fallback logic, and returns the response back to your Python script!