"""
Models module - handles LLM and embedding model initialization
"""

from models.embedding_model import EmbeddingModel
from models.llm_model import LLMModel

# Global instances
embedding_model = EmbeddingModel()
llm_model = LLMModel()

__all__ = ['embedding_model', 'llm_model', 'EmbeddingModel', 'LLMModel']
