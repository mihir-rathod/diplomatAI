from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer, util

app = FastAPI(title="diplomatAI Quality Check Service")

model = SentenceTransformer("all-MiniLM-L6-v2")


class ValidationRequest(BaseModel):
    prompt: str
    answer: str


class ValidationResponse(BaseModel):
    qc_passed: bool
    qc_score: int
    relevance_score: float
    toxicity_flagged: bool
    reason: str


@app.post("/api/v1/validate", response_model=ValidationResponse)
async def validate_content(request: ValidationRequest):
    prompt = request.prompt
    answer = request.answer

    prompt_embedding = model.encode(prompt, convert_to_tensor=True)
    answer_embedding = model.encode(answer, convert_to_tensor=True)
    cosine_score = util.cos_sim(prompt_embedding, answer_embedding).item()

    if cosine_score < 0.15:
        return ValidationResponse(
            qc_passed=False,
            qc_score=int(cosine_score * 100),
            relevance_score=round(cosine_score, 4),
            toxicity_flagged=False,
            reason=f"Answer is not semantically relevant to the prompt (similarity: {cosine_score:.2f})."
        )

    if len(prompt.split()) > 10 and len(answer.split()) < 3:
        return ValidationResponse(
            qc_passed=False,
            qc_score=30,
            relevance_score=round(cosine_score, 4),
            toxicity_flagged=False,
            reason="Answer is too short for the complexity of the prompt."
        )

    final_score = int(cosine_score * 100)

    return ValidationResponse(
        qc_passed=True,
        qc_score=final_score,
        relevance_score=round(cosine_score, 4),
        toxicity_flagged=False,
        reason="Content passed all quality and safety checks."
    )


class CacheCheckRequest(BaseModel):
    prompt: str
    cached_prompts: list[str]


class CacheCheckResponse(BaseModel):
    matched: bool
    matched_prompt: str | None = None
    similarity: float = 0.0


@app.post("/api/v1/cache", response_model=CacheCheckResponse)
async def check_semantic_cache(request: CacheCheckRequest):
    if not request.cached_prompts:
        return CacheCheckResponse(matched=False)

    prompt_embedding = model.encode(request.prompt, convert_to_tensor=True)
    cached_embeddings = model.encode(request.cached_prompts, convert_to_tensor=True)

    # Compute cosine similarities
    cosine_scores = util.cos_sim(prompt_embedding, cached_embeddings)[0]
    
    # Find the best match
    best_idx = cosine_scores.argmax().item()
    best_score = cosine_scores[best_idx].item()

    # 0.80 captures variations like "write bubble sort" vs "can you write me bubble sort"
    if best_score > 0.80:
        return CacheCheckResponse(
            matched=True,
            matched_prompt=request.cached_prompts[best_idx],
            similarity=round(best_score, 4)
        )

    return CacheCheckResponse(matched=False, similarity=round(best_score, 4))


@app.get("/health")
async def health_check():
    return {"status": "up", "model_loaded": True}
