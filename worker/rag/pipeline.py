"""
RAG Pipeline - combines retrieval and generation
"""

from typing import List, Dict, Any, Optional
from rag.vector_store import VectorStore
from rag.query_expansion import QueryExpansion
from rag.reranker import Reranker
from rag.context_compressor import ContextCompressor
from rag.fact_checker import FactChecker


class RAGPipeline:
    """
    Complete RAG pipeline:
    1. Index documents with embeddings
    2. Retrieve relevant context with query expansion and reranking
    3. Compress context to keep only relevant information
    4. Generate response with context
    5. Fact-check the response against the context
    """
    
    def __init__(self, embedding_model, llm_model, reranker_model, dimension: int = 384):
        self.embedding_model = embedding_model
        self.llm_model = llm_model
        self.vector_store = VectorStore(dimension=dimension)
        self.query_expander = QueryExpansion(llm_model)
        self.reranker = reranker_model
        self.context_compressor = ContextCompressor(reranker_model)
        self.fact_checker = FactChecker(llm_model)

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
        Retrieve relevant documents for a query using expansion and reranking.
        
        Args:
            query: Search query
            top_k: Number of final results to return
            
        Returns:
            List of relevant documents with scores
        """
        # 1. Expand the query
        expanded_queries = self.query_expander.expand(query)
        
        # 2. Retrieve for each query
        retrieved_docs = {}
        # We retrieve more documents initially to give the reranker more to work with
        initial_k = top_k * 5

        for q in expanded_queries:
            query_embedding = self.embedding_model.encode(q)[0]
            results = self.vector_store.search(query_embedding, initial_k)
            for doc_id, score, doc in results:
                if doc_id not in retrieved_docs and doc is not None:
                    retrieved_docs[doc_id] = {
                        "id": doc_id,
                        "content": doc.get("content", ""),
                        "score": score,
                        "metadata": doc.get("metadata", {})
                    }
        
        # 3. Rerank the collected documents
        all_docs = list(retrieved_docs.values())
        reranked_docs = self.reranker.rerank(query, all_docs, top_k)

        return reranked_docs
    
    def query(
        self, 
        query: str, 
        top_k: int = 5,
        use_compression: bool = True,
        use_fact_checking: bool = True, # New flag for fact checking
        system_prompt: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 1024
    ) -> Dict[str, Any]:
        """
        Full RAG query: retrieve, compress, generate, and fact-check response
        
        Args:
            query: User query
            top_k: Number of context documents
            use_compression: Whether to compress the context before generation
            use_fact_checking: Whether to fact-check the final answer
            system_prompt: Optional system prompt
            temperature: Generation temperature
            max_tokens: Max tokens to generate
            
        Returns:
            Response with answer, sources, and fact-checking analysis
        """
        # 1. Retrieve relevant context
        sources = self.retrieve(query, top_k)
        
        # 2. (Optional) Compress the context
        if use_compression:
            context_for_generation = self.context_compressor.compress(query, sources)
        else:
            context_for_generation = sources

        # 3. Build context string
        context = "\n\n".join([
            f"[Source {i+1}]: {src['content']}"
            for i, src in enumerate(context_for_generation)
        ])
        
        # 4. Build messages
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
        
        # 5. Generate response
        response = self.llm_model.generate(
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens
        )

        answer = response["choices"][0]["message"]["content"]

        # 6. (Optional) Fact-check the response
        fact_check_result = None
        if use_fact_checking:
            fact_check_result = self.fact_checker.check(answer, context_for_generation)

        return {
            "answer": answer,
            "sources": sources, # Return original, uncompressed sources
            "model": response["model"],
            "fact_check": fact_check_result
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
