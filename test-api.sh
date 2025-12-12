#!/bin/bash

# AI Gateway Test Script
# Tests various endpoints of the AI Gateway

BASE_URL="${1:-http://localhost:8080}"
WORKER_URL="${2:-http://localhost:8000}"

echo "================================"
echo "AI Gateway Test Suite"
echo "Gateway: $BASE_URL"
echo "Worker: $WORKER_URL"
echo "================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test function
test_endpoint() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    
    echo -n "Testing: $name... "
    
    if [ "$method" = "GET" ]; then
        response=$(curl -s -w "\n%{http_code}" "$url")
    else
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$url" \
            -H "Content-Type: application/json" \
            -d "$data")
    fi
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
        echo -e "${GREEN}✓ OK${NC} (HTTP $http_code)"
        echo "$body" | head -c 500
        echo ""
    else
        echo -e "${RED}✗ FAILED${NC} (HTTP $http_code)"
        echo "$body" | head -c 500
        echo ""
    fi
    echo ""
}

echo "========== Worker Tests =========="
echo ""

# Test Worker Health
test_endpoint "Worker Health" "GET" "$WORKER_URL/health"

# Test Worker Detailed Health
test_endpoint "Worker Detailed Health" "GET" "$WORKER_URL/health/detailed"

# Test Worker Chat
test_endpoint "Worker Chat Completion" "POST" "$WORKER_URL/v1/chat/completions" '{
    "model": "local-llm",
    "messages": [
        {"role": "user", "content": "Hello, how are you?"}
    ],
    "temperature": 0.7
}'

# Test Worker Embeddings
test_endpoint "Worker Embeddings" "POST" "$WORKER_URL/v1/embeddings" '{
    "input": "This is a test sentence for embedding.",
    "model": "text-embedding-local"
}'

echo "========== Gateway Tests =========="
echo ""

# Test Gateway Health
test_endpoint "Gateway Health" "GET" "$BASE_URL/health"

# Test Gateway Detailed Health
test_endpoint "Gateway Detailed Health" "GET" "$BASE_URL/health/detailed"

# Test Models List
test_endpoint "List Models" "GET" "$BASE_URL/v1/models"

# Test Chat via Gateway (will use local worker as fallback)
test_endpoint "Gateway Chat (Local Worker)" "POST" "$BASE_URL/v1/chat/completions" '{
    "model": "local-llm",
    "messages": [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "What is 2+2?"}
    ],
    "temperature": 0.7,
    "provider": "local-worker"
}'

# Test Embeddings via Gateway
test_endpoint "Gateway Embeddings (Local Worker)" "POST" "$BASE_URL/v1/embeddings" '{
    "input": "This is a test for gateway embeddings.",
    "provider": "local-worker"
}'

echo "========== RAG Tests =========="
echo ""

# Index some documents
test_endpoint "RAG Index Documents" "POST" "$WORKER_URL/v1/rag/index" '{
    "documents": [
        {
            "content": "The AI Gateway is a production-grade system for managing AI requests. It supports multiple providers including OpenAI, Gemini, Claude, and local models.",
            "metadata": {"source": "documentation", "topic": "overview"}
        },
        {
            "content": "Rate limiting is implemented using Bucket4j and supports per-user limits. The default is 60 requests per minute.",
            "metadata": {"source": "documentation", "topic": "rate-limiting"}
        },
        {
            "content": "The Python Worker handles local LLM inference using vLLM or llama.cpp. It also provides embedding generation using Sentence Transformers.",
            "metadata": {"source": "documentation", "topic": "worker"}
        }
    ],
    "chunk_size": 512,
    "chunk_overlap": 50
}'

# Test RAG Search
test_endpoint "RAG Search" "POST" "$WORKER_URL/v1/rag/search" '{
    "query": "How does rate limiting work?",
    "top_k": 2
}'

# Test RAG Query
test_endpoint "RAG Query (Full Pipeline)" "POST" "$WORKER_URL/v1/rag/query" '{
    "query": "What providers does the AI Gateway support?",
    "top_k": 3,
    "temperature": 0.7
}'

# Get RAG Stats
test_endpoint "RAG Stats" "GET" "$WORKER_URL/v1/rag/stats"

echo "================================"
echo "Test Suite Complete"
echo "================================"
