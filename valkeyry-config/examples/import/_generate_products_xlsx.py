"""Generates /app/valkeyry-config/examples/import/products.xlsx — a small, deterministic
workbook with two sheets so the Tools-tab XLSX converter has to round-trip multiple
`tables[]` entries on `POST /api/v1/tools/convert/xlsx`.

Run with: `python3 examples/import/_generate_products_xlsx.py`
Re-run is idempotent — the file is overwritten in place.
"""
from datetime import date
from pathlib import Path
from openpyxl import Workbook

HERE = Path(__file__).parent
out = HERE / "products.xlsx"

wb = Workbook()

# --- Sheet 1: Products -------------------------------------------------------
ws = wb.active
ws.title = "products"
ws.append(["sku", "name", "category", "priceUsd", "inStock", "launchedOn"])
ws.append(["P-100", "Atlas keyboard",  "peripherals", 129.00, True,  date(2024, 5, 12)])
ws.append(["P-101", "Atlas mouse",     "peripherals",  59.00, True,  date(2024, 5, 12)])
ws.append(["P-200", "Nimbus desk",     "furniture",   449.00, True,  date(2024, 3,  3)])
ws.append(["P-201", "Nimbus chair",    "furniture",   299.00, False, date(2024, 1, 22)])
ws.append(["P-300", "Polaris monitor", "displays",    549.00, True,  date(2024, 7,  9)])
ws.append(["P-301", "Polaris stand",   "displays",     89.00, True,  date(2024, 7,  9)])

# --- Sheet 2: Stock per warehouse -------------------------------------------
ws2 = wb.create_sheet("stock")
ws2.append(["sku", "warehouse", "onHand", "reorderPoint"])
ws2.append(["P-100", "berlin",        420,  50])
ws2.append(["P-100", "san-francisco", 180,  50])
ws2.append(["P-200", "berlin",         32,  20])
ws2.append(["P-201", "san-francisco",   0,  10])
ws2.append(["P-300", "berlin",         71,  25])

wb.save(out)
print(f"wrote {out} ({out.stat().st_size} bytes)")
