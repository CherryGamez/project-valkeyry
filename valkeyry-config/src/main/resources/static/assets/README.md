# Tailwind CSS build for the Java-served HTMX admin pages

The production Java backend serves `login.html`, `admin.html`, and `tools.html` as
static resources straight out of `src/main/resources/static/`. We don't want
Tailwind's runtime CDN (it warns *"cdn.tailwindcss.com should not be used in
production"* and ships ~350 KB of JIT compiler) so we pre-build a tiny CSS bundle.

## Rebuild after editing the three HTML files

```bash
# One-time
mkdir -p /tmp/tw && cd /tmp/tw
npm i -D tailwindcss@3.4.17

# Whenever you touch login.html, admin.html, or tools.html:
cd /tmp/tw
npx tailwindcss \
    -c /app/valkeyry-config/src/main/resources/static/assets/tailwind.config.cjs \
    -i /app/valkeyry-config/src/main/resources/static/assets/tailwind.src.css \
    -o /app/valkeyry-config/src/main/resources/static/assets/tailwind.css \
    --minify
```

The generated `tailwind.css` (~12 KB minified) is committed alongside the source so the
Maven build does **not** need a Node toolchain. Add this command to your IDE's file watcher
or pre-commit hook if you frequently change the static pages.

## Why we don't bundle it through Maven

- The pages are HTMX-shells, not a React/Vite app — adding a node-build to the Maven
  reactor would impose Node on every CI runner for a 12 KB file.
- `index.html` keeps its own bespoke design tokens and doesn't share the build (it
  uses CDN Tailwind today; migrating it is tracked as a future task).

## Files

- `tailwind.config.cjs` — scans the three HTML files for utility classes.
- `tailwind.src.css` — the three `@tailwind base/components/utilities` directives.
- `tailwind.css`       — the generated, minified bundle (committed).
