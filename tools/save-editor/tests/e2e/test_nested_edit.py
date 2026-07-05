"""E2E: editing a field inside a bag (nested list) must NOT collapse the
container or lose input focus.

Background: vuedraggable@4 used to remount item slots on every :list change,
which destroyed the inner el-collapse state and cleared activeNested →
container collapsed + input lost focus on every keystroke. After switching
to vue-draggable-plus (v-for with stable :key), patching no longer remounts.

Run:
    cd tools/save-editor/frontend && npm run build
    cd .. && SPD_SAVE_EDITOR_URL=http://127.0.0.1:5012/ PORT=5012 python app.py
    SPD_SAVE_EDITOR_URL=http://127.0.0.1:5012/ pytest tools/save-editor/tests/e2e/test_nested_edit.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

URL = os.environ.get("SPD_SAVE_EDITOR_URL", "http://127.0.0.1:5010/")
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "sample_save_with_bag_items.zip"


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
        page.locator("text=保存并下载 zip").wait_for(timeout=10000)
        chk(True, "upload sample_save_with_bag_items.zip")

        # Form Schema -> 背包 tab
        page.locator(".el-tabs__item", has_text="表单 Schema").click()
        page.wait_for_timeout(400)
        page.locator(".el-tabs__item", has_text="背包").click()
        page.wait_for_timeout(500)

        pouch = page.locator(".item-card", has_text="绒布袋").first
        pouch.scroll_into_view_if_needed()
        page.wait_for_timeout(300)

        # expand the bag's nested inventory collapse
        pouch.locator(".el-collapse-item__header", has_text="容器内物品").first.click()
        page.wait_for_timeout(500)

        pouch_collapse = pouch.locator(".el-collapse-item").first
        before = "is-active" in (pouch_collapse.get_attribute("class") or "")
        chk(before, "pouch inventory collapse expanded before edit")

        # find potion's quantity field inside the bag
        potion_card = pouch.locator(".el-collapse-item__content .item-card").first
        chk(potion_card.is_visible(), "potion card visible inside bag")

        inputs = potion_card.locator("input.el-input__inner")
        target = None
        for i in range(inputs.count()):
            v = inputs.nth(i).input_value()
            ctx = inputs.nth(i).evaluate(
                "el => el.closest('.field-row') && el.closest('.field-row').innerText"
            )
            if v.isdigit() and "数量" in (ctx or ""):
                target = inputs.nth(i)
                chk(True, f"found potion quantity field (value={v!r})")
                break
        if target is None:
            chk(False, "potion quantity field not found")
            context.close()
            browser.close()
            return 1

        old_val = target.input_value()
        target.click()
        page.wait_for_timeout(100)
        target.fill("77")
        page.wait_for_timeout(800)

        # THE core assertion: collapse must still be expanded
        after = "is-active" in (pouch_collapse.get_attribute("class") or "")
        chk(after, f"pouch collapse still expanded after edit (class kept is-active)")
        chk(before and after, ">>> collapse NOT collapsed by edit (regression fixed)")

        # focus preserved
        focused = page.evaluate(
            "document.activeElement && (document.activeElement.tagName + ' ' + (document.activeElement.className||''))"
        )
        chk("INPUT" in focused, f"focus stays on input after edit (got: {focused[:40]})")

        # value updated
        try:
            new_val = target.input_value()
            chk(new_val == "77", f"potion quantity updated to 77 (got {new_val!r})")
        except Exception:
            chk(False, "target input detached after edit")

        # dirty indicator shown after edit
        dirty_visible = page.locator("text=未保存改动").first.is_visible(timeout=2000)
        chk(dirty_visible, "ActionBar shows 未保存改动 after edit")

        # second edit in the SAME open container — must still not collapse
        target.click()
        page.wait_for_timeout(100)
        target.fill("88")
        page.wait_for_timeout(800)
        still_active = "is-active" in (pouch_collapse.get_attribute("class") or "")
        chk(still_active, "collapse still expanded after 2nd consecutive edit")

        # save-and-download clears dirty
        with page.expect_download(timeout=15000) as dl_info:
            page.locator("text=保存并下载 zip").click()
        dl = dl_info.value
        out = "/tmp/nested-edit-out.zip"
        dl.save_as(out)
        chk(Path(out).exists() and Path(out).stat().st_size > 0, f"download zip ({Path(out).stat().st_size} bytes)")
        page.wait_for_timeout(500)
        dirty_after = page.locator("text=未保存改动").first.is_visible(timeout=1000)
        chk(not dirty_after, "dirty indicator cleared after save-and-download")

        page.screenshot(path="/tmp/nested-edit-shot.png", full_page=True)

        context.close()
        browser.close()

    ok = sum(1 for c, _ in results if c)
    total = len(results)
    print(f"\n=== {ok}/{total} passed ===")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
