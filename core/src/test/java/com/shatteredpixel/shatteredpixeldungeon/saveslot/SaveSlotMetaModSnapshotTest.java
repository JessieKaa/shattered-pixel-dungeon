package com.shatteredpixel.shatteredpixeldungeon.saveslot;

import com.watabou.utils.Bundle;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M9a: unit coverage for the save-slot × modding mismatch detection. Two concerns:
 *
 * <p>(1) {@link SaveSlotService#computeMissingMods} is the pure diff between a slot's saved
 * active-mod snapshot and the running process's active set. It must return empty for legacy
 * slots (no snapshot → no warning), empty when nothing is missing, and exactly the missing ids
 * otherwise — without flagging mods that are merely "extra" in the current set.
 *
 * <p>(2) The Bundle round-trip contract for {@code enabled_mods}: a stored String[] survives
 * put → contains → getStringArray unchanged, and a bundle without the key reports
 * {@code contains=false} so the read path falls back to an empty set without throwing or logging
 * an error. No headless Gdx fixture is needed: {@link Bundle} is pure org.json.
 */
public class SaveSlotMetaModSnapshotTest {

	// ---- computeMissingMods: pure diff semantics ----------------------------------

	@Test
	public void nullSnapshot_yieldsEmpty() {
		assertTrue(SaveSlotService.computeMissingMods(null, setOf("a")).isEmpty());
	}

	@Test
	public void emptySnapshot_yieldsEmpty_legacySlotNoWarning() {
		// Legacy slots saved before M9a have no enabled_mods key → empty snapshot. Must NOT warn.
		assertTrue(SaveSlotService.computeMissingMods(Collections.emptySet(), setOf("a")).isEmpty());
	}

	@Test
	public void equalSets_yieldsEmpty() {
		assertTrue(SaveSlotService.computeMissingMods(setOf("a", "b"), setOf("b", "a")).isEmpty());
	}

	@Test
	public void missingMods_returnedExactly() {
		Set<String> missing = SaveSlotService.computeMissingMods(
				new LinkedHashSet<>(Arrays.asList("a", "b", "c")),
				setOf("a"));
		assertEquals(setOf("b", "c"), missing);
	}

	@Test
	public void extraCurrentMods_notFlagged() {
		// Mods present now but absent from the save don't degrade the save — must not warn.
		Set<String> missing = SaveSlotService.computeMissingMods(
				setOf("a"),
				new LinkedHashSet<>(Arrays.asList("a", "b", "c")));
		assertTrue(missing.isEmpty());
	}

	@Test
	public void nullCurrent_treatedAsEmpty_noNpe() {
		// Defensive: production always passes a non-null active set, but the pure helper must not
		// NPE if it ever receives null — treat as "nothing enabled", everything snapshot-side missing.
		Set<String> missing = SaveSlotService.computeMissingMods(setOf("a", "b"), null);
		assertEquals(setOf("a", "b"), missing);
	}

	@Test
	public void duplicateSnapshotIds_dedupedAndOrderPreserved() {
		LinkedHashSet<String> snapshot = new LinkedHashSet<>(Arrays.asList("c", "a", "c", "b", "a"));
		Set<String> missing = SaveSlotService.computeMissingMods(snapshot, setOf("b"));
		// LinkedHashSet preserves insertion order of the snapshot (first occurrence), dedupes repeats.
		assertEquals(Arrays.asList("c", "a"), new java.util.ArrayList<>(missing));
	}

	// ---- Bundle round-trip contract for enabled_mods ------------------------------

	@Test
	public void enabledModsBundle_roundTrip() {
		Bundle b = new Bundle();
		b.put(SaveSlotService.KEY_ENABLED_MODS, new String[]{"mod_a", "mod_b"});

		assertTrue(b.contains(SaveSlotService.KEY_ENABLED_MODS));
		String[] read = b.getStringArray(SaveSlotService.KEY_ENABLED_MODS);
		assertNotNull(read);
		assertEquals(new HashSet<>(Arrays.asList("mod_a", "mod_b")), new HashSet<>(Arrays.asList(read)));
	}

	@Test
	public void emptyEnabledModsArray_roundTripsEmpty() {
		Bundle b = new Bundle();
		b.put(SaveSlotService.KEY_ENABLED_MODS, new String[0]);

		assertTrue("empty array still written so new slots always carry the key",
				b.contains(SaveSlotService.KEY_ENABLED_MODS));
		String[] read = b.getStringArray(SaveSlotService.KEY_ENABLED_MODS);
		assertNotNull(read);
		assertEquals(0, read.length);
	}

	@Test
	public void legacyBundleWithoutKey_containsFalse_noThrow() {
		// A slot saved before M9a has no enabled_mods key. The read path guards on contains() to
		// avoid getStringArray's JSONException path (which logs via Game.reportException).
		Bundle legacy = new Bundle();
		legacy.put("name", "oldsave");
		assertFalse(legacy.contains(SaveSlotService.KEY_ENABLED_MODS));
	}

	// ---- pending warning staging (no stale leak on clean follow-up load) ---------

	@Test
	public void stageLoadWarning_setsWhenMissing() {
		SaveSlotService.pendingLoadWarning = null;
		SaveSlotService.stageLoadWarning(new LinkedHashSet<>(Arrays.asList("mod_x", "mod_y")));
		assertNotNull(SaveSlotService.pendingLoadWarning);
		assertTrue(SaveSlotService.pendingLoadWarning.contains("mod_x"));
	}

	@Test
	public void stageLoadWarning_clearsOnCleanLoad_noStaleLeak() {
		// Regression: a prior load staged a warning, the player then loads a clean slot before the
		// GameScene hook drains it. The clean load must clear the stale warning, not inherit it.
		SaveSlotService.pendingLoadWarning = "stale from a previous slot";
		SaveSlotService.stageLoadWarning(Collections.emptySet());
		assertNull(SaveSlotService.pendingLoadWarning);
	}

	@Test
	public void stageLoadWarning_clearsOnNullMissing() {
		SaveSlotService.pendingLoadWarning = "stale";
		SaveSlotService.stageLoadWarning(null);
		assertNull(SaveSlotService.pendingLoadWarning);
	}

	@Test
	public void onGameSceneReady_drainsPendingWarning() {
		SaveSlotService.pendingLoadWarning = "drain me";
		SaveSlotService.onGameSceneReady();
		assertNull("hook must drain the pending warning after firing", SaveSlotService.pendingLoadWarning);
	}

	private static Set<String> setOf(String... ids) {
		return new HashSet<>(Arrays.asList(ids));
	}
}
