"""
Configuration settings for AI Worker
"""

from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    """Application settings"""
    
    # Server
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = False
    
    # Embedding model
    embedding_model: str = "all-MiniLM-L6-v2"
    embedding_dimension: int = 384
    
    # LLM settings
    llm_mode: str = "mock"  # "mock", "vllm", "llamacpp"
    llm_model_path: Optional[str] = None
    llm_max_tokens: int = 2048
    llm_temperature: float = 0.7
    
    # vLLM settings (if using vLLM)
    vllm_model: str = "Qwen/Qwen2.5-0.5B-Instruct"
    vllm_tensor_parallel_size: int = 1
    vllm_gpu_memory_utilization: float = 0.9
    
    # RAG settings
    rag_chunk_size: int = 512
    rag_chunk_overlap: int = 50
    rag_top_k: int = 5
    
    # Vector store
    vector_store_path: str = "./data/vector_store"
    
    class Config:
        env_file = ".env"
        env_prefix = "WORKER_"
