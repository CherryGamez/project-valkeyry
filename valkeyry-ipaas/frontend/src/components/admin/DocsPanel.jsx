import { useEffect, useMemo, useState } from "react";
import { marked } from "marked";
import DOMPurify from "dompurify";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";

const PRODUCTS = [
  { id: "ipaas",  label: "valkeyry-ipaas"  },
  { id: "config", label: "valkeyry-config" },
];

const DOCS = [
  { id: "PRD",      label: "PRD"      },
  { id: "TRD",      label: "TRD"      },
  { id: "App_Flow", label: "App Flow" },
];

marked.setOptions({ gfm: true, breaks: false, mangle: false, headerIds: true });

export default function DocsPanel() {
  const [product, setProduct] = useState("ipaas");
  const [doc, setDoc]         = useState("PRD");
  const [raw, setRaw]         = useState("");
  const [error, setError]     = useState(null);
  const [loading, setLoading] = useState(true);

  const url = `${process.env.PUBLIC_URL || ""}/docs/${product}/${doc}.md`;

  useEffect(() => {
    const ctrl = new AbortController();
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const r = await fetch(url, { signal: ctrl.signal });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const md = await r.text();
        setRaw(md);
        setLoading(false);
      } catch (e) {
        if (e.name === "AbortError") return;
        setError(e.message || String(e));
        setLoading(false);
      }
    })();
    return () => ctrl.abort();
  }, [url]);

  const html = useMemo(() => {
    if (!raw) return "";
    return DOMPurify.sanitize(marked.parse(raw), { ADD_ATTR: ["target", "rel"] });
  }, [raw]);

  return (
    <Card className="bg-card border-soft" data-testid="docs-card">
      <CardContent className="p-6">
        <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
          <div>
            <h2 className="text-xl font-semibold text-white">Documentation</h2>
            <p className="text-sm text-slate-400 mt-1">
              Product (PRD), technical (TRD) and application-flow specs for both services in the Valkeyry ecosystem.
            </p>
          </div>
          <div className="flex items-center gap-2" data-testid="docs-product-switch">
            {PRODUCTS.map((p) => (
              <Button
                key={p.id}
                variant={p.id === product ? "default" : "secondary"}
                size="sm"
                data-testid={`docs-prod-${p.id}`}
                onClick={() => setProduct(p.id)}
                className={p.id === product
                  ? "bg-[var(--accent-cyan,#10B981)] text-[#0F172A] hover:bg-[var(--accent-cyan,#10B981)]/90"
                  : ""}
              >
                {p.label}
              </Button>
            ))}
          </div>
        </div>

        <div
          className="flex items-center gap-1 border-b border-soft pb-2 mb-5"
          data-testid="docs-doc-switch">
          {DOCS.map((d) => (
            <button
              key={d.id}
              data-testid={`docs-doc-${d.id.toLowerCase()}`}
              onClick={() => setDoc(d.id)}
              className={`px-3 py-1.5 rounded-md text-sm transition-colors mono ${
                d.id === doc
                  ? "bg-[var(--accent-cyan,#10B981)] text-[#0F172A] font-semibold"
                  : "text-slate-300 hover:bg-slate-800"
              }`}>
              {d.label}
            </button>
          ))}
          <div className="ml-auto flex items-center gap-3">
            <span className="text-xs text-slate-500 mono" data-testid="docs-source-path">
              valkeyry-{product}/docs/{doc}.md
            </span>
            <a
              href={url}
              download={`${product}-${doc}.md`}
              data-testid="docs-download-link"
              className="text-xs text-slate-300 hover:text-white border border-soft rounded-md px-2 py-1 mono">
              ↓ Markdown
            </a>
          </div>
        </div>

        {loading && (
          <div className="text-sm text-slate-400 italic" data-testid="docs-loading">
            Loading documentation…
          </div>
        )}
        {error && !loading && (
          <div className="text-sm text-rose-400" data-testid="docs-error">
            Failed to load <span className="mono">{url}</span> — {error}
          </div>
        )}
        {!loading && !error && (
          <article
            data-testid="docs-content"
            className="docs-prose"
            dangerouslySetInnerHTML={{ __html: html }}
          />
        )}
      </CardContent>
    </Card>
  );
}
