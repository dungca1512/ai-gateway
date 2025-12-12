# AI Gateway + AI Worker Demo

A production-grade AI Gateway system combining Java (Spring Boot WebFlux) and Python (FastAPI) for unified AI request management.

## 🏗️ Architecture

```
┌──────────────────────────────────┐
│         Client Apps              │
│  (Mobile, Backend, Web, CLI)     │
└──────────────┬───────────────────┘
               │ HTTPS
               ▼
┌──────────────────────────────────┐
│      AI Gateway (Java)           │
│  ┌────────────────────────────┐  │
│  │ • Unified API (OpenAI-     │  │
│  │   compatible)              │  │
│  │ • Multi-provider routing   │  │
│  │ • Fallback & retry logic   │  │
│  │ • Rate limiting (Bucket4j) │  │
│  │ • Caching (Redis)          │  │
│  │ • Circuit breaker          │  │
│  │ • Observability            │  │
│  └────────────────────────────┘  │
└──────────────┬───────────────────┘
               │
     ┌─────────┼─────────┬─────────┐
     ▼         ▼         ▼         ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐
│ OpenAI  │ │ Gemini  │ │ Claude  │ │ Python Worker│
└─────────┘ └─────────┘ └─────────┘ └──────┬───────┘
                                           │
                                           ▼
                                   ┌──────────────┐
                                   │ Local LLM    │
                                   │ Embeddings   │
                                   │ RAG Pipeline │
                                   └──────────────┘
```

## 🚀 Quick Start

### Prerequisites

- Docker & Docker Compose
- (Optional) Java 21+ and Maven for local development
- (Optional) Python 3.11+ for local development

### Option 1: Docker Compose (Recommended)

```bash
# Clone or copy the project
cd ai-gateway-demo

# Copy environment file
cp .env.example .env

# (Optional) Add your API keys to .env
# OPENAI_API_KEY=sk-...
# GEMINI_API_KEY=...
# CLAUDE_API_KEY=sk-ant-...

# Start all services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f

# Run tests
chmod +x test-api.sh
./test-api.sh
```

### Option 2: Run Locally on Mac M4

#### 1. Start Redis

```bash
# Using Docker
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Or using Homebrew
brew install redis
brew services start redis
```

#### 2. Start Python Worker

```bash
cd worker

# Create virtual environment
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Run worker
python app.py
```

#### 3. Start Java Gateway

```bash
cd gateway

# Build
./mvnw clean package -DskipTests

# Run
java -jar target/ai-gateway-1.0.0.jar

# Or with Maven
./mvnw spring-boot:run
```

#### 4. Test

```bash
# Health check
curl http://localhost:8080/health

# Chat completion
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local-llm",
    "messages": [{"role": "user", "content": "Hello!"}],
    "provider": "local-worker"
  }'
```

## 📡 API Endpoints

### Gateway (Port 8080)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/health/detailed` | GET | Detailed health with provider status |
| `/v1/models` | GET | List available models |
| `/v1/chat/completions` | POST | Chat completion (OpenAI-compatible) |
| `/v1/embeddings` | POST | Generate embeddings |
| `/metrics` | GET | Prometheus metrics |
| `/admin/cache` | DELETE | Clear cache |
| `/admin/ratelimit/{id}` | GET/DELETE | Rate limit management |

### Worker (Port 8000)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/health/detailed` | GET | Detailed health |
| `/v1/chat/completions` | POST | Local LLM chat |
| `/v1/embeddings` | POST | Generate embeddings |
| `/v1/rag/index` | POST | Index documents for RAG |
| `/v1/rag/search` | POST | Search indexed documents |
| `/v1/rag/query` | POST | Full RAG pipeline |
| `/v1/rag/stats` | GET | RAG index statistics |

## 🔧 Configuration

### Environment Variables

#### Gateway

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENAI_API_KEY` | - | OpenAI API key |
| `OPENAI_ENABLED` | true | Enable OpenAI provider |
| `GEMINI_API_KEY` | - | Google Gemini API key |
| `GEMINI_ENABLED` | true | Enable Gemini provider |
| `CLAUDE_API_KEY` | - | Anthropic Claude API key |
| `CLAUDE_ENABLED` | true | Enable Claude provider |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `PYTHON_WORKER_URL` | http://localhost:8000 | Worker URL |

#### Worker

| Variable | Default | Description |
|----------|---------|-------------|
| `WORKER_LLM_MODE` | mock | LLM mode: mock, vllm, llamacpp |
| `WORKER_EMBEDDING_MODEL` | all-MiniLM-L6-v2 | Embedding model |
| `WORKER_VLLM_MODEL` | Qwen/Qwen2.5-0.5B-Instruct | vLLM model |
| `WORKER_LLM_MODEL_PATH` | - | Path to GGUF model |

## 📊 Example Usage

### Chat with Local LLM

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local-llm",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "Explain quantum computing in simple terms."}
    ],
    "temperature": 0.7,
    "max_tokens": 1024,
    "provider": "local-worker"
  }'
```

### Chat with OpenAI (if configured)

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ]
  }'
```

### Generate Embeddings

```bash
curl -X POST http://localhost:8080/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "input": "The quick brown fox jumps over the lazy dog.",
    "provider": "local-worker"
  }'
```

### RAG Pipeline

```bash
# 1. Index documents
curl -X POST http://localhost:8000/v1/rag/index \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {"content": "Python is a programming language...", "metadata": {"topic": "python"}},
      {"content": "Java is also a programming language...", "metadata": {"topic": "java"}}
    ]
  }'

# 2. Query with RAG
curl -X POST http://localhost:8000/v1/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is Python?",
    "top_k": 3
  }'
```

## 🔍 Monitoring

### Prometheus Metrics

```bash
# Gateway metrics
curl http://localhost:8080/actuator/prometheus

# Worker metrics  
curl http://localhost:8000/metrics
```

Key metrics:
- `ai_provider_requests_total` - Total requests per provider
- `ai_provider_latency_seconds` - Provider latency
- `ai_routing_fallback_total` - Fallback events
- `ai_cache_hits_total` - Cache hit rate
- `ai_ratelimit_exceeded_total` - Rate limit violations

## 🔐 Security Features

- Rate limiting per user/API key
- Circuit breaker for provider failures
- Request validation
- Configurable authentication
- Audit logging

## 📁 Project Structure

```
ai-gateway-demo/
├── gateway/                    # Java Gateway
│   ├── src/main/java/com/aigateway/
│   │   ├── controller/        # REST controllers
│   │   ├── service/           # Business logic
│   │   ├── provider/          # AI providers
│   │   ├── config/            # Configuration
│   │   └── model/             # Data models
│   ├── src/main/resources/
│   │   └── application.yml    # Configuration
│   ├── Dockerfile
│   └── pom.xml
├── worker/                     # Python Worker
│   ├── routers/               # API routers
│   ├── models/                # ML models
│   ├── rag/                   # RAG pipeline
│   ├── app.py                 # FastAPI app
│   ├── requirements.txt
│   └── Dockerfile
├── docker-compose.yml
├── .env.example
├── test-api.sh
└── README.md
```

## 🚧 Production Considerations

1. **Scaling**: Use Kubernetes for horizontal scaling
2. **Security**: Add JWT authentication, HTTPS
3. **Monitoring**: Set up Grafana dashboards
4. **Database**: Add PostgreSQL for usage tracking
5. **Queue**: Add Kafka for async processing
6. **GPU**: Configure vLLM with GPU support for production LLM

## 📝 License

MIT License
