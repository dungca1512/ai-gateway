"""
Reranker Module using a Cross-Encoder model.
"""
from typing import List, Dict, Any

class Reranker:
    """
    Reranks documents based on relevance to a query using a Cross-Encoder.
    """
    def __init__(self, model_name: str = 'cross-encoder/ms-marco-MiniLM-L-6-v2'):
        self.model = None
        self.model_name = model_name
        self._loaded = False

    def initialize(self):
        """Load the Cross-Encoder model."""
        try:
            from sentence_transformers import CrossEncoder
            print(f"Loading reranker model: {self.model_name}")
            self.model = CrossEncoder(self.model_name)
            self._loaded = True
            print("Reranker model loaded.")
        except Exception as e:
            print(f"Warning: Could not load Cross-Encoder model: {e}")
            print("Reranker will be disabled.")
            self._loaded = False

    def is_loaded(self) -> bool:
        return self._loaded

    def rerank(self, query: str, documents: List[Dict[str, Any]], top_k: int = 5) -> List[Dict[str, Any]]:
        """
        Reranks a list of documents for a given query.

        Args:
            query: The search query.
            documents: A list of documents retrieved from the vector store.
                       Each dict should have a "content" key.
            top_k: The number of documents to return.

        Returns:
            A sorted list of the top_k most relevant documents.
        """
        if not self.is_loaded() or not documents:
            # Return original top_k if reranker is not available or no docs
            return documents[:top_k]

        # Create pairs of [query, document_content]
        pairs = [[query, doc['content']] for doc in documents]

        # Predict scores
        scores = self.model.predict(pairs, show_progress_bar=False)

        # Add scores to documents
        for doc, score in zip(documents, scores):
            doc['rerank_score'] = float(score)

        # Sort documents by the new rerank_score in descending order
        reranked_docs = sorted(documents, key=lambda x: x['rerank_score'], reverse=True)

        return reranked_docs[:top_k]
