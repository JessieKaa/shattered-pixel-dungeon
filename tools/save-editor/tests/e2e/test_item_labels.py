"""E2E: item-labels-zh feature.

Verifies that backpack item cards in the Form-Schema tab display the
localized Chinese name (from items_zh.properties) alongside the bare
Java simple class name. Uses Playwright + system Chrome.

Run order:
1. cd tools/save-editor/frontend && npm run build
2. PORT=5010 python ../app.py  (from tools/save-editor/)
3. pytest tools/save-editor/tests/e2e/test_item_labels.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

URL = "http://127.0.0.1:5010/"
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "sample_save_with_cloth_armor.zip"


def main() -> int:
    if not FIXTURE.exists():
        print(f"FAIL: fixture missing: {FIXTURE}")
        return 2

    results: list[tuple[bool, str]] = []

    def chk(cond: bool, label: str) -> None:
        results.append((cond, label))
        print(f"{'OK' if cond else 'XX'}  {label}")

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(accept_downloads=True)
        page = context.new_page()

        page.goto(URL, wait_until="networkidle")
        chk(page.title() == "SPD Save Slot Editor", f"title = {page.title()!r}")
        page.locator('input[type="file"]').first.set_input_files(str(FIXTURE))
        page.locator("text=下载 zip").wait_for(timeout=10000)
        chk(True, "upload sample_save.zip")

        page.locator(".el-tabs__item", has_text="表单 Schema").click()
        page.wait_for_timeout(800)

        form_item_titles = page.locator(".item-card .item-title").all_text_contents()
        form_joined = " | ".join(form_item_titles)
        chk("布甲" in form_joined, f"Form Schema DOM contains 布甲 (got: {form_joined[:240]})")
        chk("ClothArmor" in form_joined, f"Form Schema DOM contains ClothArmor (got: {form_joined[:240]})")

        item_titles = page.locator(".item-card .item-title").all_text_contents()
        joined = " | ".join(item_titles)
        chk("口粮" in joined, f"item title contains 口粮 (got: {joined[:240]})")
        chk("Food" in joined, f"item title contains Food (got: {joined[:240]})")
        chk("水袋" in joined, f"item title contains 水袋 (got: {joined[:240]})")
        chk("布甲" in joined, f"item title contains 布甲 (got: {joined[:240]})")
        chk("ClothArmor" in joined, f"item title contains ClothArmor (got: {joined[:240]})")

        collapse_headers = page.locator(".el-collapse-item__header").all_text_contents()
        collapse_joined = " | ".join(collapse_headers)
        chk(
            "魔弹法杖" in collapse_joined,
            f"NestedObject collapse header contains 魔弹法杖 (got: {collapse_joined[:240]})",
        )

        page.screenshot(path="/tmp/item-labels-shot-form.png", full_page=True)

        context.close()
        browser.close()

    ok = sum(1 for c, _ in results if c)
    total = len(results)
    print(f"\n=== {ok}/{total} passed ===")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
