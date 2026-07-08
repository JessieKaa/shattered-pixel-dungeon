package com.shatteredpixel.shatteredpixeldungeon.modding;

/**
 * Platform seam for the M12b "import mod from zip" flow (see
 * docs/PLAN-m12b-desktop-zip-import.md).
 *
 * <p>{@code core} is platform-agnostic and cannot touch AWT/SAF, so the actual file picker lives
 * in a platform module: the {@code desktop} module registers a {@code DesktopModImporter} (Swing
 * {@code JFileChooser}, mirroring {@code DesktopSaveSlotBridge}), and a future M12c android module
 * will register a SAF-backed impl. Anything that needs to launch a picker (currently
 * {@link WndModManager}) talks only to {@link #get()}; when no platform impl is registered the
 * import button is hidden (graceful degradation, never throws).
 *
 * <p>The shared, platform-neutral zip handling lives in {@link ModInstaller}; this interface is
 * only the picker entry point plus the registration holder.
 */
public interface ModImporter {

	/**
	 * Ask the platform to pick a {@code .zip} and install it via {@link ModInstaller}. Must return
	 * promptly (the picker runs off the render thread); the result is delivered to {@code cb}
	 * exactly once, on the libgdx render thread.
	 */
	void pickZip(ImportCallback cb);

	/**
	 * Delivered exactly once by {@link #pickZip}, on the libgdx render thread.
	 * {@code code} is one of the {@link ModInstaller} error constants
	 * ({@code io_error} / {@code invalid_zip} / {@code too_many_entries} / {@code zip_too_large}
	 * / {@code bad_manifest} / {@code version_mismatch} / {@code already_exists}).
	 */
	interface ImportCallback {
		void onSuccess(String modId);
		void onError(String code);
		void onCancel();
	}

	/**
	 * Mutable holder for the registered platform picker. Nested because interface fields are
	 * implicitly {@code public static final}; this class field is the one place a settable
	 * reference can live. {@code volatile}: written once at platform-launcher startup, read on the
	 * render thread — volatile makes the visibility hand-off explicit even though there is no
	 * sustained concurrent mutation.
	 */
	final class Holder {
		static volatile ModImporter impl;
		private Holder() {}
	}

	/** Platform launcher hook (desktop / android-M12c). Idempotent; a later call overwrites. */
	static void setPlatformImpl(ModImporter impl) { Holder.impl = impl; }

	/** @return the registered platform picker, or null if none (import UI must then hide itself). */
	static ModImporter get() { return Holder.impl; }
}
