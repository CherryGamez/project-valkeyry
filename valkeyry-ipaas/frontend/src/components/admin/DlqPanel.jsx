import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { api } from "@/lib/ipaasClient";
import { toast } from "sonner";

// Stagger entrance animations: first N rows cascade in 25ms apart; the rest snap in.
const STAGGER_STEP_MS  = 25;
const STAGGER_MAX_ROWS = 8;
const DEFAULT_PEEK_LIMIT = 20;

export default function DlqPanel({ ctx }) {
  const [queue, setQueue] = useState("orders");
  const [limit, setLimit] = useState(DEFAULT_PEEK_LIMIT);
  const [messages, setMessages] = useState([]);
  const [selected, setSelected] = useState({});  // messageId -> { checked, newPayload }
  const [loading, setLoading] = useState(false);

  const peek = async () => {
    if (!ctx.tenantId || !ctx.projectId) return toast.error("Set tenantId + projectId first.");
    setLoading(true);
    try {
      const msgs = await api.browseDlq(ctx.tenantId, ctx.projectId, queue, limit);
      setMessages(msgs);
      setSelected({});
      toast.success(`Fetched ${msgs.length} message(s)`);
    } catch (e) { toast.error(e.message); }
    setLoading(false);
  };

  const bulkRetry = async () => {
    const items = Object.entries(selected)
      .filter(([_, v]) => v.checked)
      .map(([messageId, v]) => ({ messageId, newPayload: v.newPayload || null }));
    if (!items.length) return toast.error("Select at least one message.");
    try {
      const result = await api.bulkRetry(ctx.tenantId, ctx.projectId, queue, items);
      toast.success(`Retry: ${result.succeeded} ok / ${result.failed} failed`);
      setMessages(m => m.filter(x => !items.find(i => i.messageId === x.messageId)));
    } catch (e) { toast.error(e.message); }
  };

  const toggle = (id, patch) =>
    setSelected(s => ({ ...s, [id]: { ...(s[id] || {}), ...patch } }));

  const checkedCount = Object.values(selected).filter(v => v.checked).length;

  return (
    <Card className="bg-card border-soft" data-testid="dlq-card">
      <CardHeader>
        <CardTitle className="text-xl">Dead-Letter Queue Inspector</CardTitle>
        <p className="text-sm text-muted">Peek (non-destructive) and bulk-retry with optional payload substitution.</p>
      </CardHeader>
      <CardContent>
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col">
            <Label className="text-xs text-muted">Queue name</Label>
            <Input
              data-testid="dlq-queue-input"
              className="w-[260px] bg-card-strong border-soft mono text-sm"
              value={queue} onChange={(e) => setQueue(e.target.value.trim())} />
          </div>
          <div className="flex flex-col">
            <Label className="text-xs text-muted">Limit</Label>
            <Input
              data-testid="dlq-limit-input"
              type="number"
              className="w-[100px] bg-card-strong border-soft mono text-sm"
              value={limit} onChange={(e) => setLimit(Number(e.target.value) || DEFAULT_PEEK_LIMIT)} />
          </div>
          <Button
            data-testid="dlq-peek-btn"
            className="bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] font-semibold"
            onClick={peek} disabled={loading}>
            {loading ? "Peeking…" : "Peek DLQ"}
          </Button>
          <Button
            data-testid="dlq-bulk-retry-btn"
            variant="outline"
            className="border-soft hover:border-[var(--accent-amber)] hover:text-[var(--accent-amber)]"
            onClick={bulkRetry}
            disabled={!checkedCount}>
            Bulk retry ({checkedCount})
          </Button>
        </div>

        <div className="mt-6 space-y-2 max-h-[600px] overflow-y-auto scrollbar-thin">
          {messages.length === 0 ? (
            <div className="text-sm text-muted py-12 text-center mono" data-testid="dlq-empty">
              // no messages browsed yet
            </div>
          ) : messages.map((m, i) => (
            <DlqMessageRow key={m.messageId} msg={m} index={i}
                           selection={selected[m.messageId]} onToggle={toggle} />
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Single DLQ row — checkbox + payload preview + optional payload-substitution textarea.
 * Extracted from {@link DlqPanel} so the parent's cyclomatic complexity stays bounded.
 */
function DlqMessageRow({ msg, index, selection, onToggle }) {
  const checked = !!selection?.checked;
  return (
    <div
      className="border border-soft rounded-md p-4 bg-card-strong fade-up"
      style={{ animationDelay: `${Math.min(index, STAGGER_MAX_ROWS) * STAGGER_STEP_MS}ms` }}
      data-testid={`dlq-message-${msg.messageId}`}>
      <div className="flex items-start gap-3">
        <Checkbox
          data-testid={`dlq-msg-check-${msg.messageId}`}
          checked={checked}
          onCheckedChange={(c) => onToggle(msg.messageId, { checked: !!c })}
          className="mt-1 border-soft data-[state=checked]:bg-[var(--accent-cyan)] data-[state=checked]:text-[#0F172A]"
        />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="mono text-xs text-accent">id·{msg.messageId}</span>
            <span className="mono text-[10px] text-muted">
              x-retry-count={msg.headers?.["x-retry-count"] ?? "0"}
            </span>
          </div>
          <pre className="mt-2 text-xs mono text-slate-200 bg-[#0F172A] border border-soft rounded p-3 overflow-x-auto whitespace-pre-wrap break-all">
{msg.payload}
          </pre>
          {checked && (
            <div className="mt-2">
              <Label className="text-xs text-muted">Edit payload (optional)</Label>
              <Textarea
                data-testid={`dlq-msg-payload-edit-${msg.messageId}`}
                className="bg-[#0F172A] border-soft mono text-xs"
                rows={3}
                placeholder="leave blank to retry original"
                value={selection?.newPayload || ""}
                onChange={(e) => onToggle(msg.messageId, { newPayload: e.target.value })}
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
