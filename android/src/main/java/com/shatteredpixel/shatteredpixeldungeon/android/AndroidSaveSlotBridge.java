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

package com.shatteredpixel.shatteredpixeldungeon.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

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

/**
 * Fork extension: Android implementation of {@link SaveSlotBridge}.
 *
 * <p>Uses SAF ({@code ACTION_CREATE_DOCUMENT} / {@code ACTION_OPEN_DOCUMENT}) to
 * let the user pick an export target or an import source. Activity results are
 * routed back through {@link AndroidLauncher#registerActivityResult(int,
 * AndroidLauncher.ActivityResultHandler)}.</p>
 *
 * <p>Threading model:</p>
 * <ul>
 *   <li>Export: SAF picker (main) → worker thread streams zip into URI → render thread callback.</li>
 *   <li>Import: SAF picker (main) → worker thread reads zip into staging + resolves suggested
 *       name → render thread asks user to confirm overwrite / rename → worker thread commits
 *       → render thread callback.</li>
 * </ul>
 *
 * <p>User cancellation returns {@code ok=false} and does <em>not</em> call
 * {@link Game#reportException(Throwable)}.</p>
 */
public class AndroidSaveSlotBridge implements SaveSlotBridge {

	private static final int REQUEST_EXPORT = 0x5301;
	private static final int REQUEST_IMPORT = 0x5302;

	private final AndroidLauncher activity;

	public AndroidSaveSlotBridge(AndroidLauncher activity) {
		this.activity = activity;
	}

	@Override
	public boolean available() {
		return activity != null;
	}

	// ---- export --------------------------------------------------------------

	@Override
	public void exportSlot(final String slotName, final ExportCallback cb) {
		if (activity == null) {
			invokeExport(cb, false, "no_activity");
			return;
		}
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("application/zip");
		intent.putExtra(Intent.EXTRA_TITLE, slotName + ".zip");

		try {
			activity.registerActivityResult(REQUEST_EXPORT, new AndroidLauncher.ActivityResultHandler() {
				@Override
				public void onResult(int resultCode, final Intent data) {
					handleExportResult(slotName, cb, resultCode, data);
				}
			});
			activity.startActivityForResult(intent, REQUEST_EXPORT);
		} catch (ActivityNotFoundException e) {
			activity.unregisterActivityResult(REQUEST_EXPORT);
			invokeExport(cb, false, "saf_unavailable");
		}
	}

	private void handleExportResult(final String slotName, final ExportCallback cb,
	                                int resultCode, final Intent data) {
		if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
			invokeExport(cb, false, "cancelled");
			return;
		}
		final Uri uri = data.getData();
		new Thread(new Runnable() {
			@Override
			public void run() {
				boolean ok = false;
				String msg = "export_failed";
				OutputStream os = null;
				try {
					os = activity.getContentResolver().openOutputStream(uri);
					if (os == null) {
						msg = "open_output_failed";
					} else {
						SaveSlotService.exportToStream(slotName, os);
						ok = true;
						msg = "ok";
					}
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
		if (activity == null) {
			invokeImport(cb, false, null, "no_activity");
			return;
		}
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("application/zip");

		try {
			activity.registerActivityResult(REQUEST_IMPORT, new AndroidLauncher.ActivityResultHandler() {
				@Override
				public void onResult(int resultCode, final Intent data) {
					handleImportResult(cb, resultCode, data);
				}
			});
			activity.startActivityForResult(intent, REQUEST_IMPORT);
		} catch (ActivityNotFoundException e) {
			activity.unregisterActivityResult(REQUEST_IMPORT);
			invokeImport(cb, false, null, "saf_unavailable");
		}
	}

	private void handleImportResult(final ImportCallback cb, int resultCode, final Intent data) {
		if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
			invokeImport(cb, false, null, "cancelled");
			return;
		}
		final Uri uri = data.getData();
		new Thread(new Runnable() {
			@Override
			public void run() {
				String suggested = resolveSuggestedName(uri);
				SlotImportResult result;
				InputStream is = null;
				try {
					is = activity.getContentResolver().openInputStream(uri);
					if (is == null) {
						invokeImport(cb, false, null, "open_input_failed");
						return;
					}
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

				// Phase 2: confirm overwrite / rename on render thread.
				if (result.conflict) {
					askConflictResolution(result, cb);
				} else {
					commitOnWorker(result, result.name, false, cb);
				}
			}
		}, "spd-import").start();
	}

	private String resolveSuggestedName(Uri uri) {
		String displayName = null;
		Cursor cursor = null;
		try {
			cursor = activity.getContentResolver().query(uri,
					new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (idx >= 0) displayName = cursor.getString(idx);
			}
		} catch (Throwable ignored) {
		} finally {
			if (cursor != null) cursor.close();
		}
		if (displayName != null) {
			int dot = displayName.lastIndexOf('.');
			if (dot > 0) displayName = displayName.substring(0, dot);
		}
		if (SaveSlotService.isValidName(displayName)) return displayName;
		return "imported";
	}

	private void askConflictResolution(final SlotImportResult result, final ImportCallback cb) {
		// Phase 2 must run on render thread because WndOptions touches the scene graph.
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
							// recursive: another conflict, ask again
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
					// Make sure staging is gone immediately rather than waiting for the
					// next startup cleanup.
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
