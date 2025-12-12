"""
RAG (Retrieval Augmented Generation) router
"""

import uuid
from typing import List, Optional, Dict, Any

from fastapi import APIRouter, HTTPException, UploadFile, File
from pydantic import BaseModel, Field

router = APIRouter()

# In-memory document store for demo
documents_store: Dict[str, Dict[str, Any]] = {}
vectors_store: List[Dict[str, Any]] = []


class Document(BaseModel):
    id: Optional[str] = None
    content: str
    metadata: Optional[Dict[str, Any]] = None


class IndexRequest(BaseModel):
    documents: List[Document]
    chunk_size: int = Field(default=512, ge=100, le=2000)
    chunk_overlap: int = Field(default=50, ge=0, le=200)


class SearchRequest(BaseModel):
    query: str
    top_k: int = Field(default=5, ge=1, le=20)
    filter: Optional[Dict[str, Any]] = None


class SearchResult(BaseModel):
    id: str
    content: str
    score: float
    metadata: Optional[Dict[str, Any]] = None


class RAGRequest(BaseModel):
    query: str
    top_k: int = Field(default=5, ge=1, le=20)
    temperature: float = Field(default=0.7, ge=0, le=2)
    max_tokens: int = Field(default=1024, ge=1, le=4096)
    system_prompt: Optional[str] = None


class RAGResponse(BaseModel):
    answer: str
    sources: List[SearchResult]
    model: str


@router.post("/rag/index")
async def index_documents(request: IndexRequest):
    """
    Index documents for RAG retrieval
    """
    from models import embedding_model
    
    if not embedding_model.is_loaded():
        raise HTTPException(status_code=503, detail="Embedding model not loaded")
    
    indexed_count = 0
    
    for doc in request.documents:
        doc_id = doc.id or str(uuid.uuid4())
        
        # Simple chunking
        chunks = chunk_text(doc.content, request.chunk_size, request.chunk_overlap)
        
        for i, chunk in enumerate(chunks):
            chunk_id = f"{doc_id}_{i}"
            
            # Generate embedding
            embedding = embedding_model.encode(chunk)[0]
            
            # Store document and vector
            documents_store[chunk_id] = {
                "content": chunk,
                "metadata": {
                    **(doc.metadata or {}),
                    "doc_id": doc_id,
                    "chunk_index": i
                }
            }
            
            vectors_store.append({
                "id": chunk_id,
                "embedding": embedding
            })
            
            indexed_count += 1
    
    return {
        "status": "success",
        "indexed_chunks": indexed_count,
        "total_documents": len(request.documents)
    }


@router.post("/rag/search", response_model=List[SearchResult])
async def search_documents(request: SearchRequest):
    """
    Search indexed documents
    """
    from models import embedding_model
    import numpy as np
    
    if not embedding_model.is_loaded():
        raise HTTPException(status_code=503, detail="Embedding model not loaded")
    
    if not vectors_store:
        return []
    
    # Generate query embedding
    query_embedding = embedding_model.encode(request.query)[0]
    
    # Calculate similarities
    results = []
    for vec_data in vectors_store:
        similarity = float(np.dot(query_embedding, vec_data["embedding"]))
        doc_data = documents_store.get(vec_data["id"], {})
        
        results.append(SearchResult(
            id=vec_data["id"],
            content=doc_data.get("content", ""),
            score=similarity,
            metadata=doc_data.get("metadata")
        ))
    
    # Sort by similarity and return top_k
    results.sort(key=lambda x: x.score, reverse=True)
    return results[:request.top_k]


@router.post("/rag/query", response_model=RAGResponse)
async def rag_query(request: RAGRequest):
    """
    Full RAG pipeline: retrieve + generate
    """
    from models import embedding_model, llm_model
    
    if not embedding_model.is_loaded() or not llm_model.is_loaded():
        raise HTTPException(status_code=503, detail="Models not loaded")
    
    # Search for relevant documents
    search_request = SearchRequest(query=request.query, top_k=request.top_k)
    sources = await search_documents(search_request)
    
    # Build context from retrieved documents
    context = "\n\n".join([
        f"[Source {i+1}]: {result.content}"
        for i, result in enumerate(sources)
    ])
    
    # Build prompt
    system_prompt = request.system_prompt or """You are a helpful assistant that answers questions based on the provided context. 
If the context doesn't contain relevant information, say so clearly.
Always cite which source(s) you used in your answer."""
    
    user_prompt = f"""Context:
{context}

Question: {request.query}

Please answer the question based on the context provided above."""
    
    # Generate response
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt}
    ]
    
    result = llm_model.generate(
        messages=messages,
        temperature=request.temperature,
        max_tokens=request.max_tokens
    )
    
    return RAGResponse(
        answer=result["choices"][0]["message"]["content"],
        sources=sources,
        model=result["model"]
    )


@router.delete("/rag/clear")
async def clear_index():
    """Clear all indexed documents"""
    global documents_store, vectors_store
    documents_store = {}
    vectors_store = []
    return {"status": "success", "message": "Index cleared"}


@router.get("/rag/stats")
async def get_stats():
    """Get index statistics"""
    return {
        "total_chunks": len(vectors_store),
        "total_documents": len(set(
            doc.get("metadata", {}).get("doc_id") 
            for doc in documents_store.values()
        ))
    }


def chunk_text(text: str, chunk_size: int, overlap: int) -> List[str]:
    """Split text into overlapping chunks"""
    words = text.split()
    chunks = []
    
    if len(words) <= chunk_size:
        return [text]
    
    start = 0
    while start < len(words):
        end = start + chunk_size
        chunk = " ".join(words[start:end])
        chunks.append(chunk)
        start = end - overlap
    
    return chunks
