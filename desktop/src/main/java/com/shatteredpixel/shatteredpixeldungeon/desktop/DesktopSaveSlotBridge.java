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

package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.saveslot.SaveSlotService;
import com.shatteredpixel.shatteredpixeldungeon.saveslot.SlotImportResult;
import com.shatteredpixel.shatteredpixeldungeon.saveslot.WndSaveSlotSelect;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Game;
import com.watabou.utils.SaveSlotBridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Fork extension: desktop implementation of {@link SaveSlotBridge}.
 *
 * <p>Uses {@link JFileChooser} running in a worker thread to keep the libgdx
 * render loop responsive. The conflict-resolution dialog runs on the render
 * thread (libgdx scene graph access).</p>
 */
public class DesktopSaveSlotBridge implements SaveSlotBridge {

	@Override
	public boolean available() { return true; }

	// ---- export --------------------------------------------------------------

	@Override
	public void exportSlot(final String slotName, final ExportCallback cb) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				JFileChooser chooser;
				int rc;
				try {
					chooser = new JFileChooser();
					chooser.setDialogTitle("Export " + slotName + ".zip");
					chooser.setSelectedFile(new java.io.File(slotName + ".zip"));
					chooser.setFileFilter(new FileNameExtensionFilter("Zip archives", "zip"));
					rc = chooser.showSaveDialog(null);
				} catch (Throwable t) {
					Game.reportException(t);
					invokeExport(cb, false, "chooser_error");
					return;
				}
				if (rc != JFileChooser.APPROVE_OPTION) {
					invokeExport(cb, false, "cancelled");
					return;
				}
				final java.io.File target = chooser.getSelectedFile();
				boolean ok = false;
				String msg = "ok";
				OutputStream os = null;
				try {
					os = new java.io.FileOutputStream(target);
					SaveSlotService.exportToStream(slotName, os);
					ok = true;
				} catch (IOException e) {
					Game.reportException(e);
					msg = "io_error";
				} catch (Throwable t) {
					Game.reportException(t);
					msg = "error";
				} finally {
					if (os != null) try { os.close(); } catch (IOException ignored) {}
				}
				invokeExport(cb, ok, msg);
			}
		}, "spd-export").start();
	}

	// ---- import --------------------------------------------------------------

	@Override
	public void importSlot(final ImportCallback cb) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				JFileChooser chooser;
				int rc;
				try {
					chooser = new JFileChooser();
					chooser.setDialogTitle("Import Save Slot");
					chooser.setFileFilter(new FileNameExtensionFilter("Zip archives", "zip"));
					rc = chooser.showOpenDialog(null);
				} catch (Throwable t) {
					Game.reportException(t);
					invokeImport(cb, false, null, "chooser_error");
					return;
				}
				if (rc != JFileChooser.APPROVE_OPTION) {
					invokeImport(cb, false, null, "cancelled");
					return;
				}
				final java.io.File src = chooser.getSelectedFile();
				String suggested = src.getName();
				int dot = suggested.lastIndexOf('.');
				if (dot > 0) suggested = suggested.substring(0, dot);
				if (!SaveSlotService.isValidName(suggested)) suggested = "imported";

				SlotImportResult result;
				InputStream is = null;
				try {
					is = new java.io.FileInputStream(src);
					result = SaveSlotService.importFromStream(is, suggested);
				} catch (Throwable t) {
					Game.reportException(t);
					invokeImport(cb, false, null, "error");
					return;
				} finally {
					if (is != null) try { is.close(); } catch (IOException ignored) {}
				}

				if (!result.ok) {
					invokeImport(cb, false, null, result.message);
					return;
				}

				if (result.conflict) {
					askConflictResolution(result, cb);
				} else {
					commitOnWorker(result, result.name, false, cb);
				}
			}
		}, "spd-import").start();
	}

	private void askConflictResolution(final SlotImportResult result, final ImportCallback cb) {
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				final String label = result.name;
				ShatteredPixelDungeon.scene().addToFront(new WndOptions(
						WndSaveSlotSelect.txt("conflict_title"),
						WndSaveSlotSelect.txt("conflict_body", label),
						WndSaveSlotSelect.txt("btn_overwrite"),
						WndSaveSlotSelect.txt("btn_rename"),
						WndSaveSlotSelect.txt("btn_cancel_import")) {
					@Override
					protected void onSelect(int index) {
						if (index == 0) {
							commitOnWorker(result, label, true, cb);
						} else if (index == 1) {
							askRename(result, cb);
						} else {
							SaveSlotService.cancelImport(result);
							invokeImport(cb, false, null, "cancelled");
						}
					}
				});
			}
		});
	}

	private void askRename(final SlotImportResult result, final ImportCallback cb) {
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() {
				ShatteredPixelDungeon.scene().addToFront(new WndTextInput(
						WndSaveSlotSelect.txt("rename_prompt"),
						WndSaveSlotSelect.txt("rename_desc"),
						result.name + "_2",
						20,
						false,
						WndSaveSlotSelect.txt("btn_save"),
						WndSaveSlotSelect.txt("btn_cancel")) {
					@Override
					public void onSelect(boolean positive, String text) {
						if (!positive || text == null) {
							SaveSlotService.cancelImport(result);
							invokeImport(cb, false, null, "cancelled");
							return;
						}
						text = text.trim();
						if (!SaveSlotService.isValidName(text)) {
							ShatteredPixelDungeon.scene().addToFront(new WndMessage(
									WndSaveSlotSelect.txt("name_invalid")));
							askRename(result, cb);
							return;
						}
						if (SaveSlotService.slotExists(text)) {
							askRename(result, cb);
							return;
						}
						commitOnWorker(result, text, false, cb);
					}
				});
			}
		});
	}

	private void commitOnWorker(final SlotImportResult result, final String finalName,
	                            final boolean overwrite, final ImportCallback cb) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				boolean ok = SaveSlotService.commitImport(result, finalName, overwrite);
				if (!ok) {
					SaveSlotService.cancelImport(result);
				}
				String msg = ok ? "ok" : "commit_failed";
				invokeImport(cb, ok, ok ? finalName : null, msg);
			}
		}, "spd-import-commit").start();
	}

	// ---- callback thread hops ------------------------------------------------

	private void invokeExport(final ExportCallback cb, final boolean ok, final String msg) {
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() { cb.onComplete(ok, msg); }
		});
	}

	private void invokeImport(final ImportCallback cb, final boolean ok,
	                          final String name, final String msg) {
		Gdx.app.postRunnable(new Runnable() {
			@Override
			public void run() { cb.onComplete(ok, name, msg); }
		});
	}
}
