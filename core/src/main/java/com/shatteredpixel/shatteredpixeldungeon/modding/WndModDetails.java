package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.ui.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * M16c mod diagnostics detail window. Shows one mod's (or one scan-problem's) full diagnostic
 * snapshot: id/name/version/origin, status, recorded errors and warnings, and per-type content
 * counts. Opened from {@link WndModManager} either via the per-row "details" button (real mod) or
 * the "scan problems" area (orphan diagnostic — a directory that was skipped during scan).
 *
 * <p>Two constructors:
 * <ul>
 *   <li>{@link #WndModDetails(ModManifest)} — real mod; reads its {@link ModDiagnostics} from
 *       {@link ModRegistry} (may be null before the engine initializes, in which case only
 *       name/id/version/origin render).</li>
 *   <li>{@link #WndModDetails(String, ModDiagnostics)} — scan-problem orphan; the key is
 *       {@code scan:<origin>:<dirname>} and diagnostics carry the skip reason.</li>
 * </ul>
 *
 * <p>Labels are hardcoded ZH/EN via {@link #TXT_ZH}/{@link #TXT_EN} + {@link #txt(String)}, mirroring
 * {@link WndModManager}: the fork's {@code Messages.get} is unreliable across the modding package.
 */
public class WndModDetails extends Window {

	private static final int WIDTH = 130;
	private static final int HEIGHT = 140;
	private static final int MARGIN = 2;

	// Count-type keys (set by LuaEngine.registerSucceeded) → localized label.
	private static final String[] COUNT_TYPES = {
			"items", "mobs", "allies", "heroes", "spells", "npcs", "shops",
			"buffs", "talents", "painters", "traps", "levels"
	};

	static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh")
			|| (com.shatteredpixel.shatteredpixeldungeon.messages.Messages.lang() != null
			    && com.shatteredpixel.shatteredpixeldungeon.messages.Messages.lang().code() != null
			    && com.shatteredpixel.shatteredpixeldungeon.messages.Messages.lang().code()
						.toLowerCase(Locale.ENGLISH).startsWith("zh"));

	private static final Map<String, String> TXT_ZH = new HashMap<>();
	private static final Map<String, String> TXT_EN = new HashMap<>();
	static {
		TXT_ZH.put("status_label", "状态");
		TXT_ZH.put("id_label", "ID");
		TXT_ZH.put("declared_id_label", "声明 ID");
		TXT_ZH.put("origin_builtin", "内建");
		TXT_ZH.put("origin_external", "外部");
		TXT_ZH.put("errors_label", "错误");
		TXT_ZH.put("warnings_label", "警告");
		TXT_ZH.put("counts_label", "已注册内容");
		TXT_ZH.put("none", "无");
		TXT_EN.put("status_label", "Status");
		TXT_EN.put("id_label", "ID");
		TXT_EN.put("declared_id_label", "Declared ID");
		TXT_EN.put("origin_builtin", "built-in");
		TXT_EN.put("origin_external", "external");
		TXT_EN.put("errors_label", "Errors");
		TXT_EN.put("warnings_label", "Warnings");
		TXT_EN.put("counts_label", "Registered");
		TXT_EN.put("none", "none");
	}

	private static String txt(String key) {
		Map<String, String> m = LANG_ZH ? TXT_ZH : TXT_EN;
		String s = m.get(key);
		return s != null ? s : key;
	}

	private static String originText(ModManifest mod) {
		boolean external = mod != null && mod.origin == ModManifest.Origin.EXTERNAL;
		return txt(external ? "origin_external" : "origin_builtin");
	}

	public WndModDetails(ModManifest mod) {
		this(mod.name + " v" + mod.version + " [" + originText(mod) + "]",
				mod.id,
				ModRegistry.getDiagnostics(mod.id),
				false);
	}

	public WndModDetails(String orphanKey, ModDiagnostics diag) {
		this(displayNameForOrphan(orphanKey, diag),
				idForOrphan(orphanKey),
				diag,
				true);
	}

	private WndModDetails(String title, String id, ModDiagnostics diag, boolean orphan) {
		super();
		resize(WIDTH, HEIGHT);

		ScrollPane pane = new ScrollPane(new Component());
		add(pane);
		pane.setRect(0, 0, WIDTH, HEIGHT);
		Component content = pane.content();
		float contentWidth = WIDTH - MARGIN * 2;
		float y = 0;

		y = addLine(content, title, 9, 0xFFFFCC, contentWidth, MARGIN, y);

		if (id != null) {
			y = addLine(content, txt("id_label") + ": " + id, 7, 0xCCCCCC, contentWidth, MARGIN, y);
		}
		if (diag != null && diag.declaredId() != null && !diag.declaredId().isEmpty() && !diag.declaredId().equals(id)) {
			y = addLine(content, txt("declared_id_label") + ": " + diag.declaredId(), 7, 0xCCCCCC, contentWidth, MARGIN, y);
		}
		if (diag != null) {
			y = addLine(content, txt("status_label") + ": " + WndModManager.statusTagFor(diag), 7, statusColor(diag), contentWidth, MARGIN, y);
			y = addSection(content, txt("errors_label"), diag.errors(), 0xFF8888, contentWidth, MARGIN, y);
			y = addSection(content, txt("warnings_label"), diag.warnings(), 0xFFCC88, contentWidth, MARGIN, y);
			y = addCounts(content, diag, contentWidth, MARGIN, y);
		} else if (orphan) {
			// Orphan with no diagnostics object (defensive) — nothing further to show.
			y = addLine(content, txt("none"), 7, 0xCCCCCC, contentWidth, MARGIN, y);
		}

		content.setRect(0, 0, WIDTH, Math.max(y, HEIGHT));
	}

	private static int statusColor(ModDiagnostics diag) {
		if (diag == null) return 0xCCCCCC;
		switch (diag.status()) {
			case FAILED:   return 0xFF6666;
			case WARNINGS: return 0xFFCC44;
			case LOADED:   return 0x88FF88;
			case DISABLED: return 0x999999;
			default:       return 0xCCCCCC;
		}
	}

	private float addLine(Component parent, String text, int size, int color, float width, float x, float y) {
		RenderedTextBlock line = PixelScene.renderTextBlock(text, size);
		line.maxWidth((int) width);
		line.hardlight(color);
		line.setPos(x, y);
		parent.add(line);
		return line.bottom() + 1;
	}

	private float addSection(Component parent, String label, java.util.List<String> items, int color,
	                         float width, float x, float y) {
		y = addLine(parent, label + ":", 7, 0xCCCCCC, width, x, y);
		if (items == null || items.isEmpty()) {
			return addLine(parent, "  " + txt("none"), 7, 0x888888, width, x, y);
		}
		for (String s : items) {
			y = addLine(parent, "  " + s, 6, color, width, x, y);
		}
		return y;
	}

	private float addCounts(Component parent, ModDiagnostics diag, float width, float x, float y) {
		Map<String, Integer> counts = diag.counts();
		Map<String, Integer> present = new LinkedHashMap<>();
		for (String type : COUNT_TYPES) {
			Integer v = counts.get(type);
			if (v != null && v > 0) present.put(type, v);
		}
		if (present.isEmpty()) return y;
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, Integer> e : present.entrySet()) {
			if (!first) sb.append(", ");
			sb.append(e.getKey()).append(":").append(e.getValue());
			first = false;
		}
		y = addLine(parent, txt("counts_label") + ":", 7, 0xCCCCCC, width, x, y);
		return addLine(parent, "  " + sb, 6, 0xCCCCFF, width, x, y);
	}

	private static String displayNameForOrphan(String orphanKey, ModDiagnostics diag) {
		if (diag != null && diag.declaredId() != null && !diag.declaredId().isEmpty()) {
			return diag.declaredId();
		}
		int c1 = orphanKey.indexOf(':');
		int c2 = orphanKey.indexOf(':', c1 + 1);
		return c2 >= 0 ? orphanKey.substring(c2 + 1) : orphanKey;
	}

	private static String idForOrphan(String orphanKey) {
		return orphanKey;
	}
}
