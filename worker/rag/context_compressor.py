"""
Context Compressor Module
"""
from typing import List, Dict, Any

class ContextCompressor:
    """
    Compresses the context by extracting the most relevant sentences from documents.
    """
    def __init__(self, reranker_model: Any, sentence_splitter: Any = None):
        self.reranker = reranker_model
        # A simple default sentence splitter
        self.sentence_splitter = sentence_splitter or (lambda text: text.split('. '))

    def compress(self, query: str, documents: List[Dict[str, Any]], max_sentences_per_doc: int = 3) -> List[Dict[str, Any]]:
        """
        Compresses a list of documents based on a query.

        Args:
            query: The user's query.
            documents: The list of documents retrieved from the reranker.
            max_sentences_per_doc: The maximum number of relevant sentences to keep from each document.

        Returns:
            A list of documents with their content compressed to the most relevant sentences.
        """
        if not self.reranker or not self.reranker.is_loaded():
            print("Warning: Reranker not available for context compression. Skipping.")
            return documents

        compressed_docs = []
        for doc in documents:
            content = doc.get('content', '')
            if not content:
                continue

            # 1. Split the document content into sentences
            sentences = self.sentence_splitter(content)
            if not sentences:
                continue

            # 2. Score each sentence against the query
            pairs = [[query, s] for s in sentences]
            scores = self.reranker.model.predict(pairs, show_progress_bar=False)

            # 3. Combine sentences with their scores
            scored_sentences = list(zip(sentences, scores))

            # 4. Sort sentences by score and select the top ones
            scored_sentences.sort(key=lambda x: x[1], reverse=True)
            top_sentences = [s[0] for s in scored_sentences[:max_sentences_per_doc]]

            # 5. Create a new compressed document
            new_doc = doc.copy()
            new_doc['content'] = ". ".join(top_sentences)
            # Optionally, add original content for reference
            # new_doc['original_content'] = content
            compressed_docs.append(new_doc)

        return compressed_docs
