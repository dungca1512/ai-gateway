"""
Chat completion router - OpenAI-compatible endpoint
"""

import time
import uuid
from typing import List, Optional, Dict, Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter()


class Message(BaseModel):
    role: str
    content: str
    name: Optional[str] = None
    function_call: Optional[Dict[str, Any]] = None
    
    class Config:
        extra = "ignore"


class ChatRequest(BaseModel):
    model: str = "local-llm"
    messages: List[Message]
    temperature: Optional[float] = Field(default=0.7, ge=0, le=2)
    max_tokens: Optional[int] = Field(default=1024, ge=1, le=4096)
    top_p: Optional[float] = Field(default=0.9, ge=0, le=1)
    stream: bool = False
    stop: Optional[List[str]] = None
    user: Optional[str] = None
    frequency_penalty: Optional[float] = None
    presence_penalty: Optional[float] = None
    provider: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    
    class Config:
        extra = "ignore"  # Ignore extra fields


class ChatChoice(BaseModel):
    index: int
    message: Message
    finish_reason: str


class Usage(BaseModel):
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


class ChatResponse(BaseModel):
    id: str
    object: str = "chat.completion"
    created: int
    model: str
    choices: List[ChatChoice]
    usage: Usage


@router.post("/chat/completions", response_model=ChatResponse)
async def chat_completion(request: ChatRequest):
    """
    Generate chat completion (OpenAI-compatible)
    """
    import logging
    logging.info(f"Chat request received: model={request.model}, messages={len(request.messages)}")
    
    from models import llm_model
    
    if not llm_model.is_loaded():
        raise HTTPException(status_code=503, detail="LLM model not loaded")
    
    # Convert messages to dict format
    messages = [{"role": m.role, "content": m.content} for m in request.messages]
    
    try:
        # Generate response
        result = llm_model.generate(
            messages=messages,
            temperature=request.temperature,
            max_tokens=request.max_tokens or 1024,
            top_p=request.top_p,
            stop=request.stop
        )
        
        # Convert to response model
        return ChatResponse(
            id=result["id"],
            created=result["created"],
            model=result["model"],
            choices=[
                ChatChoice(
                    index=c["index"],
                    message=Message(
                        role=c["message"]["role"],
                        content=c["message"]["content"]
                    ),
                    finish_reason=c["finish_reason"]
                )
                for c in result["choices"]
            ],
            usage=Usage(
                prompt_tokens=result["usage"]["prompt_tokens"],
                completion_tokens=result["usage"]["completion_tokens"],
                total_tokens=result["usage"]["total_tokens"]
            )
        )
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/models")
async def list_models():
    """List available models"""
    from models import llm_model
    
    return {
        "object": "list",
        "data": [
            {
                "id": llm_model.model_name,
                "object": "model",
                "created": int(time.time()),
                "owned_by": "local",
                "permission": [],
                "root": llm_model.model_name,
                "parent": None
            }
        ]
    }