from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer, util
from better_profanity import profanity

app = FastAPI(title="diplomatAI Quality Check Service")

model = SentenceTransformer("all-MiniLM-L6-v2")
profanity.load_censor_words()


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

    prompt_toxic = profanity.contains_profanity(prompt)
    answer_toxic = profanity.contains_profanity(answer)

    if prompt_toxic or answer_toxic:
        return ValidationResponse(
            qc_passed=False,
            qc_score=15,
            relevance_score=0.0,
            toxicity_flagged=True,
            reason="Toxicity detected in prompt or answer."
        )

    prompt_embedding = model.encode(prompt, convert_to_tensor=True)
    answer_embedding = model.encode(answer, convert_to_tensor=True)
    cosine_score = util.cos_sim(prompt_embedding, answer_embedding).item()

    if cosine_score < 0.3:
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


@app.get("/health")
async def health_check():
    return {"status": "up", "model_loaded": True}
