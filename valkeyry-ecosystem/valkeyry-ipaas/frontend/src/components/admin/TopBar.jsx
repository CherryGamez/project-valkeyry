import { useEffect, useState } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { setContext } from "@/lib/ipaasClient";
import { LogOut, ShieldCheck } from "lucide-react";

const SAMPLE_TENANT  = "acme-corp";
const SAMPLE_PROJECT = "payments-prod";

export default function TopBar({ ctx, setCtx, session, onSignOut }) {
  const [tenantId, setTenantId]   = useState(ctx.tenantId || "");
  const [projectId, setProjectId] = useState(ctx.projectId || "");

  // setCtx is a stable React setState fn; included in deps to satisfy exhaustive-deps and
  // remain correct if the parent ever swaps it for a memoized callback that closes over
  // additional state. `setContext` is a module-level helper (stable).
  useEffect(() => {
    setContext({ tenantId, projectId });
    setCtx({ tenantId, projectId });
  }, [tenantId, projectId, setCtx]);

  const loadSample = () => {
    setTenantId(SAMPLE_TENANT);
    setProjectId(SAMPLE_PROJECT);
  };

  const roleBadgeColor = session?.role === "ADMIN"
      ? "text-[var(--accent-green)] border-[var(--accent-green)]/40 bg-[var(--accent-green)]/10"
      : "text-[var(--accent-cyan)] border-[var(--accent-cyan)]/40 bg-[var(--accent-cyan)]/10";

  return (
    <div className="border-b border-soft bg-card-strong/70 backdrop-blur sticky top-0 z-20">
      <div className="px-6 lg:px-12 py-3 flex flex-wrap items-end gap-4">
        <div className="flex items-center gap-2 pr-4 border-r border-soft">
          <div className="w-2 h-2 rounded-full bg-[var(--accent-green)] pulse-dot" />
          <span className="mono text-sm text-accent">valkeyry</span>
        </div>

        <div className="flex flex-col">
          <Label className="text-xs text-slate-300">tenantId</Label>
          <Input
            data-testid="top-bar-tenant-input"
            className="w-[260px] bg-card border-soft mono text-sm text-white"
            placeholder="e.g. acme-corp"
            maxLength={200}
            pattern="[A-Za-z0-9_-]{1,200}"
            title="1-200 chars, alphanumeric with - or _"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value.trim())}
          />
        </div>
        <div className="flex flex-col">
          <Label className="text-xs text-slate-300">projectId</Label>
          <Input
            data-testid="top-bar-project-input"
            className="w-[260px] bg-card border-soft mono text-sm text-white"
            placeholder="e.g. payments-prod"
            maxLength={200}
            pattern="[A-Za-z0-9_-]{1,200}"
            title="1-200 chars, alphanumeric with - or _"
            value={projectId}
            onChange={(e) => setProjectId(e.target.value.trim())}
          />
        </div>

        <Button
          data-testid="top-bar-load-sample"
          className="bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold"
          onClick={loadSample}>
          Load sample
        </Button>

        <div className="flex-1" />

        {/* Identity badge — always present once logged in */}
        {session && (
          <div
            data-testid="top-bar-identity-badge"
            className={`flex items-center gap-2 px-3 py-2 rounded-md border mono text-xs ${roleBadgeColor}`}>
            <ShieldCheck className="w-3.5 h-3.5" />
            <span>{session.identity}</span>
            {session.role && <span className="text-muted">·</span>}
            {session.role && <span>{session.role}</span>}
            <span className="text-muted">·</span>
            <span className="text-muted">{session.kind}</span>
          </div>
        )}

        <Button
          data-testid="top-bar-signout-btn"
          variant="outline"
          className="border-soft hover:border-[var(--accent-rose)] hover:text-[var(--accent-rose)] gap-2"
          onClick={onSignOut}>
          <LogOut className="w-3.5 h-3.5" /> Sign out
        </Button>
      </div>
    </div>
  );
}
