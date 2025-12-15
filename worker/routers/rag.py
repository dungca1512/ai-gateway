"""
RAG (Retrieval Augmented Generation) router using the RAGPipeline
"""

from typing import List, Optional, Dict, Any
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel, Field

# This is a placeholder and will be replaced by the instance from app.py
# during the application lifespan startup.
pipeline = None

def get_pipeline():
    """Dependency to get the initialized RAG pipeline."""
    if pipeline is None:
        raise HTTPException(status_code=503, detail="RAG pipeline is not available.")
    return pipeline

# Add a 'pipeline' attribute to the router instance to hold the pipeline object
router = APIRouter()
router.pipeline = None

# --- Shared Models ---

class Document(BaseModel):
    id: Optional[str] = None
    content: str
    metadata: Optional[Dict[str, Any]] = None

class SearchResult(BaseModel):
    id: str
    content: str
    score: float
    metadata: Optional[Dict[str, Any]] = None
    rerank_score: Optional[float] = None

# --- Request Models ---

class IndexRequest(BaseModel):
    documents: List[Document]
    chunk_size: int = Field(default=512, ge=100, le=2000)
    chunk_overlap: int = Field(default=50, ge=0, le=200)

class SearchRequest(BaseModel):
    query: str
    top_k: int = Field(default=5, ge=1, le=20)

class RAGRequest(BaseModel):
    query: str
    top_k: int = Field(default=5, ge=1, le=20)
    use_compression: bool = Field(default=True, description="Whether to compress context before generation.")
    use_fact_checking: bool = Field(default=True, description="Whether to fact-check the generated answer.")
    temperature: float = Field(default=0.7, ge=0, le=2)
    max_tokens: int = Field(default=1024, ge=1, le=4096)
    system_prompt: Optional[str] = None

class RAGResponse(BaseModel):
    answer: str
    sources: List[SearchResult]
    model: str
    fact_check: Optional[Dict[str, Any]] = None

# --- New Module-Specific Request Models ---

class ExpandQueryRequest(BaseModel):
    query: str
    num_expansions: int = Field(default=3, ge=1, le=10)

class RerankRequest(BaseModel):
    query: str
    documents: List[Document]
    top_k: int = Field(default=5, ge=1)

class CompressRequest(BaseModel):
    query: str
    documents: List[Document]
    max_sentences_per_doc: int = Field(default=3, ge=1)

class FactCheckRequest(BaseModel):
    answer: str
    documents: List[Document]

# --- Endpoints ---

@router.post("/rag/index")
async def index_documents(request: IndexRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Index documents using the RAG pipeline.
    """
    try:
        docs_to_index = [{"content": doc.content, "metadata": doc.metadata or {}} for doc in request.documents]
        indexed_count = rag_pipeline.index_documents(
            docs_to_index,
            chunk_size=request.chunk_size,
            chunk_overlap=request.chunk_overlap
        )
        return {
            "status": "success",
            "indexed_chunks": indexed_count,
            "total_documents": len(request.documents)
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to index documents: {e}")

@router.post("/rag/search", response_model=List[SearchResult])
async def search_documents(request: SearchRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Search documents using the RAG pipeline's retrieve method (includes expansion & reranking).
    """
    try:
        results = rag_pipeline.retrieve(query=request.query, top_k=request.top_k)
        return results
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to search: {e}")

@router.post("/rag/query", response_model=RAGResponse)
async def rag_query(request: RAGRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Full RAG pipeline query using the RAG pipeline.
    """
    try:
        result = rag_pipeline.query(
            query=request.query,
            top_k=request.top_k,
            use_compression=request.use_compression,
            use_fact_checking=request.use_fact_checking,
            temperature=request.temperature,
            max_tokens=request.max_tokens,
            system_prompt=request.system_prompt
        )
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to process RAG query: {e}")

@router.delete("/rag/clear")
async def clear_index(rag_pipeline = Depends(get_pipeline)):
    """Clear the index in the RAG pipeline."""
    try:
        rag_pipeline.clear()
        return {"status": "success", "message": "Index cleared"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to clear index: {e}")

@router.get("/rag/stats")
async def get_stats(rag_pipeline = Depends(get_pipeline)):
    """Get statistics from the RAG pipeline."""
    try:
        stats = rag_pipeline.stats()
        return stats
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to get stats: {e}")

# --- New Module-Specific Endpoints ---

@router.post("/rag/expand-query")
async def expand_query(request: ExpandQueryRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Directly access the Query Expansion module.
    """
    try:
        expanded = rag_pipeline.query_expander.expand(request.query, request.num_expansions)
        return {"original": request.query, "expanded": expanded}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to expand query: {e}")

@router.post("/rag/rerank")
async def rerank_documents(request: RerankRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Directly access the Reranker module.
    """
    try:
        # Convert Pydantic models to dicts
        docs_dicts = [doc.model_dump() for doc in request.documents]
        reranked = rag_pipeline.reranker.rerank(request.query, docs_dicts, request.top_k)
        return reranked
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to rerank documents: {e}")

@router.post("/rag/compress")
async def compress_context(request: CompressRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Directly access the Context Compression module.
    """
    try:
        docs_dicts = [doc.model_dump() for doc in request.documents]
        compressed = rag_pipeline.context_compressor.compress(
            request.query,
            docs_dicts,
            request.max_sentences_per_doc
        )
        return compressed
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to compress context: {e}")

@router.post("/rag/fact-check")
async def fact_check(request: FactCheckRequest, rag_pipeline = Depends(get_pipeline)):
    """
    Directly access the Fact Checker module.
    """
    try:
        docs_dicts = [doc.model_dump() for doc in request.documents]
        result = rag_pipeline.fact_checker.check(request.answer, docs_dicts)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fact check: {e}")
