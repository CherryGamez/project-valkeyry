import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { toast } from "sonner";
import { LogIn, ShieldCheck, KeyRound, Terminal } from "lucide-react";
import { oidcEnabled } from "@/auth/oidcConfig";
import { loginLocal, loginWithToken } from "@/auth/session";

/** Enterprise split-layout sign-in. Three paths: local dev user, OIDC SSO, JWT paste. */
export default function LoginPage({ onSignedIn, auth }) {
  const [mode, setMode] = useState("local");                 // 'local' | 'jwt'
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [tokenInput, setTokenInput] = useState("");
  const [busy, setBusy] = useState(false);

  const handleLocal = async () => {
    setBusy(true);
    try {
      const s = loginLocal(username, password);
      toast.success(`Welcome, ${s.identity}`);
      onSignedIn(s);
    } catch (e) { toast.error(e.message); }
    setBusy(false);
  };

  const handlePaste = () => {
    try {
      const s = loginWithToken(tokenInput);
      toast.success("Token accepted");
      onSignedIn(s);
    } catch (e) { toast.error(e.message); }
  };

  return (
    <div
      data-testid="login-page"
      className="min-h-screen grain flex flex-col lg:flex-row">

      {/* LEFT: brand panel */}
      <aside className="bg-card-strong lg:w-2/5 px-10 lg:px-16 py-12 flex flex-col justify-between border-b lg:border-b-0 lg:border-r border-soft">
        <div className="flex items-center gap-3 fade-up">
          <div className="w-2 h-2 rounded-full bg-[var(--accent-green)] pulse-dot" />
          <span className="mono text-sm text-accent">valkeyry</span>
        </div>

        <div className="space-y-6 fade-up fade-up-1">
          <h1 className="text-5xl lg:text-6xl font-bold tracking-tight text-white leading-[1.05]">
            Reactive iPaaS<br />
            <span className="text-accent">control plane.</span>
          </h1>
          <p className="text-lg text-slate-300 max-w-md">
            Multi-tenant message routing, dynamic topologies, AI-driven enrichment
            and end-to-end distributed traces &mdash; in one console.
          </p>

          <div className="grid grid-cols-2 gap-4 pt-4 max-w-md">
            <Feature title="Live SSE metrics"    sub="@ 1-second tick" />
            <Feature title="DLQ peek + retry"    sub="non-destructive" />
            <Feature title="1·N / N·1 / N·N"     sub="declarative YAML" />
            <Feature title="OIDC RBAC"            sub="per-workspace" />
          </div>
        </div>

        <div className="mono text-xs text-muted pt-12 fade-up fade-up-3">
          valkeyry/ipaas v1.0.0 &nbsp;·&nbsp; OSS &nbsp;·&nbsp; Apache 2.0
        </div>
      </aside>

      {/* RIGHT: sign-in card */}
      <section className="flex-1 px-8 lg:px-20 py-12 flex items-center fade-up fade-up-2">
        <div className="w-full max-w-md">
          <div className="mb-8">
            <h2 className="text-3xl lg:text-4xl font-semibold text-white tracking-tight">
              Sign in
            </h2>
            <p className="text-sm text-slate-300 mt-2">
              {oidcEnabled
                ? "Continue with your organization's SSO, or use a dev account below."
                : "Use a local dev account or paste a bearer JWT for non-OIDC backends."}
            </p>
          </div>

          {oidcEnabled && (
            <div className="mb-6">
              <Button
                data-testid="login-oidc-btn"
                className="w-full bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold gap-2 h-11"
                onClick={() => auth?.signinRedirect()}
                disabled={auth?.isLoading}>
                <ShieldCheck className="w-4 h-4" />
                {auth?.isLoading ? "Redirecting…" : "Continue with SSO"}
              </Button>
              <div className="flex items-center gap-3 my-6">
                <div className="flex-1 h-px bg-[var(--border-soft)]" />
                <span className="mono text-[10px] text-muted uppercase tracking-widest">or</span>
                <div className="flex-1 h-px bg-[var(--border-soft)]" />
              </div>
            </div>
          )}

          {/* Mode tabs */}
          <div className="inline-flex gap-1 p-1 rounded-md border border-soft bg-card mb-6">
            <ModeTab id="local" active={mode} setActive={setMode} icon={<LogIn className="w-3.5 h-3.5" />} label="Dev account" />
            <ModeTab id="jwt"   active={mode} setActive={setMode} icon={<KeyRound className="w-3.5 h-3.5" />} label="JWT paste" />
          </div>

          {mode === "local" && (
            <form
              className="space-y-4"
              onSubmit={(e) => { e.preventDefault(); handleLocal(); }}
              data-testid="login-local-form">
              <div>
                <Label className="text-xs text-slate-300">Username</Label>
                <Input
                  data-testid="login-username-input"
                  className="bg-card-strong border-soft text-white"
                  placeholder="admin"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoFocus
                />
              </div>
              <div>
                <Label className="text-xs text-slate-300">Password</Label>
                <Input
                  data-testid="login-password-input"
                  type="password"
                  className="bg-card-strong border-soft text-white"
                  placeholder="••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>
              <Button
                data-testid="login-submit-btn"
                type="submit"
                className="w-full bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold h-11"
                disabled={busy}>
                {busy ? "Signing in…" : "Sign in"}
              </Button>

              <div className="mt-6 rounded-md border border-soft bg-card-strong p-4 text-xs">
                <div className="mono text-[10px] uppercase tracking-widest text-muted mb-2 flex items-center gap-1">
                  <Terminal className="w-3 h-3" /> Local dev credentials
                </div>
                <div className="grid grid-cols-2 gap-y-1 mono text-slate-200">
                  <div>admin</div>      <div className="text-muted">/ admin</div>
                  <div>operator</div>   <div className="text-muted">/ operator</div>
                </div>
                <p className="text-[11px] text-muted mt-3 leading-relaxed">
                  Dev sign-in requires the Spring app to run with{" "}
                  <span className="mono text-accent">ipaas.security.allow-anonymous=true</span>{" "}
                  (default in <span className="mono">local-dev/docker-compose.yaml</span>).
                </p>
              </div>
            </form>
          )}

          {mode === "jwt" && (
            <div className="space-y-4" data-testid="login-jwt-form">
              <div>
                <Label className="text-xs text-slate-300">Bearer JWT</Label>
                <Textarea
                  data-testid="login-jwt-input"
                  rows={4}
                  className="bg-card-strong border-soft mono text-xs text-white"
                  placeholder="eyJhbGciOi..."
                  value={tokenInput}
                  onChange={(e) => setTokenInput(e.target.value)}
                />
              </div>
              <Button
                data-testid="login-jwt-submit"
                className="w-full bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold h-11"
                onClick={handlePaste}>
                Use this token
              </Button>
              <p className="text-[11px] text-muted leading-relaxed">
                Useful when the Spring app is running with the real OIDC resource server
                and you have a JWT minted out-of-band (CLI, Postman, CI).
              </p>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function Feature({ title, sub }) {
  return (
    <div className="border-l-2 border-[var(--accent-cyan)] pl-3">
      <div className="text-sm font-medium text-white">{title}</div>
      <div className="mono text-[11px] text-muted">{sub}</div>
    </div>
  );
}

function ModeTab({ id, active, setActive, icon, label }) {
  const isActive = id === active;
  return (
    <button
      type="button"
      data-testid={`login-mode-${id}`}
      onClick={() => setActive(id)}
      className={`px-3 py-1.5 rounded text-sm font-medium gap-2 inline-flex items-center transition-colors
        ${isActive
          ? "bg-[var(--accent-cyan)] text-[#0F172A]"
          : "text-slate-300 hover:text-white"}`}>
      {icon} {label}
    </button>
  );
}
