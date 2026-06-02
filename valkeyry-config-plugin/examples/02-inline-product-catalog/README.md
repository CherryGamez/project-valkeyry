# Example 2 — Inline product catalog (`schemaInline:`)

The full Draft 2020-12 schema is declared **directly in YAML** under
`schemaInline:` — no separate `.schema.json` file needed. This is the
preferred style for small/medium config tables because everything lives in
one place and Git diffs are easy to read.

Every UI widget the GUI's *Visual Builder* can emit is expressible here:

| Widget        | YAML shape                                                |
|---------------|-----------------------------------------------------------|
| Text          | `{ type: string }`                                        |
| Email         | `{ type: string, format: email }`                         |
| Date          | `{ type: string, format: date }`                          |
| Number        | `{ type: number, minimum: 0, maximum: 999.99 }`           |
| Integer       | `{ type: integer, minimum: 0, maximum: 1000 }`            |
| Checkbox      | `{ type: boolean, default: false }`                       |
| Dropdown      | `{ type: string, enum: ["a", "b", "c"] }`                 |
| Multi-choice  | `{ type: array, items: { type: string, enum: [...] } }`   |
| Regex         | `{ type: string, pattern: "^[A-Z]{3}-\\d{4}$" }`          |

```bash
mvn io.valkeyry:valkeyry-config-maven-plugin:push
# or
./gradlew valkeyryConfigPush
```
