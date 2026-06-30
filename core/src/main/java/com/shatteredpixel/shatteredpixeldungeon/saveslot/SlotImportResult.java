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

package com.shatteredpixel.shatteredpixeldungeon.saveslot;

/**
 * Fork extension: immutable result of
 * {@link SaveSlotIO#readSlotFromStream(java.io.InputStream, String)}.
 */
public final class SlotImportResult {

	public final boolean ok;
	public final String name;          // resolved proposed name (null on failure)
	public final SaveSlotMeta meta;    // null on failure
	public final String stagingRelPath;// slot-relative path to staging dir (null on failure)
	public final boolean conflict;     // true if name already exists
	public final String message;       // failure code / status (null on success)

	private SlotImportResult(boolean ok, String name, SaveSlotMeta meta,
	                         String stagingRelPath, boolean conflict, String message) {
		this.ok = ok;
		this.name = name;
		this.meta = meta;
		this.stagingRelPath = stagingRelPath;
		this.conflict = conflict;
		this.message = message;
	}

	static SlotImportResult ok(String name, SaveSlotMeta meta, String stagingRelPath, boolean conflict) {
		return new SlotImportResult(true, name, meta, stagingRelPath, conflict, null);
	}

	static SlotImportResult fail(String message) {
		return new SlotImportResult(false, null, null, null, false, message);
	}
}
