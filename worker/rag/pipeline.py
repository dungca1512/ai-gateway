"""
RAG Pipeline - combines retrieval and generation
"""

from typing import List, Dict, Any, Optional
from rag.vector_store import VectorStore


class RAGPipeline:
    """
    Complete RAG pipeline:
    1. Index documents with embeddings
    2. Retrieve relevant context
    3. Generate response with context
    """
    
    def __init__(self, embedding_model, llm_model, dimension: int = 384):
        self.embedding_model = embedding_model
        self.llm_model = llm_model
        self.vector_store = VectorStore(dimension=dimension)
        
    def index_documents(
        self, 
        documents: List[Dict[str, Any]], 
        chunk_size: int = 512,
        chunk_overlap: int = 50
    ) -> int:
        """
        Index documents with chunking and embedding
        
        Args:
            documents: List of {"content": str, "metadata": dict}
            chunk_size: Size of text chunks
            chunk_overlap: Overlap between chunks
            
        Returns:
            Number of chunks indexed
        """
        indexed = 0
        
        for doc in documents:
            content = doc.get("content", "")
            metadata = doc.get("metadata", {})
            
            # Chunk the content
            chunks = self._chunk_text(content, chunk_size, chunk_overlap)
            
            for i, chunk in enumerate(chunks):
                # Generate embedding
                embedding = self.embedding_model.encode(chunk)[0]
                
                # Store in vector store
                self.vector_store.add(
                    embedding,
                    {
                        "content": chunk,
                        "metadata": {
                            **metadata,
                            "chunk_index": i
                        }
                    }
                )
                indexed += 1
        
        return indexed
    
    def retrieve(self, query: str, top_k: int = 5) -> List[Dict[str, Any]]:
        """
        Retrieve relevant documents for a query
        
        Args:
            query: Search query
            top_k: Number of results
            
        Returns:
            List of relevant documents with scores
        """
        # Generate query embedding
        query_embedding = self.embedding_model.encode(query)[0]
        
        # Search vector store
        results = self.vector_store.search(query_embedding, top_k)
        
        return [
            {
                "id": doc_id,
                "content": doc.get("content", ""),
                "score": score,
                "metadata": doc.get("metadata", {})
            }
            for doc_id, score, doc in results
            if doc is not None
        ]
    
    def query(
        self, 
        query: str, 
        top_k: int = 5,
        system_prompt: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 1024
    ) -> Dict[str, Any]:
        """
        Full RAG query: retrieve context and generate response
        
        Args:
            query: User query
            top_k: Number of context documents
            system_prompt: Optional system prompt
            temperature: Generation temperature
            max_tokens: Max tokens to generate
            
        Returns:
            Response with answer and sources
        """
        # Retrieve relevant context
        sources = self.retrieve(query, top_k)
        
        # Build context string
        context = "\n\n".join([
            f"[Source {i+1}]: {src['content']}"
            for i, src in enumerate(sources)
        ])
        
        # Build messages
        system = system_prompt or (
            "You are a helpful assistant that answers questions based on the provided context. "
            "If the context doesn't contain relevant information, say so clearly. "
            "Always cite which source(s) you used."
        )
        
        user_message = f"""Context:
{context}

Question: {query}

Please answer based on the context above."""
        
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": user_message}
        ]
        
        # Generate response
        response = self.llm_model.generate(
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens
        )
        
        return {
            "answer": response["choices"][0]["message"]["content"],
            "sources": sources,
            "model": response["model"]
        }
    
    def clear(self):
        """Clear the index"""
        self.vector_store.clear()
    
    def stats(self) -> Dict[str, Any]:
        """Get pipeline statistics"""
        return {
            "total_documents": self.vector_store.count(),
            "dimension": self.vector_store.dimension
        }
    
    def _chunk_text(self, text: str, chunk_size: int, overlap: int) -> List[str]:
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
