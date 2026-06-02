# Example 1 — Flat feature flags (file-based schema)

Classic plugin shape: the schema lives in a separate `.schema.json` file and
the data lives in one-per-record JSON files under `data/`.

```
01-flat-feature-flags/
├── valkeyry-config.yaml
├── schemas/
│   └── feature_flags.schema.json
└── data/
    ├── checkout.v2.json
    ├── dark.mode.json
    └── export.csv.json
```

Push it:

```bash
cp -r 01-flat-feature-flags/* /path/to/your-project/
cd /path/to/your-project
mvn io.valkeyry:valkeyry-config-maven-plugin:push
# or
./gradlew valkeyryConfigPush
```

Expected log:

```
· feature_flags: declaring schema… → registered configVersion=1
  → submitted=3 inserted=3 duplicates=0
```
