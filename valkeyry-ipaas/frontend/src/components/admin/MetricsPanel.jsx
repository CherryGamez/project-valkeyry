import { useCallback, useEffect, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/ipaasClient";
import { toast } from "sonner";

// ISO-8601 timestamp slice that yields the `HH:MM:SS` portion (e.g. `12:34:56`).
const ISO_TIME_START = 11;
const ISO_TIME_END   = 19;
// Stagger entrance animations: first N rows fade in 25ms apart; the rest snap in.
const STAGGER_STEP_MS = 40;
const STAGGER_MAX_ROWS = 12;

export default function MetricsPanel({ ctx }) {
  const [metrics, setMetrics] = useState([]);
  const [streaming, setStreaming] = useState(false);
  const [lastTick, setLastTick] = useState(null);
  const esRef = useRef(null);

  // useCallback gives us a stable identity so the cleanup-only effect below can depend on it.
  const stop = useCallback(() => {
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    setStreaming(false);
  }, []);

  const start = () => {
    if (!ctx.tenantId || !ctx.projectId) {
      toast.error("Set tenantId + projectId first.");
      return;
    }
    stop();
    const es = api.openMetricsStream(ctx.tenantId, ctx.projectId);
    es.addEventListener("metric", (e) => {
      try {
        const data = JSON.parse(e.data);
        setMetrics(data);
        setLastTick(new Date().toISOString());
      } catch (err) {
        // Keep last known good metrics on parse error; log so devs can spot a malformed frame.
        console.warn("MetricsPanel: dropping malformed SSE frame", err);
      }
    });
    es.onerror = () => {
      toast.error("SSE stream closed by server.");
      stop();
    };
    esRef.current = es;
    setStreaming(true);
  };

  // Unmount cleanup — close any open EventSource so we don't leak a connection.
  useEffect(() => () => stop(), [stop]);

  return (
    <Card className="bg-card border-soft" data-testid="metrics-card">
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle className="text-xl">Live Queue Metrics</CardTitle>
          <p className="text-sm text-muted mt-1">
            Server-Sent Events @ 1s tick.{" "}
            {lastTick && <span className="mono text-accent text-xs">last·{lastTick.slice(ISO_TIME_START, ISO_TIME_END)}Z</span>}
          </p>
        </div>
        <div className="flex gap-2">
          {streaming ? (
            <Button
              data-testid="metrics-stop-btn"
              variant="outline"
              className="border-soft hover:border-[var(--accent-rose)] hover:text-[var(--accent-rose)]"
              onClick={stop}>Stop</Button>
          ) : (
            <Button
              data-testid="metrics-start-btn"
              className="bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold"
              onClick={start}>Start stream</Button>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {metrics.length === 0 ? (
          <div className="text-sm text-muted py-8 text-center mono" data-testid="metrics-empty">
            {streaming ? "// waiting for first tick…" : "// stream idle"}
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {metrics.map((m, i) => (
              <div
                key={m.destination + i}
                className="bg-card-strong border border-soft rounded-md p-4 fade-up"
                style={{ animationDelay: `${Math.min(i, STAGGER_MAX_ROWS) * STAGGER_STEP_MS}ms` }}
                data-testid={`metric-card-${m.destination}`}>
                <div className="flex items-center justify-between">
                  <span className="mono text-sm text-accent">{m.destination}</span>
                  <span className="text-[10px] uppercase tracking-wider text-muted">{m.brokerType}</span>
                </div>
                <div className="mt-4 flex items-end gap-6">
                  <Metric label="msgs"     value={m.messageCount}  highlight />
                  <Metric label="consumers" value={m.consumerCount} />
                  <Metric label="dlq"       value={m.dlqSize} />
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Metric({ label, value, highlight }) {
  return (
    <div>
      <div className={`mono text-2xl font-semibold ${highlight ? "text-accent" : ""}`}>
        {value < 0 ? "—" : value}
      </div>
      <div className="text-[10px] uppercase tracking-wider text-muted">{label}</div>
    </div>
  );
}
