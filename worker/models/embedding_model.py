"""
Embedding model using Sentence Transformers
"""

import numpy as np
from typing import List, Union, Optional
from models.config import Settings

settings = Settings()


class EmbeddingModel:
    """Sentence Transformer embedding model"""
    
    def __init__(self):
        self.model = None
        self.model_name = settings.embedding_model
        self.dimension = settings.embedding_dimension
        self._loaded = False
    
    def initialize(self):
        """Load the embedding model"""
        try:
            from sentence_transformers import SentenceTransformer
            
            print(f"Loading embedding model: {self.model_name}")
            self.model = SentenceTransformer(self.model_name)
            self.dimension = self.model.get_sentence_embedding_dimension()
            self._loaded = True
            print(f"Embedding model loaded. Dimension: {self.dimension}")
            
        except Exception as e:
            print(f"Warning: Could not load sentence-transformers model: {e}")
            print("Using mock embeddings instead")
            self._loaded = True  # Use mock mode
    
    def is_loaded(self) -> bool:
        return self._loaded
    
    def encode(self, texts: Union[str, List[str]], normalize: bool = True) -> np.ndarray:
        """
        Generate embeddings for texts
        
        Args:
            texts: Single text or list of texts
            normalize: Whether to L2 normalize the embeddings
            
        Returns:
            numpy array of embeddings
        """
        if isinstance(texts, str):
            texts = [texts]
        
        if self.model is not None:
            embeddings = self.model.encode(
                texts,
                normalize_embeddings=normalize,
                show_progress_bar=False
            )
        else:
            # Mock embeddings for demo without GPU
            embeddings = self._mock_embeddings(texts)
        
        return np.array(embeddings)
    
    def _mock_embeddings(self, texts: List[str]) -> List[List[float]]:
        """Generate deterministic mock embeddings based on text content"""
        embeddings = []
        for text in texts:
            # Create a deterministic embedding based on text hash
            np.random.seed(hash(text) % (2**32))
            embedding = np.random.randn(self.dimension).astype(np.float32)
            # Normalize
            embedding = embedding / np.linalg.norm(embedding)
            embeddings.append(embedding.tolist())
        return embeddings
    
    def similarity(self, embedding1: np.ndarray, embedding2: np.ndarray) -> float:
        """Calculate cosine similarity between two embeddings"""
        return float(np.dot(embedding1, embedding2))
