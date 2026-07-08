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
import android.net.Uri;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.modding.ModImporter;
import com.shatteredpixel.shatteredpixeldungeon.modding.ModInstaller;
import com.watabou.noosa.Game;

import java.io.IOException;
import java.io.InputStream;

/**
 * Fork extension, M12c: android implementation of {@link ModImporter}. Uses SAF
 * ({@link Intent#ACTION_OPEN_DOCUMENT}) to let the user pick a {@code .zip} from any SAF-reachable
 * location (Downloads / Files / cloud providers), then hands the content URI's stream to
 * {@link ModInstaller#installFromStream} on a worker thread.
 *
 * <p>This is the android twin of {@code DesktopModImporter} (M12b) and mirrors the SAF flow already
 * proven by {@link AndroidSaveSlotBridge#importSlot} for save-slot zips: same picker intent, same
 * {@link AndroidLauncher#registerActivityResult(int, AndroidLauncher.ActivityResultHandler)} routing,
 * same worker-thread stream read. Only the unzip target differs ({@link ModInstaller} vs
 * {@code SaveSlotService}).
 *
 * <p>Threading (identical shape to the desktop/save-slot siblings):
 * <ul>
 *   <li>SAF picker launches on the main (UI) thread via {@code startActivityForResult}.</li>
 *   <li>The unzip runs on a worker thread so the UI thread is never blocked for the 64MB-capped
 *       read.</li>
 *   <li>Every callback hop back to the libgdx render thread goes through {@link Gdx#app}
 *       .postRunnable so the WndModManager UI update is scene-graph safe. {@link ModInstaller}
 *       invokes its callback on the calling (worker) thread, so the user callback is wrapped once
 *       to shift each method onto the render thread.</li>
 * </ul>
 *
 * <p>SAF returns a content URI, so no {@code READ_EXTERNAL_STORAGE} permission is needed (same as
 * the SaveSlot import flow). The activity reference is held by the static {@link ModImporter}
 * holder for the process lifetime; the Activity lifecycle equals the process lifecycle, so there
 * is no leak. The Activity result handler auto-removes itself after the first result
 * ({@link AndroidLauncher#onActivityResult} removes it from the map), and on
 * {@link ActivityNotFoundException} — where no result will ever arrive — we explicitly unregister
 * before reporting the error, so no stale handler is left behind.
 */
public class AndroidSafModImporter implements ModImporter {

	/** Unique SAF request code; SaveSlot uses {@code 0x5301}/{@code 0x5302}. */
	private static final int REQUEST_MOD_IMPORT = 0x5303;

	private final AndroidLauncher activity;

	public AndroidSafModImporter(AndroidLauncher activity) {
		this.activity = activity;
	}

	@Override
	public void pickZip(final ImportCallback cb) {
		// Single wrapper: every outcome (cancel, SAF unavailable, install result) hops to render thread.
		final ImportCallback wrapped = renderThreadWrapper(cb);
		if (activity == null) {
			wrapped.onError("saf_unavailable");
			return;
		}
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("application/zip");

		try {
			activity.registerActivityResult(REQUEST_MOD_IMPORT, new AndroidLauncher.ActivityResultHandler() {
				@Override
				public void onResult(int resultCode, final Intent data) {
					handleResult(wrapped, resultCode, data);
				}
			});
			activity.startActivityForResult(intent, REQUEST_MOD_IMPORT);
		} catch (ActivityNotFoundException e) {
			// No SAF picker will ever answer this request code, so drop the handler now.
			activity.unregisterActivityResult(REQUEST_MOD_IMPORT);
			wrapped.onError("saf_unavailable");
		}
	}

	private void handleResult(final ImportCallback wrapped, int resultCode, final Intent data) {
		if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
			wrapped.onCancel();
			return;
		}
		final Uri uri = data.getData();
		new Thread(new Runnable() {
			@Override
			public void run() {
				InputStream is = null;
				try {
					is = activity.getContentResolver().openInputStream(uri);
					if (is == null) {
						wrapped.onError("io_error");
						return;
					}
					// installFromStream reads the zip synchronously here (worker thread); capped at 64MB.
					ModInstaller.installFromStream(is, wrapped);
				} catch (Throwable t) {
					Game.reportException(t);
					wrapped.onError("io_error");
				} finally {
					if (is != null) try { is.close(); } catch (IOException ignored) {}
				}
			}
		}, "spd-mod-import").start();
	}

	/** Wraps {@code cb} so each method runs on the libgdx render thread via {@link Gdx#app}. */
	private static ImportCallback renderThreadWrapper(final ImportCallback cb) {
		return new ImportCallback() {
			@Override public void onSuccess(final String modId) {
				Gdx.app.postRunnable(new Runnable() {
					@Override public void run() { cb.onSuccess(modId); }
				});
			}
			@Override public void onError(final String code) {
				Gdx.app.postRunnable(new Runnable() {
					@Override public void run() { cb.onError(code); }
				});
			}
			@Override public void onCancel() {
				Gdx.app.postRunnable(new Runnable() {
					@Override public void run() { cb.onCancel(); }
				});
			}
		};
	}
}
