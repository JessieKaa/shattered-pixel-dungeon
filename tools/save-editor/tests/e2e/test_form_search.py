"""E2E: bilingual fuzzy search in the Form Schema editor."""

from __future__ import annotations

import os
import sys
from pathlib import Path

from playwright.sync_api import Page, sync_playwright

URL = os.environ.get("SPD_SAVE_EDITOR_URL", "http://127.0.0.1:5010/")
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "sample_save_with_cloth_armor.zip"
SCREENSHOT = "/tmp/form-search-shot.png"


def search(page: Page, query: str) -> None:
    box = page.locator('.form-search-bar input').first
    box.fill(query)
    page.wait_for_timeout(300)


def visible_text(page: Page) -> str:
    rows = page.locator('.field-row').all_text_contents()
    titles = page.locator('.item-card .item-title').all_text_contents()
    headers = page.locator('.el-collapse-item__header').all_text_contents()
    hints = page.locator('.empty-hint').all_text_contents()
    return ' | '.join(rows + titles + headers + hints)


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
        context = browser.new_context(accept_downloads=True, viewport={"width": 375, "height": 900})
        page = context.new_page()

        page.goto(URL, wait_until="networkidle")
        chk(page.title() == "SPD Save Slot Editor", f"title = {page.title()!r}")
        page.locator('input[type="file"]').first.set_input_files(str(FIXTURE))
        page.locator("text=下载 zip").wait_for(timeout=10000)
        chk(True, "upload sample save fixture")

        page.locator(".el-tabs__item", has_text="表单 Schema").click()
        page.wait_for_timeout(800)
        chk(page.locator(".form-search-bar input").count() == 1, "Form Schema search input is visible")
        chk(page.locator(".field-row").count() > 0, "initial form fields are visible")

        search(page, "数量")
        text = visible_text(page)
        chk("数量" in text and "quantity" in text, "Chinese query 数量 matches quantity field")
        chk("诅咒(cursed)" not in text, "Chinese quantity query hides unrelated cursed field")

        search(page, "quantity")
        text = visible_text(page)
        chk("数量" in text and "quantity" in text, "English query quantity matches quantity field")

        search(page, "布甲")
        text = visible_text(page)
        chk("布甲" in text and "ClothArmor" in text, "Chinese item query 布甲 matches ClothArmor")

        search(page, "cloth")
        text = visible_text(page)
        chk("布甲" in text and "ClothArmor" in text, "English class query cloth matches ClothArmor")

        search(page, "魔弹")
        text = visible_text(page)
        chk("魔弹法杖" in text, "Chinese nested item query 魔弹 matches WandOfMagicMissile")

        search(page, "zz-no-match-中文")
        text = visible_text(page)
        chk("未找到匹配" in text, "no-match query shows empty result hint")

        search(page, "")
        chk(page.locator(".field-row").count() > 0, "clearing search restores fields")
        page.locator(".el-tabs__item", has_text="背包").click()
        page.wait_for_timeout(300)
        chk(page.locator(".item-card").count() > 0, "clearing search restores inventory items")

        page.screenshot(path=SCREENSHOT, full_page=True)
        chk(Path(SCREENSHOT).exists(), f"screenshot saved to {SCREENSHOT}")

        context.close()
        browser.close()

    ok = sum(1 for passed, _ in results if passed)
    total = len(results)
    print(f"\n=== {ok}/{total} passed ===")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
