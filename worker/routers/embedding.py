"""
Embedding generation router - OpenAI-compatible endpoint
"""

import json
import time
import logging
from typing import List, Union, Optional, Dict, Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

router = APIRouter()


class EmbeddingRequest(BaseModel):
    input: Union[str, List[str]]
    model: str = "text-embedding-local"
    encoding_format: str = "float"
    dimensions: Optional[int] = None
    user: Optional[str] = None
    provider: Optional[str] = None  # Added to match Gateway's EmbeddingRequest
    
    class Config:
        extra = "ignore"  # Ignore extra fields


class EmbeddingData(BaseModel):
    object: str = "embedding"
    embedding: List[float]
    index: int


class EmbeddingUsage(BaseModel):
    prompt_tokens: int
    total_tokens: int


class EmbeddingResponse(BaseModel):
    object: str = "list"
    data: List[EmbeddingData]
    model: str
    usage: EmbeddingUsage


@router.post("/embeddings", response_model=EmbeddingResponse)
async def create_embedding(request: EmbeddingRequest):
    """
    Generate embeddings for text (OpenAI-compatible)
    """
    from models import embedding_model
    
    if not embedding_model.is_loaded():
        raise HTTPException(status_code=503, detail="Embedding model not loaded")
    
    # Handle single string or list of strings
    texts = request.input if isinstance(request.input, list) else [request.input]
    
    try:
        # Generate embeddings
        embeddings = embedding_model.encode(texts)
        
        # Calculate token count (rough estimate)
        total_tokens = sum(len(text.split()) for text in texts)
        
        # Build response
        return EmbeddingResponse(
            data=[
                EmbeddingData(
                    embedding=emb.tolist(),
                    index=i
                )
                for i, emb in enumerate(embeddings)
            ],
            model=embedding_model.model_name,
            usage=EmbeddingUsage(
                prompt_tokens=total_tokens,
                total_tokens=total_tokens
            )
        )
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/embeddings/similarity")
async def compute_similarity(text1: str, text2: str):
    """
    Compute cosine similarity between two texts
    """
    from models import embedding_model
    
    if not embedding_model.is_loaded():
        raise HTTPException(status_code=503, detail="Embedding model not loaded")
    
    try:
        embeddings = embedding_model.encode([text1, text2])
        similarity = embedding_model.similarity(embeddings[0], embeddings[1])
        
        return {
            "similarity": similarity,
            "text1": text1[:100],
            "text2": text2[:100]
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
