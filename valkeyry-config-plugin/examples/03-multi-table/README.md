# Example 3 — Multi-table manifest

One `valkeyry-config.yaml` declares **three** tables in a single push, mixing
file-based and inline schemas. The plugin walks them sequentially and reports
a per-table summary at the end.

```bash
mvn io.valkeyry:valkeyry-config-maven-plugin:push
```

Expected log:

```
· customers: declaring schema… → submitted=2 inserted=2 duplicates=0
· feature_flags: declaring schema… → submitted=2 inserted=2 duplicates=0
· webhooks_registry: declaring schema… → submitted=1 inserted=1 duplicates=0
```
