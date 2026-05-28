import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/ipaasClient";
import { toast } from "sonner";

const ID_PATTERN = "[A-Za-z0-9_-]{1,200}";
const ID_TITLE   = "1-200 chars, alphanumeric with - or _";

let rowSeq = 0;
const nextRowId = () => `row-${++rowSeq}-${Date.now().toString(36)}`;
const blankRow = (overrides = {}) => ({ uid: nextRowId(), tenantId: "", projectId: "", destination: "", ...overrides });

export default function MultiPublishPanel() {
  const [targets, setTargets] = useState([ blankRow({ destination: "ingress.events" }) ]);
  const [payload, setPayload] = useState('{"orderId":42,"total":1999}');
  const [results, setResults] = useState([]);
  const [busy, setBusy] = useState(false);

  const upd       = (uid, k, v) => setTargets(t => t.map(row => row.uid === uid ? { ...row, [k]: v } : row));
  const addRow    = () => setTargets(t => [...t, blankRow()]);
  const removeRow = (uid) => setTargets(t => t.filter(row => row.uid !== uid));

  const submit = async () => {
    const cleaned = targets
        .filter(t => t.tenantId && t.projectId && t.destination)
        .map(({ uid, ...rest }) => rest);
    if (!cleaned.length) return toast.error("Add at least one valid target.");
    setBusy(true);
    try {
      const out = await api.multiPublish(cleaned, payload);
      // Server returns rows in the order we submitted them; stamp a stable key for React.
      setResults(out.map((r, i) => ({ ...r, _uid: `${r.tenantId}/${r.projectId}/${r.destination}/${i}` })));
      const ok = out.filter(o => o.status === "ACCEPTED").length;
      toast.success(`Published to ${ok}/${out.length} target(s).`);
    } catch (e) { toast.error(e.message); }
    setBusy(false);
  };

  return (
    <Card className="bg-card border-soft" data-testid="multi-publish-card">
      <CardHeader>
        <CardTitle className="text-xl">Multi-Tenant Publish</CardTitle>
        <p className="text-sm text-muted">
          Fan-out a single payload across multiple{" "}
          <span className="mono">(tenantId, projectId, destination)</span> tuples.
          Each target is RBAC-checked independently; denied targets are reported, not blocking the rest.
        </p>
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          {targets.map((t, idx) => (
            <TargetRow key={t.uid} row={t} showLabels={idx === 0}
                       onChange={upd} onRemove={() => removeRow(t.uid)} />
          ))}
          <Button data-testid="mp-add-row" variant="outline"
                  className="border-soft hover:border-[var(--accent-cyan)] hover:text-[var(--accent-cyan)]"
                  onClick={addRow}>+ Add target</Button>
        </div>

        <div className="mt-6">
          <Label className="text-xs text-muted">Payload (raw string / JSON)</Label>
          <Textarea data-testid="mp-payload-input" rows={4}
                    className="bg-card-strong border-soft mono text-sm"
                    value={payload} onChange={(e) => setPayload(e.target.value)} />
        </div>

        <div className="mt-6">
          <Button data-testid="mp-submit-btn"
                  className="bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold"
                  disabled={busy} onClick={submit}>
            {busy ? "Publishing…" : "Fan-out publish"}
          </Button>
        </div>

        {results.length > 0 && (
          <div className="mt-6 space-y-2" data-testid="mp-results">
            <div className="text-sm text-muted mono">// per-target outcomes</div>
            {results.map((r) => <ResultRow key={r._uid} r={r} />)}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** One editable target row. Extracted to drop the parent's cyclomatic complexity. */
function TargetRow({ row, showLabels, onChange, onRemove }) {
  return (
    <div className="grid grid-cols-12 gap-2 items-end" data-testid={`mp-target-row-${row.uid}`}>
      <div className="col-span-4">
        {showLabels && <Label className="text-xs text-muted">tenantId</Label>}
        <Input data-testid={`mp-tenant-${row.uid}`}
               className="bg-card-strong border-soft mono text-sm"
               placeholder="e.g. acme-corp" maxLength={200} pattern={ID_PATTERN} title={ID_TITLE}
               value={row.tenantId}
               onChange={(e) => onChange(row.uid, "tenantId", e.target.value.trim())} />
      </div>
      <div className="col-span-4">
        {showLabels && <Label className="text-xs text-muted">projectId</Label>}
        <Input data-testid={`mp-project-${row.uid}`}
               className="bg-card-strong border-soft mono text-sm"
               placeholder="e.g. payments-prod" maxLength={200} pattern={ID_PATTERN} title={ID_TITLE}
               value={row.projectId}
               onChange={(e) => onChange(row.uid, "projectId", e.target.value.trim())} />
      </div>
      <div className="col-span-3">
        {showLabels && <Label className="text-xs text-muted">destination</Label>}
        <Input data-testid={`mp-dest-${row.uid}`}
               className="bg-card-strong border-soft mono text-sm"
               placeholder="e.g. ingress.events"
               value={row.destination}
               onChange={(e) => onChange(row.uid, "destination", e.target.value)} />
      </div>
      <div className="col-span-1">
        <Button data-testid={`mp-remove-${row.uid}`} variant="outline"
                className="border-soft hover:border-[var(--accent-rose)] hover:text-[var(--accent-rose)] w-full"
                onClick={onRemove}>−</Button>
      </div>
    </div>
  );
}

/** One per-target result row. Extracted for clarity + stable keys. */
function ResultRow({ r }) {
  const tone =
    r.status === "ACCEPTED" ? "border-[var(--accent-green)]/40 bg-[var(--accent-green)]/5"
    : r.status === "DENIED" ? "border-[var(--accent-amber)]/40 bg-[var(--accent-amber)]/5"
    :                         "border-[var(--accent-rose)]/40 bg-[var(--accent-rose)]/5";
  return (
    <div className={`border rounded-md p-3 text-sm flex flex-wrap items-center gap-x-4 gap-y-1 ${tone}`}
         data-testid={`mp-result-${r._uid}`}>
      <span className="mono text-xs">{r.status}</span>
      <span className="mono text-xs text-muted">{r.tenantId?.slice(0, 8)}…</span>
      <span className="mono text-xs text-muted">{r.projectId?.slice(0, 8)}…</span>
      <span className="mono text-xs text-accent">{r.destination}</span>
      {r.brokerType        && <span className="mono text-[10px] text-muted">{r.brokerType}</span>}
      {r.provisioningMode  && <span className="mono text-[10px] text-muted">{r.provisioningMode}</span>}
      {r.error             && <span className="mono text-[10px] text-[var(--accent-rose)]">{r.error}</span>}
    </div>
  );
}
