"""
Query Expansion Module
"""
from typing import List, Dict, Any

class QueryExpansion:
    """
    Expands a user query into multiple related queries using an LLM.
    """
    def __init__(self, llm_model: Any):
        self.llm_model = llm_model

    def expand(self, query: str, num_expansions: int = 3) -> List[str]:
        """
        Generates multiple versions of a query.

        Args:
            query: The original user query.
            num_expansions: The number of expanded queries to generate.

        Returns:
            A list containing the original query and the expanded queries.
        """
        if not self.llm_model or not hasattr(self.llm_model, 'generate'):
            print("Warning: LLM model not available for query expansion. Skipping.")
            return [query]

        prompt = f"""You are an expert in information retrieval. Your task is to expand a user's query to improve search results.
Generate {num_expansions} alternative queries that are semantically related to the original query.
The queries should be diverse and cover different aspects or phrasings of the original topic.
Return ONLY a numbered list of the new queries. Do not include the original query.

Original Query: "{query}"
"""

        messages = [
            {"role": "system", "content": "You are a helpful assistant that generates alternative search queries."},
            {"role": "user", "content": prompt}
        ]

        try:
            response = self.llm_model.generate(
                messages=messages,
                temperature=0.5,
                max_tokens=200
            )
            content = response["choices"][0]["message"]["content"]

            # Parse the numbered list of queries
            expanded_queries = [q.strip().split('. ', 1)[-1] for q in content.strip().split('\n') if '. ' in q]

            # Combine and deduplicate
            all_queries = [query] + expanded_queries
            return list(dict.fromkeys(all_queries))

        except Exception as e:
            print(f"Error during query expansion: {e}")
            return [query]

