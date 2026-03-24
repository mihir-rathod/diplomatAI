# 🤖 diplomatAI

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-green?logo=springboot)
![Python](https://img.shields.io/badge/Python-3.11-blue?logo=python)
![FastAPI](https://img.shields.io/badge/FastAPI-0.104-teal?logo=fastapi)
![Redis](https://img.shields.io/badge/Redis-Alpine-red?logo=redis)
![Docker](https://img.shields.io/badge/Docker-Compose-blue?logo=docker)

A resilient AI API Gateway that sits between your applications and LLM providers. It intercepts, caches, routes, validates, and fault-tolerates every AI request — so your infrastructure doesn't break when a provider does.

---

## Why?

| Problem | What happens | diplomatAI solution |
|---------|-------------|---------------------|
| **Cost trap** | An agent loops the same question 1,000× — you pay for 1,000 requests | **Redis caching** returns identical prompts instantly at $0 |
| **Rate limit crash** | Provider returns 429 — your system crashes | **Circuit breaker** silently reroutes to a fallback model |
| **Hallucination risk** | Model returns toxic or irrelevant content | **Quality Check** scores every response with NLP before returning it |

---

## Architecture

```
┌──────────────┐     ┌──────────────────────────┐     ┌─────────────────┐
│              │     │      Java Gateway         │     │  LLM Providers  │
│  Streamlit   │────▶│                          │────▶│  OpenAI         │
│  Dashboard   │     │  Router → Cache → Call   │     │  Gemini         │
│              │◀────│  Rate Limiter + Breaker   │◀────│  Groq           │
│  :8501       │     │                          │     │  Anthropic      │
└──────────────┘     │  :8080                   │     │  Mistral        │
                     └────────┬─────────────────┘     │  OpenRouter     │
                              │                       │  Together AI    │
                              ▼                       └─────────────────┘
                     ┌──────────────────┐
                     │  Quality Check   │
                     │  (FastAPI)       │
                     │  Sentence BERT   │
                     │  Toxicity Filter │
                     │  :8000           │
                     └──────────────────┘
                              │
                     ┌──────────────────┐
                     │     Redis        │
                     │  Prompt Cache    │
                     │  :6379           │
                     └──────────────────┘
```

---

## Supported Providers

| Provider | Free Tier | Validation Endpoint |
|----------|-----------|-------------------|
| OpenAI | $5 credit on sign-up | `api.openai.com/v1/models` |
| Gemini | ✅ 15 RPM free | `generativelanguage.googleapis.com` |
| Anthropic | No free tier | `api.anthropic.com/v1/models` |
| Groq | ✅ 30 RPM free | `api.groq.com/openai/v1/models` |
| Mistral | ✅ Limited free | `api.mistral.ai/v1/models` |
| OpenRouter | ✅ Free models | `openrouter.ai/api/v1/models` |
| Together AI | $5 free credit | `api.together.xyz/v1/models` |

API keys are validated against the provider's real endpoint before registration.

---

## Quick Start

### Prerequisites
- [Docker & Docker Compose](https://www.docker.com/products/docker-desktop/)
- Git

### Run
```bash
git clone https://github.com/mihir-rathod/diplomatAI.git
cd diplomatAI
docker compose up --build -d
```

### Access
| Service | URL |
|---------|-----|
| Dashboard | `http://localhost:8501` |
| Gateway API | `http://localhost:8080/api/v1/chat` |
| Quality Check | `http://localhost:8000/health` |
| Redis | `localhost:6379` |

---

## API Reference

### Chat
```
POST /api/v1/chat
Body: { "prompt": "your question" }
Returns: { "answer": "...", "metrics": { "cache_hit", "model_routed", "fallback_triggered", "qc_score", "qc_passed", "latency_ms" } }
```

### Register Provider Models
```
POST /api/v1/chat/models
Body: { "provider": "OpenAI", "apiKey": "sk-..." }
Returns: list of registered model configs (or 401 if key is invalid)
```

### Model Registry
```
GET    /api/v1/chat/models/registry          → list all registered models
DELETE /api/v1/chat/models/registry/{modelId} → remove a model
```

### Health
```
GET /api/v1/chat/health  → gateway status + redis connectivity
GET /health              → quality check service status
```

---

## Project Structure

```
diplomatAI/
├── dashboard/                  # Streamlit UI (Python)
│   ├── app.py
│   ├── requirements.txt
│   └── Dockerfile
├── gateway-service/            # Core Gateway (Java / Spring Boot)
│   ├── src/main/java/com/diplomat/gateway/
│   │   ├── controller/         # REST endpoints
│   │   ├── client/             # ProviderClient, QualityCheckClient
│   │   ├── service/            # RouterService, DynamicModelFetcherService
│   │   ├── config/             # ModelRegistryProperties, GatewayConfig
│   │   └── model/              # DTOs
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── models.yaml         # Default model registry
│   ├── build.gradle
│   └── Dockerfile
├── quality-check-service/      # Validation service (Python / FastAPI)
│   ├── main.py
│   ├── requirements.txt
│   └── Dockerfile
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## How AI Agents Use This

Change one URL — no SDK, no library, no code rewrite:

```python
# Before: direct to OpenAI
response = requests.post("https://api.openai.com/v1/chat/completions", ...)

# After: through diplomatAI
response = requests.post("http://localhost:8080/api/v1/chat", json={"prompt": "..."})
```

The gateway handles routing, caching, fallback, and quality validation transparently.