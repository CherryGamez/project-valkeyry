import { useEffect, useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import TopBar from "@/components/admin/TopBar";
import MetricsPanel from "@/components/admin/MetricsPanel";
import DlqPanel from "@/components/admin/DlqPanel";
import TopologyBuilder from "@/components/admin/TopologyBuilder";
import MultiPublishPanel from "@/components/admin/MultiPublishPanel";
import CopilotPanel from "@/components/admin/CopilotPanel";
import { getContext } from "@/lib/ipaasClient";

export default function AdminConsole({ session, onSignOut }) {
  const [ctx, setCtx] = useState(getContext());
  const [tab, setTab] = useState("metrics");

  useEffect(() => { document.title = "Valkeyry · iPaaS Console"; }, []);

  return (
    <div className="grain min-h-screen flex flex-col" data-testid="admin-console">
      <TopBar ctx={ctx} setCtx={setCtx} session={session} onSignOut={onSignOut} />

      <main className="flex-1 px-6 lg:px-12 py-8 max-w-[1600px] w-full self-start">
        <header className="mb-8 fade-up">
          <h1 className="text-4xl sm:text-5xl lg:text-6xl font-semibold tracking-tight text-white">
            <span className="text-accent mono">~</span>{" "}
            valkeyry<span className="text-slate-500">·</span>console
          </h1>
          <p className="mt-3 text-lg text-slate-300 max-w-3xl">
            Reactive iPaaS control plane &mdash; live SSE metrics, dead-letter inspection,
            declarative topology builder, and cross-tenant fan-out publishing.
          </p>
        </header>

        <Tabs value={tab} onValueChange={setTab} className="fade-up fade-up-1">
          <TabsList
            className="bg-card border border-soft rounded-md p-1 h-auto"
            data-testid="admin-tabs-list">
            <TabsTrigger value="metrics"       data-testid="tab-metrics"
              className="data-[state=active]:bg-[var(--accent-cyan)] data-[state=active]:text-[#0F172A]">Live Metrics</TabsTrigger>
            <TabsTrigger value="dlq"           data-testid="tab-dlq"
              className="data-[state=active]:bg-[var(--accent-cyan)] data-[state=active]:text-[#0F172A]">DLQ Inspector</TabsTrigger>
            <TabsTrigger value="topologies"    data-testid="tab-topologies"
              className="data-[state=active]:bg-[var(--accent-cyan)] data-[state=active]:text-[#0F172A]">Topology Builder</TabsTrigger>
            <TabsTrigger value="multi-publish" data-testid="tab-multi-publish"
              className="data-[state=active]:bg-[var(--accent-cyan)] data-[state=active]:text-[#0F172A]">Multi-Tenant Publish</TabsTrigger>
            <TabsTrigger value="copilot"       data-testid="tab-copilot"
              className="data-[state=active]:bg-[var(--accent-cyan)] data-[state=active]:text-[#0F172A]">Operator Copilot</TabsTrigger>
          </TabsList>

          <div className="mt-6">
            <TabsContent value="metrics"       data-testid="panel-metrics"><MetricsPanel ctx={ctx} /></TabsContent>
            <TabsContent value="dlq"           data-testid="panel-dlq"><DlqPanel ctx={ctx} /></TabsContent>
            <TabsContent value="topologies"    data-testid="panel-topologies"><TopologyBuilder ctx={ctx} /></TabsContent>
            <TabsContent value="multi-publish" data-testid="panel-multi-publish"><MultiPublishPanel /></TabsContent>
            <TabsContent value="copilot"       data-testid="panel-copilot"><CopilotPanel ctx={ctx} /></TabsContent>
          </div>
        </Tabs>

        <footer className="mt-16 pt-6 border-t border-soft text-xs text-slate-400 mono">
          valkeyry / ipaas v1.0.0 &middot; reactive&nbsp;·&nbsp;multi-tenant&nbsp;·&nbsp;OSS
        </footer>
      </main>
    </div>
  );
}
