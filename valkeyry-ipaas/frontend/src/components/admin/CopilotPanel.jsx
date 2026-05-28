import { useCallback, useEffect, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/ipaasClient";
import { toast } from "sonner";

const COPILOT_SID_KEY = "valkeyry.copilot.sid";

/** Lazy-initialised session id — generated once per browser, persisted to localStorage. */
function loadOrCreateSessionId() {
  const cached = localStorage.getItem(COPILOT_SID_KEY);
  if (cached) return cached;
  const id = "sess-" + Math.random().toString(36).slice(2, 10);
  localStorage.setItem(COPILOT_SID_KEY, id);
  return id;
}

let presetSeq = 0;
const stamp = (text) => ({ id: `preset-${++presetSeq}`, text });

/**
 * Operator Copilot — a thin Spring AI ChatClient UI.
 *
 * Backed by:
 *   POST /api/v1/copilot/stream     (text/event-stream)
 *   POST /api/v1/copilot/session/{id}/reset
 *   GET  /api/v1/copilot/tools
 *   GET  /api/v1/copilot/providers
 */
export default function CopilotPanel({ ctx }) {
  // useState(initFn) runs `loadOrCreateSessionId` exactly once. useMemo is incorrect here —
  // React may re-run useMemo factories at will (e.g. concurrent mode), generating
  // multiple session-ids and writing the last one to localStorage every render.
  const [sessionId] = useState(loadOrCreateSessionId);

  const [providers, setProviders] = useState({ available: [], default: "ollama" });
  const [provider, setProvider]   = useState("");
  const [tools, setTools]         = useState([]);
  const [showTools, setShowTools] = useState(false);
  const [messages, setMessages]   = useState(/** @type {{id:string,role:string,content:string}[]} */ ([]));
  const [draft, setDraft]         = useState("");
  const [busy, setBusy]           = useState(false);
  const streamBufRef = useRef("");
  const msgSeqRef    = useRef(0);
  const nextMsgId    = () => `m-${++msgSeqRef.current}-${Date.now().toString(36)}`;

  const bootstrap = useCallback(async () => {
    try {
      const [p, t, h] = await Promise.all([
        api.copilotProviders(),
        api.copilotTools(),
        api.copilotHistory(sessionId),
      ]);
      setProviders(p);
      setProvider(p.default || (p.available?.[0] ?? ""));
      setTools(t);
      // Server returns history without ids; stamp our own stable identifiers.
      setMessages((h || []).map((m) => ({ ...m, id: nextMsgId() })));
    } catch (e) {
      toast.error("Copilot not reachable: " + e.message);
    }
  }, [sessionId]);

  useEffect(() => { bootstrap(); }, [bootstrap]);

  const send = useCallback(async () => {
    const text = draft.trim();
    if (!text) return;
    const userMsg      = { id: nextMsgId(), role: "user",      content: text };
    const assistantMsg = { id: nextMsgId(), role: "assistant", content: "" };
    setMessages((m) => [...m, userMsg, assistantMsg]);
    setDraft("");
    setBusy(true);
    streamBufRef.current = "";

    await api.copilotStream(
      { sessionId, message: text, provider },
      {
        onChunk: (chunk) => {
          streamBufRef.current += chunk;
          const partial = streamBufRef.current;
          setMessages((m) => m.map((row) =>
            row.id === assistantMsg.id ? { ...row, content: partial } : row));
        },
        onDone:  () => setBusy(false),
        onError: (e) => {
          toast.error("Copilot error: " + (e?.message || e));
          setBusy(false);
        },
      }
    );
  }, [draft, provider, sessionId]);

  const reset = useCallback(async () => {
    try {
      await api.copilotReset(sessionId);
      setMessages([]);
      toast.success("Conversation cleared.");
    } catch (e) {
      toast.error("Reset failed: " + e.message);
    }
  }, [sessionId]);

  const tenant  = ctx.tenantId  || "acme-corp";
  const project = ctx.projectId || "payments-prod";
  const presets = [
    stamp(`List queues for ${tenant}/${project}.`),
    stamp(`Give me a metrics snapshot for ${tenant}/${project}.`),
    stamp(`Peek the first 5 DLQ messages of ingress.events in ${tenant}/${project}.`),
    stamp(`Which LLM providers are currently wired?`),
  ];

  return (
    <Card className="bg-card border-soft" data-testid="copilot-panel">
      <CardHeader className="flex flex-row items-center justify-between gap-4">
        <CardTitle className="text-white">
          Operator Copilot
          <span className="ml-3 text-xs mono text-slate-400">session: {sessionId}</span>
        </CardTitle>
        <div className="flex items-center gap-3">
          <Label className="text-slate-300 text-sm">Provider</Label>
          <Select value={provider} onValueChange={setProvider}>
            <SelectTrigger className="w-[160px] bg-[#0F172A] border-soft text-white" data-testid="copilot-provider">
              <SelectValue placeholder="provider" />
            </SelectTrigger>
            <SelectContent>
              {(providers.available || []).map((p) => (
                <SelectItem key={p} value={p}>{p}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button variant="outline" onClick={() => setShowTools((s) => !s)} data-testid="copilot-tools-toggle">
            {showTools ? "Hide tools" : `Tools (${tools.length})`}
          </Button>
          <Button variant="outline" onClick={reset} data-testid="copilot-reset">Reset</Button>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {showTools && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2 p-3 bg-[#0F172A] border border-soft rounded-md">
            {tools.map((t) => <ToolRow key={t.name} tool={t} />)}
          </div>
        )}

        <div className="bg-[#0F172A] border border-soft rounded-md p-4 h-[440px] overflow-y-auto"
             data-testid="copilot-thread">
          {messages.length === 0 && (
            <div className="text-slate-500 text-sm">
              Ask the Copilot anything about your platform. Try one of the prompts below.
            </div>
          )}
          {messages.map((m, idx) => (
            <MessageBubble key={m.id} msg={m}
                           pulseDots={busy && idx === messages.length - 1 && !m.content && m.role === "assistant"} />
          ))}
        </div>

        <div className="flex flex-wrap gap-2">
          {presets.map((p) => (
            <button key={p.id}
                    onClick={() => setDraft(p.text)}
                    className="text-xs px-3 py-1 rounded-full border border-soft text-slate-300 hover:text-white hover:border-[var(--accent-cyan)] transition">
              {p.text}
            </button>
          ))}
        </div>

        <div className="flex items-end gap-3">
          <div className="flex-1">
            <Label className="text-slate-300 text-sm">Message</Label>
            <Input
              data-testid="copilot-input"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder="e.g. 'how many DLQ messages are stuck on acme-corp/payments-prod/orders?'"
              className="bg-[#0F172A] border-soft text-white"
              disabled={busy}
            />
          </div>
          <Button onClick={send} disabled={busy || !draft.trim()} data-testid="copilot-send">
            {busy ? "Thinking…" : "Send"}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

/** Single chat bubble — keeps the parent simple + reactive list keys stable. */
function MessageBubble({ msg, pulseDots }) {
  const right = msg.role === "user";
  return (
    <div className={`mb-4 ${right ? "text-right" : ""}`}>
      <div className={`inline-block px-3 py-2 rounded-md max-w-[85%] whitespace-pre-wrap ${
        right
          ? "bg-[var(--accent-cyan)] text-[#0F172A]"
          : "bg-[#1E293B] border border-soft text-slate-100"
      }`}>
        {msg.content || (pulseDots ? "…" : "")}
      </div>
    </div>
  );
}

/** Single tool-catalog entry (read/write category badge + name + description). */
function ToolRow({ tool }) {
  const writeTone = tool.category === "write";
  return (
    <div className="text-sm">
      <span className={`mono ${writeTone ? "text-amber-400" : "text-emerald-300"}`}>
        {writeTone ? "WRITE" : "READ"}
      </span>{" "}
      <span className="mono text-white">{tool.name}</span>
      <span className="text-slate-400"> — {tool.description}</span>
    </div>
  );
}
