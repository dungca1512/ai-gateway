"""
Simple in-memory vector store using FAISS
"""

import numpy as np
from typing import List, Dict, Any, Optional, Tuple
import os


class VectorStore:
    """In-memory vector store with FAISS-like functionality"""
    
    def __init__(self, dimension: int = 384):
        self.dimension = dimension
        self.vectors: List[np.ndarray] = []
        self.documents: List[Dict[str, Any]] = []
        self.index = None
        
    def add(self, embedding: np.ndarray, document: Dict[str, Any]) -> int:
        """Add a single embedding and document"""
        doc_id = len(self.vectors)
        self.vectors.append(embedding)
        self.documents.append({
            "id": doc_id,
            **document
        })
        return doc_id
    
    def add_batch(self, embeddings: np.ndarray, documents: List[Dict[str, Any]]) -> List[int]:
        """Add multiple embeddings and documents"""
        ids = []
        for emb, doc in zip(embeddings, documents):
            ids.append(self.add(emb, doc))
        return ids
    
    def search(self, query_embedding: np.ndarray, top_k: int = 5) -> List[Tuple[int, float, Dict[str, Any]]]:
        """
        Search for similar documents
        
        Returns:
            List of (doc_id, similarity_score, document)
        """
        if not self.vectors:
            return []
        
        # Normalize query
        query_norm = query_embedding / np.linalg.norm(query_embedding)
        
        # Calculate cosine similarities
        similarities = []
        for i, vec in enumerate(self.vectors):
            vec_norm = vec / np.linalg.norm(vec)
            sim = float(np.dot(query_norm, vec_norm))
            similarities.append((i, sim, self.documents[i]))
        
        # Sort by similarity
        similarities.sort(key=lambda x: x[1], reverse=True)
        
        return similarities[:top_k]
    
    def delete(self, doc_id: int) -> bool:
        """Delete a document by ID"""
        if 0 <= doc_id < len(self.documents):
            # Mark as deleted (keep indices stable)
            self.documents[doc_id] = None
            self.vectors[doc_id] = np.zeros(self.dimension)
            return True
        return False
    
    def clear(self):
        """Clear all data"""
        self.vectors = []
        self.documents = []
        
    def count(self) -> int:
        """Get number of documents"""
        return len([d for d in self.documents if d is not None])
    
    def save(self, path: str):
        """Save to disk"""
        os.makedirs(os.path.dirname(path), exist_ok=True)
        np.savez(
            path,
            vectors=np.array(self.vectors),
            documents=np.array(self.documents, dtype=object)
        )
    
    def load(self, path: str):
        """Load from disk"""
        if os.path.exists(path):
            data = np.load(path, allow_pickle=True)
            self.vectors = list(data['vectors'])
            self.documents = list(data['documents'])
