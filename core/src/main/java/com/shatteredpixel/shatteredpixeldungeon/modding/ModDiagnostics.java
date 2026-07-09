package com.shatteredpixel.shatteredpixeldungeon.modding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-mod diagnostic snapshot for the M16c mod diagnostics UI. Immutable-ish in public API:
 * the model is mutable only through the controlled set/add/increment/clear methods
 * so the scanner/engine can build it up, then consumers see a stable snapshot.
 */
public final class ModDiagnostics {

	public enum Status {
		DISCOVERED, LOADED, FAILED, DISABLED, WARNINGS
	}

	private static final int MAX_MESSAGES = 10;

	private Status status = Status.DISCOVERED;
	private String declaredId;
	private final List<String> errors = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();
	private final Map<String, Integer> counts = new LinkedHashMap<>();
	private long lastUpdated = System.currentTimeMillis();

	public Status status() {
		return status;
	}

	public String declaredId() {
		return declaredId;
	}

	public List<String> errors() {
		return Collections.unmodifiableList(errors);
	}

	public List<String> warnings() {
		return Collections.unmodifiableList(warnings);
	}

	public Map<String, Integer> counts() {
		return Collections.unmodifiableMap(counts);
	}

	public long lastUpdated() {
		return lastUpdated;
	}

	public ModDiagnostics setStatus(Status status) {
		this.status = status;
		touch();
		return this;
	}

	public ModDiagnostics setDeclaredId(String declaredId) {
		this.declaredId = declaredId;
		touch();
		return this;
	}

	public ModDiagnostics addError(String message) {
		addLimited(errors, message);
		if (status != Status.FAILED) {
			status = Status.FAILED;
		}
		touch();
		return this;
	}

	public ModDiagnostics addWarning(String message) {
		addLimited(warnings, message);
		if (status != Status.FAILED && status != Status.DISABLED) {
			status = Status.WARNINGS;
		}
		touch();
		return this;
	}

	public ModDiagnostics setCount(String type, int value) {
		counts.put(type, value);
		touch();
		return this;
	}

	public ModDiagnostics incrementCount(String type) {
		counts.merge(type, 1, Integer::sum);
		touch();
		return this;
	}

	public ModDiagnostics clear() {
		status = Status.DISCOVERED;
		declaredId = null;
		errors.clear();
		warnings.clear();
		counts.clear();
		touch();
		return this;
	}

	private void addLimited(List<String> list, String message) {
		if (list.size() >= MAX_MESSAGES) {
			list.remove(MAX_MESSAGES - 1);
			list.add("(" + txtMoreOmitted() + ")");
		} else {
			list.add(message);
		}
	}

	private void touch() {
		lastUpdated = System.currentTimeMillis();
	}

	private static String txtMoreOmitted() {
		return langZh() ? "更多错误/警告已省略" : "more errors/warnings omitted";
	}

	private static boolean langZh() {
		return java.util.Locale.getDefault().getLanguage().equalsIgnoreCase("zh");
	}
}
