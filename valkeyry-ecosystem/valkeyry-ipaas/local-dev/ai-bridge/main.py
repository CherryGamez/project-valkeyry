"""
AI-Bridge Sidecar — Valkeyry iPaaS
----------------------------------
A tiny FastAPI service that exposes a reactive, OpenAI-style enrichment endpoint
backed by the `emergentintegrations` library + the Emergent Universal LLM key.

OpenTelemetry: W3C trace-context is extracted from inbound headers (`traceparent`)
that the Java side propagates via Micrometer Tracing. We then record a span around
each LLM call so the trace stitches end-to-end across Java → Python → upstream provider.
"""
import os
import uuid
import json
import logging
from typing import Optional, Literal, Any, Dict

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from emergentintegrations.llm.chat import LlmChat, UserMessage

# === OpenTelemetry boot ====================================================
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, ConsoleSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.propagate import extract
from opentelemetry.trace import SpanKind

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ai-bridge")

DEFAULT_PROVIDER = os.environ.get("AI_DEFAULT_PROVIDER", "openai")
DEFAULT_MODEL    = os.environ.get("AI_DEFAULT_MODEL", "gpt-4.1-mini")
EMERGENT_LLM_KEY = os.environ.get("EMERGENT_LLM_KEY")
OTLP_ENDPOINT    = os.environ.get("OTLP_HTTP_ENDPOINT", "http://jaeger:4318/v1/traces")
SERVICE_NAME     = os.environ.get("OTEL_SERVICE_NAME", "valkeyry-ai-bridge")

resource = Resource.create({"service.name": SERVICE_NAME, "service.version": "1.0.0"})
provider = TracerProvider(resource=resource)
try:
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=OTLP_ENDPOINT)))
    log.info("OTLP exporter wired to %s", OTLP_ENDPOINT)
except Exception as e:
    log.warning("Falling back to console span exporter: %s", e)
    provider.add_span_processor(BatchSpanProcessor(ConsoleSpanExporter()))
trace.set_tracer_provider(provider)
tracer = trace.get_tracer(SERVICE_NAME)

if not EMERGENT_LLM_KEY:
    log.warning("EMERGENT_LLM_KEY is not set; calls to /enrich and /decide will fail.")

app = FastAPI(title="Valkeyry AI-Bridge", version="1.0.0")
FastAPIInstrumentor.instrument_app(app, tracer_provider=provider)


class EnrichRequest(BaseModel):
    payload:        Dict[str, Any]                              = Field(..., description="Raw message body parsed as JSON.")
    provider:       Optional[Literal["openai", "anthropic", "gemini"]] = None
    model:          Optional[str]                               = None
    system_prompt:  Optional[str] = "You are a JSON enrichment assistant. Return ONLY a JSON object."
    session_id:     Optional[str] = None
    instruction:    Optional[str] = "Add an 'ai_classification' string field and an 'ai_priority' (LOW|NORMAL|HIGH) field to the payload. Return the full enriched JSON object."


class EnrichResponse(BaseModel):
    enriched: Dict[str, Any]
    provider: str
    model: str
    trace_id: Optional[str] = None


class DecideRequest(BaseModel):
    payload:       Dict[str, Any]
    options:       list[str]                                    = Field(..., description="Allowed routing decisions.")
    provider:      Optional[Literal["openai", "anthropic", "gemini"]] = None
    model:         Optional[str] = None
    system_prompt: Optional[str] = "You route messages. Choose exactly one option."
    session_id:    Optional[str] = None


class DecideResponse(BaseModel):
    decision: str
    confidence: Optional[float] = None
    rationale: Optional[str] = None
    trace_id: Optional[str] = None


def _resolve_chat(provider: Optional[str], model: Optional[str], system_prompt: str, session_id: Optional[str]):
    if not EMERGENT_LLM_KEY:
        raise HTTPException(status_code=500, detail="EMERGENT_LLM_KEY not configured in sidecar env.")
    prov = (provider or DEFAULT_PROVIDER).lower()
    mdl  = model or DEFAULT_MODEL
    sid  = session_id or f"ipaas-{uuid.uuid4()}"
    return LlmChat(api_key=EMERGENT_LLM_KEY, session_id=sid, system_message=system_prompt).with_model(prov, mdl), prov, mdl


def _current_trace_id() -> Optional[str]:
    ctx = trace.get_current_span().get_span_context()
    if not ctx or not ctx.is_valid:
        return None
    return format(ctx.trace_id, "032x")


@app.get("/health")
def health():
    return {"status": "ok",
            "default_provider": DEFAULT_PROVIDER,
            "default_model": DEFAULT_MODEL,
            "key_configured": bool(EMERGENT_LLM_KEY),
            "otlp_endpoint": OTLP_ENDPOINT}


@app.post("/enrich", response_model=EnrichResponse)
async def enrich(req: EnrichRequest, request: Request):
    # Stitch into the Java caller's trace via W3C traceparent header.
    parent_ctx = extract(dict(request.headers))
    chat, prov, mdl = _resolve_chat(req.provider, req.model, req.system_prompt, req.session_id)
    prompt = f"{req.instruction}\n\nINPUT JSON:\n{json.dumps(req.payload)}\n\nReturn ONLY the enriched JSON object — no prose, no markdown fence."
    with tracer.start_as_current_span("llm.enrich", context=parent_ctx, kind=SpanKind.CLIENT) as span:
        span.set_attribute("ai.provider", prov)
        span.set_attribute("ai.model", mdl)
        span.set_attribute("ai.input_bytes", len(prompt))
        try:
            raw = await chat.send_message(UserMessage(text=prompt))
            body = _extract_json(raw)
            span.set_attribute("ai.output_keys", ",".join(list(body.keys())[:8]))
            return EnrichResponse(enriched=body, provider=prov, model=mdl, trace_id=_current_trace_id())
        except HTTPException:
            raise
        except Exception as e:
            span.record_exception(e)
            log.exception("LLM enrich failed")
            raise HTTPException(status_code=502, detail=f"LLM enrich error: {e}")


@app.post("/decide", response_model=DecideResponse)
async def decide(req: DecideRequest, request: Request):
    parent_ctx = extract(dict(request.headers))
    chat, prov, mdl = _resolve_chat(req.provider, req.model, req.system_prompt, req.session_id)
    prompt = (f"Pick exactly ONE option from this list: {req.options}\n"
              f"Message JSON:\n{json.dumps(req.payload)}\n"
              "Return ONLY a JSON object: {\"decision\": \"<chosen>\", \"confidence\": 0..1, \"rationale\": \"...\"}.")
    with tracer.start_as_current_span("llm.decide", context=parent_ctx, kind=SpanKind.CLIENT) as span:
        span.set_attribute("ai.provider", prov)
        span.set_attribute("ai.model", mdl)
        span.set_attribute("ai.options", ",".join(req.options))
        try:
            raw = await chat.send_message(UserMessage(text=prompt))
            body = _extract_json(raw)
            decision = body.get("decision")
            if decision not in req.options:
                decision = req.options[0]
            span.set_attribute("ai.decision", decision)
            return DecideResponse(decision=decision,
                                  confidence=body.get("confidence"),
                                  rationale=body.get("rationale"),
                                  trace_id=_current_trace_id())
        except HTTPException:
            raise
        except Exception as e:
            span.record_exception(e)
            log.exception("LLM decide failed")
            raise HTTPException(status_code=502, detail=f"LLM decide error: {e}")


def _extract_json(raw: str) -> Dict[str, Any]:
    """LLM outputs may include code fences; pull out the first JSON object found."""
    if not raw:
        return {}
    text = raw.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    start = text.find("{")
    end   = text.rfind("}")
    if start == -1 or end == -1:
        return {"_raw": raw}
    return json.loads(text[start:end + 1])
