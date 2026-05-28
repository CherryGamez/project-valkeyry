import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/ipaasClient";
import { toast } from "sonner";

const initial = {
  topologyName: "sample-fanout",
  topologyType: "ONE_TO_MANY",
  processingMode: "QUEUE",
  brokerType: "RABBITMQ",
  sources: "ingress.events",
  targets: "downstream.analytics, downstream.audit, downstream.ml-features",
};

export default function TopologyBuilder({ ctx }) {
  const [form, setForm] = useState(initial);
  const [submitting, setSubmitting] = useState(false);

  const upd = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const buildManifestYaml = () => {
    const sources = form.sources.split(",").map(s => s.trim()).filter(Boolean);
    const targets = form.targets.split(",").map(s => s.trim()).filter(Boolean)
      .map(t => `  - destination: ${t}`).join("\n");
    return [
      `topologyName: ${form.topologyName}`,
      `topologyType: ${form.topologyType}`,
      `processingMode: ${form.processingMode}`,
      `brokerType: ${form.brokerType}`,
      `sources:`,
      ...sources.map(s => `  - ${s}`),
      `targets:`,
      targets || `  - destination: change-me`,
    ].join("\n");
  };

  const save = async (deployAfter = false) => {
    if (!ctx.tenantId || !ctx.projectId) return toast.error("Set tenantId + projectId first.");
    setSubmitting(true);
    try {
      const yaml = buildManifestYaml();
      await api.upsertTopology(ctx.tenantId, ctx.projectId, {
        topologyName: form.topologyName,
        topologyType: form.topologyType,
        manifestYaml: yaml,
        enabled: true,
      });
      toast.success("Topology saved");
      if (deployAfter) {
        await api.deployTopology(ctx.tenantId, ctx.projectId, form.topologyName);
        toast.success(`Topology '${form.topologyName}' deployed`);
      }
    } catch (e) { toast.error(e.message); }
    setSubmitting(false);
  };

  return (
    <Card className="bg-card border-soft" data-testid="topology-card">
      <CardHeader>
        <CardTitle className="text-xl">Declarative Topology Builder</CardTitle>
        <p className="text-sm text-muted">
          Compose <span className="mono">ONE_TO_MANY</span> / <span className="mono">MANY_TO_ONE</span> /{" "}
          <span className="mono">MANY_TO_MANY</span> routing pipelines from the UI.
        </p>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="space-y-4">
            <div>
              <Label className="text-xs text-muted">Topology name</Label>
              <Input
                data-testid="topology-name-input"
                className="bg-card-strong border-soft mono"
                value={form.topologyName}
                onChange={e => upd("topologyName", e.target.value)} />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label className="text-xs text-muted">Type</Label>
                <Select value={form.topologyType} onValueChange={v => upd("topologyType", v)}>
                  <SelectTrigger data-testid="topology-type-select" className="bg-card-strong border-soft">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent className="bg-card-strong border-soft">
                    <SelectItem value="ONE_TO_MANY">ONE_TO_MANY (fan-out)</SelectItem>
                    <SelectItem value="MANY_TO_ONE">MANY_TO_ONE (consolidate)</SelectItem>
                    <SelectItem value="MANY_TO_MANY">MANY_TO_MANY (matrix)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label className="text-xs text-muted">Mode</Label>
                <Select value={form.processingMode} onValueChange={v => upd("processingMode", v)}>
                  <SelectTrigger data-testid="topology-mode-select" className="bg-card-strong border-soft">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent className="bg-card-strong border-soft">
                    <SelectItem value="QUEUE">QUEUE</SelectItem>
                    <SelectItem value="STREAMING">STREAMING</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div>
              <Label className="text-xs text-muted">Broker</Label>
              <Select value={form.brokerType} onValueChange={v => upd("brokerType", v)}>
                <SelectTrigger data-testid="topology-broker-select" className="bg-card-strong border-soft">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent className="bg-card-strong border-soft">
                  <SelectItem value="RABBITMQ">RabbitMQ</SelectItem>
                  <SelectItem value="KAFKA">Kafka</SelectItem>
                  <SelectItem value="ACTIVEMQ">ActiveMQ</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div>
              <Label className="text-xs text-muted">Sources (comma-separated)</Label>
              <Input
                data-testid="topology-sources-input"
                className="bg-card-strong border-soft mono text-sm"
                value={form.sources}
                onChange={e => upd("sources", e.target.value)} />
            </div>
            <div>
              <Label className="text-xs text-muted">Targets (comma-separated destinations)</Label>
              <Textarea
                data-testid="topology-targets-input"
                rows={3}
                className="bg-card-strong border-soft mono text-sm"
                value={form.targets}
                onChange={e => upd("targets", e.target.value)} />
            </div>

            <div className="flex gap-2 pt-2">
              <Button
                data-testid="topology-save-btn"
                variant="outline"
                className="border-soft hover:border-[var(--accent-cyan)] hover:text-[var(--accent-cyan)]"
                disabled={submitting}
                onClick={() => save(false)}>Save</Button>
              <Button
                data-testid="topology-deploy-btn"
                className="bg-[var(--accent-cyan)] text-[#0F172A] hover:bg-[#34D399] glow-cyan font-semibold"
                disabled={submitting}
                onClick={() => save(true)}>Save &amp; Deploy</Button>
            </div>
          </div>

          <div>
            <Label className="text-xs text-muted">Manifest preview</Label>
            <pre
              data-testid="topology-yaml-preview"
              className="mono text-xs bg-[#0F172A] border border-soft rounded-md p-4 max-h-[480px] overflow-auto whitespace-pre">
{buildManifestYaml()}
            </pre>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
