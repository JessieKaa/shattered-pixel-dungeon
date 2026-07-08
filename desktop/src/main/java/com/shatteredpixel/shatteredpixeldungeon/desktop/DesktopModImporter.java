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
import com.shatteredpixel.shatteredpixeldungeon.modding.ModImporter;
import com.shatteredpixel.shatteredpixeldungeon.modding.ModInstaller;
import com.watabou.noosa.Game;

import java.awt.EventQueue;
import java.io.IOException;
import java.io.InputStream;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Fork extension, M12b: desktop implementation of {@link ModImporter}. A Swing {@link JFileChooser}
 * is created and shown on the Swing EDT ({@link EventQueue#invokeAndWait}) and hands the chosen
 * {@code .zip} to {@link ModInstaller#installFromStream} on a worker thread.
 *
 * <p>Threading: the picker runs on the EDT (Swing components must be constructed/shown there); the
 * unzip runs on the worker thread (so the EDT is not blocked for the duration of the 64MB-capped
 * read); and every callback hop back to the libgdx render thread goes through
 * {@link Gdx#app}.postRunnable so the WndModManager UI update is scene-graph safe. {@link
 * ModInstaller} invokes its callback on the calling (worker) thread, so the user callback is
 * wrapped once to shift each method onto the render thread.
 *
 * <p>Android's SAF-backed impl is M12c; until then {@link ModImporter#get()} returns null on
 * android and the import button stays hidden (no crash).
 */
public class DesktopModImporter implements ModImporter {

	@Override
	public void pickZip(final ImportCallback cb) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				// Single wrapper: every outcome (chooser error/cancel, install result) hops to render thread.
				final ImportCallback wrapped = renderThreadWrapper(cb);

				// JFileChooser is a Swing component — construct AND show it on the EDT (Swing
				// thread-safety covers construction too, not just setVisible). invokeAndWait blocks
				// THIS worker thread until the user picks (the libgdx render thread is separate and
				// keeps rendering). Only the return code + selected File escape the EDT; the chooser
				// itself is a local that never leaves the runnable.
				final int[] rc = new int[1];
				final java.io.File[] picked = new java.io.File[1];
				try {
					EventQueue.invokeAndWait(new Runnable() {
						@Override
						public void run() {
							JFileChooser chooser = new JFileChooser();
							chooser.setDialogTitle("Import Mod (.zip)");
							chooser.setFileFilter(new FileNameExtensionFilter("Zip archives", "zip"));
							rc[0] = chooser.showOpenDialog(null);
							if (rc[0] == JFileChooser.APPROVE_OPTION) {
								picked[0] = chooser.getSelectedFile();
							}
						}
					});
				} catch (Throwable t) {
					Game.reportException(t);
					wrapped.onError("io_error");
					return;
				}
				if (rc[0] != JFileChooser.APPROVE_OPTION || picked[0] == null) {
					wrapped.onCancel();
					return;
				}

				// Install on the worker thread — do not hold the EDT during the unzip.
				InputStream is = null;
				try {
					is = new java.io.FileInputStream(picked[0]);
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
