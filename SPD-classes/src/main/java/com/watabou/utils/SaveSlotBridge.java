/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.watabou.utils;

/**
 * Fork extension: platform bridge for save slot export/import.
 *
 * <p>Lives in SPD-classes (not core) so that {@link PlatformSupport} can reference it
 * without inverting module dependencies. The interface only uses Java base types
 * and {@link String} so it has zero dependency on core or platform APIs.</p>
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>Android: ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT via SAF</li>
 *   <li>Desktop: JFileChooser in a worker thread</li>
 *   <li>iOS: not implemented (default returns null from {@link PlatformSupport#saveSlotBridge()})</li>
 * </ul>
 *
 * <p>All callback methods must be invoked on the render thread so that UI updates
 * triggered by the callback are safe.</p>
 */
public interface SaveSlotBridge {

	/**
	 * Open a "create file" picker and stream the named slot's zip content into the
	 * user-selected target.
	 *
	 * @param slotName the source slot name (already validated)
	 * @param cb callback invoked on the render thread with success flag and message
	 */
	void exportSlot(String slotName, ExportCallback cb);

	/**
	 * Open an "open file" picker and stream the user-selected zip into a new slot.
	 * Implementations are responsible for resolving a proposed slot name (from the
	 * picked file's display name or the embedded meta.bundle), checking conflicts,
	 * and committing the import.
	 *
	 * @param cb callback invoked on the render thread with success flag, the
	 *          imported slot name (null on failure), and a message
	 */
	void importSlot(ImportCallback cb);

	/**
	 * @return true if this bridge actually implements export/import; false to hide
	 *         the UI buttons (e.g. iOS stub or missing SAF)
	 */
	boolean available();

	interface ExportCallback {
		void onComplete(boolean ok, String message);
	}

	interface ImportCallback {
		void onComplete(boolean ok, String importedName, String message);
	}
}
