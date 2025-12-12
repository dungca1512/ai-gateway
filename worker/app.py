"""
AI Worker - FastAPI Application
Handles local LLM inference, embeddings, and RAG pipelines
"""

import os
import time
import uuid
from contextlib import asynccontextmanager
from typing import Optional

import uvicorn
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response

from routers import chat, embedding, rag
from models.config import Settings

# Metrics
REQUEST_COUNT = Counter('worker_requests_total', 'Total requests', ['endpoint', 'status'])
REQUEST_LATENCY = Histogram('worker_request_latency_seconds', 'Request latency', ['endpoint'])

# Settings
settings = Settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Initialize and cleanup resources"""
    print("🚀 Starting AI Worker...")
    
    # Initialize models on startup
    from models import embedding_model, llm_model
    
    print("📦 Loading embedding model...")
    embedding_model.initialize()
    
    print("🤖 Initializing LLM...")
    llm_model.initialize()
    
    print("✅ AI Worker ready!")
    
    yield
    
    # Cleanup
    print("👋 Shutting down AI Worker...")

# Create app
app = FastAPI(
    title="AI Worker",
    description="Python AI Worker for local LLM inference, embeddings, and RAG",
    version="1.0.0",
    lifespan=lifespan
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(chat.router, prefix="/v1", tags=["Chat"])
app.include_router(embedding.router, prefix="/v1", tags=["Embeddings"])
app.include_router(rag.router, prefix="/v1", tags=["RAG"])


@app.middleware("http")
async def metrics_middleware(request: Request, call_next):
    """Track request metrics"""
    start_time = time.time()
    
    response = await call_next(request)
    
    latency = time.time() - start_time
    endpoint = request.url.path
    status = "success" if response.status_code < 400 else "error"
    
    REQUEST_COUNT.labels(endpoint=endpoint, status=status).inc()
    REQUEST_LATENCY.labels(endpoint=endpoint).observe(latency)
    
    return response


@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {
        "status": "healthy",
        "service": "ai-worker",
        "timestamp": time.time()
    }


@app.get("/health/detailed")
async def detailed_health():
    """Detailed health with component status"""
    from models import embedding_model, llm_model
    
    return {
        "status": "healthy",
        "service": "ai-worker",
        "components": {
            "embedding_model": {
                "loaded": embedding_model.is_loaded(),
                "model": embedding_model.model_name
            },
            "llm": {
                "loaded": llm_model.is_loaded(),
                "mode": llm_model.mode
            }
        },
        "timestamp": time.time()
    }


@app.get("/metrics")
async def metrics():
    """Prometheus metrics endpoint"""
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST
    )


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """Global exception handler"""
    return JSONResponse(
        status_code=500,
        content={
            "error": {
                "message": str(exc),
                "type": "server_error",
                "code": "internal_error"
            }
        }
    )


if __name__ == "__main__":
    uvicorn.run(
        "app:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
        workers=1  # Single worker for model loading
    )
