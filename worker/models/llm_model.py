"""
LLM Model with support for multiple backends:
- Mock (for demo/testing)
- vLLM (for production GPU inference)
- LlamaCpp (for CPU inference)
"""

import time
import uuid
from typing import List, Dict, Any, Optional, Generator
from models.config import Settings

settings = Settings()


class LLMModel:
    """Multi-backend LLM model"""
    
    def __init__(self):
        self.mode = settings.llm_mode
        self.model = None
        self._loaded = False
        self.model_name = "local-llm"
    
    def initialize(self):
        """Initialize the LLM based on configured mode"""
        print(f"Initializing LLM in mode: {self.mode}")
        
        if self.mode == "mock":
            self._init_mock()
        elif self.mode == "vllm":
            self._init_vllm()
        elif self.mode == "llamacpp":
            self._init_llamacpp()
        else:
            print(f"Unknown mode: {self.mode}, falling back to mock")
            self._init_mock()
    
    def _init_mock(self):
        """Initialize mock mode (no actual model)"""
        print("Using mock LLM for demo")
        self._loaded = True
        self.model_name = "mock-llm"
    
    def _init_vllm(self):
        """Initialize vLLM for GPU inference"""
        try:
            from vllm import LLM, SamplingParams
            
            print(f"Loading vLLM model: {settings.vllm_model}")
            self.model = LLM(
                model=settings.vllm_model,
                tensor_parallel_size=settings.vllm_tensor_parallel_size,
                gpu_memory_utilization=settings.vllm_gpu_memory_utilization,
                trust_remote_code=True
            )
            self.model_name = settings.vllm_model
            self._loaded = True
            print("vLLM model loaded successfully")
            
        except Exception as e:
            print(f"Failed to load vLLM: {e}")
            print("Falling back to mock mode")
            self._init_mock()
    
    def _init_llamacpp(self):
        """Initialize llama.cpp for CPU inference"""
        try:
            from llama_cpp import Llama
            
            if settings.llm_model_path:
                print(f"Loading llama.cpp model: {settings.llm_model_path}")
                self.model = Llama(
                    model_path=settings.llm_model_path,
                    n_ctx=4096,
                    n_threads=4
                )
                self.model_name = settings.llm_model_path.split("/")[-1]
                self._loaded = True
                print("llama.cpp model loaded successfully")
            else:
                print("No model path configured for llama.cpp")
                self._init_mock()
                
        except Exception as e:
            print(f"Failed to load llama.cpp: {e}")
            print("Falling back to mock mode")
            self._init_mock()
    
    def is_loaded(self) -> bool:
        return self._loaded
    
    def generate(
        self,
        messages: List[Dict[str, str]],
        temperature: float = 0.7,
        max_tokens: int = 1024,
        top_p: float = 0.9,
        stop: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        """
        Generate completion for messages
        
        Args:
            messages: List of message dicts with 'role' and 'content'
            temperature: Sampling temperature
            max_tokens: Maximum tokens to generate
            top_p: Top-p sampling
            stop: Stop sequences
            
        Returns:
            OpenAI-compatible response dict
        """
        if self.mode == "mock":
            return self._generate_mock(messages, temperature, max_tokens)
        elif self.mode == "vllm":
            return self._generate_vllm(messages, temperature, max_tokens, top_p, stop)
        elif self.mode == "llamacpp":
            return self._generate_llamacpp(messages, temperature, max_tokens, top_p, stop)
        else:
            return self._generate_mock(messages, temperature, max_tokens)
    
    def _generate_mock(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int
    ) -> Dict[str, Any]:
        """Generate mock response for demo"""
        # Get the last user message
        user_message = ""
        for msg in reversed(messages):
            if msg.get("role") == "user":
                user_message = msg.get("content", "")
                break
        
        # Generate a contextual mock response
        mock_responses = {
            "hello": "Hello! I'm a local AI assistant running on your machine. How can I help you today?",
            "how are you": "I'm doing great! I'm a local LLM running in mock mode for demo purposes. I can help answer questions and have conversations.",
            "what can you do": "I'm a local AI assistant. In production mode, I can run actual LLM inference using vLLM or llama.cpp. Currently running in mock mode for demo.",
            "test": "Test successful! The AI Worker is running correctly. In production, this would use an actual LLM model.",
        }
        
        # Check for keyword matches
        response_text = None
        user_lower = user_message.lower()
        for keyword, response in mock_responses.items():
            if keyword in user_lower:
                response_text = response
                break
        
        if not response_text:
            response_text = f"I received your message: \"{user_message[:100]}{'...' if len(user_message) > 100 else ''}\"\n\nThis is a mock response from the AI Worker. In production mode with vLLM or llama.cpp, you would receive actual AI-generated responses."
        
        # Simulate some latency
        time.sleep(0.1)
        
        return {
            "id": f"chatcmpl-{uuid.uuid4().hex[:8]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": self.model_name,
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": response_text
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": sum(len(m.get("content", "").split()) for m in messages),
                "completion_tokens": len(response_text.split()),
                "total_tokens": sum(len(m.get("content", "").split()) for m in messages) + len(response_text.split())
            }
        }
    
    def _generate_vllm(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        top_p: float,
        stop: Optional[List[str]]
    ) -> Dict[str, Any]:
        """Generate using vLLM"""
        from vllm import SamplingParams
        
        # Format messages into prompt
        prompt = self._format_messages(messages)
        
        sampling_params = SamplingParams(
            temperature=temperature,
            max_tokens=max_tokens,
            top_p=top_p,
            stop=stop
        )
        
        outputs = self.model.generate([prompt], sampling_params)
        output = outputs[0]
        
        return {
            "id": f"chatcmpl-{uuid.uuid4().hex[:8]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": self.model_name,
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": output.outputs[0].text
                },
                "finish_reason": "stop" if output.outputs[0].finish_reason == "stop" else "length"
            }],
            "usage": {
                "prompt_tokens": len(output.prompt_token_ids),
                "completion_tokens": len(output.outputs[0].token_ids),
                "total_tokens": len(output.prompt_token_ids) + len(output.outputs[0].token_ids)
            }
        }
    
    def _generate_llamacpp(
        self,
        messages: List[Dict[str, str]],
        temperature: float,
        max_tokens: int,
        top_p: float,
        stop: Optional[List[str]]
    ) -> Dict[str, Any]:
        """Generate using llama.cpp"""
        prompt = self._format_messages(messages)
        
        output = self.model(
            prompt,
            max_tokens=max_tokens,
            temperature=temperature,
            top_p=top_p,
            stop=stop or []
        )
        
        return {
            "id": f"chatcmpl-{uuid.uuid4().hex[:8]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": self.model_name,
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": output["choices"][0]["text"]
                },
                "finish_reason": output["choices"][0].get("finish_reason", "stop")
            }],
            "usage": output.get("usage", {
                "prompt_tokens": 0,
                "completion_tokens": 0,
                "total_tokens": 0
            })
        }
    
    def _format_messages(self, messages: List[Dict[str, str]]) -> str:
        """Format messages into a prompt string"""
        prompt_parts = []
        
        for msg in messages:
            role = msg.get("role", "user")
            content = msg.get("content", "")
            
            if role == "system":
                prompt_parts.append(f"System: {content}")
            elif role == "user":
                prompt_parts.append(f"User: {content}")
            elif role == "assistant":
                prompt_parts.append(f"Assistant: {content}")
        
        prompt_parts.append("Assistant:")
        return "\n\n".join(prompt_parts)
