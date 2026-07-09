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

package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.WelcomeScene;
import com.shatteredpixel.shatteredpixeldungeon.modding.LuaEngine;
import com.shatteredpixel.shatteredpixeldungeon.modding.ModSpriteCache;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Music;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.DeviceCompat;
import com.shatteredpixel.shatteredpixeldungeon.saveslot.SaveSlotService;
import com.watabou.utils.PlatformSupport;

public class ShatteredPixelDungeon extends Game {

	//rankings from v1.2.3 and older use a different score formula, so this reference is kept
	public static final int v1_2_3 = 628;

	//savegames from versions older than v2.5.4 are no longer supported, and data from them is ignored
	public static final int v2_5_4 = 802;

	public static final int v3_0_2 = 833;
	public static final int v3_1_1 = 850;
	public static final int v3_2_5 = 877;
	public static final int v3_3_0 = 883;

	// Fork (M16a): mirror of Game's private versionContextRef. Game.resize()
	// only reloads its own TextureCache on GL context loss; our ModSpriteCache
	// holds SmartTextures that the engine can't see, so we detect the same
	// context loss (GLVersion reference change) and reload them ourselves.
	private GLVersion modSpriteContextRef;
	
	public ShatteredPixelDungeon( PlatformSupport platform ) {
		super( sceneClass == null ? WelcomeScene.class : sceneClass, platform );

		//pre-v3.3.0
		com.watabou.utils.Bundle.addAlias(
				com.shatteredpixel.shatteredpixeldungeon.items.keys.WornKey.class,
				"com.shatteredpixel.shatteredpixeldungeon.items.keys.SkeletonKey" );

		// Fork: capture the platform's save-slot export/import bridge.
		// Sweep of crashed-import staging dirs is deferred to create() because
		// Gdx.files is not yet initialised here.
		SaveSlotService.setBridge(platform.saveSlotBridge());
	}
	
	@Override
	public void create() {
		super.create();

		// Fork (M16a): snapshot the GL context so resize() can detect loss and
		// re-upload our mod sprite textures. super.create() already ran
		// TextureCache.reload() for the engine's textures.
		modSpriteContextRef = Gdx.graphics.getGLVersion();

		updateSystemUI();
		SPDAction.loadBindings();

		Music.INSTANCE.enable( SPDSettings.music() );
		Music.INSTANCE.volume( SPDSettings.musicVol()*SPDSettings.musicVol()/100f );
		Sample.INSTANCE.enable( SPDSettings.soundFx() );
		Sample.INSTANCE.volume( SPDSettings.SFXVol()*SPDSettings.SFXVol()/100f );

		Sample.INSTANCE.load( Assets.Sounds.all );

		// Fork: now that libgdx file handles are wired up, sweep any staging/.tmp/.bak
		// directories left behind by a crashed save-slot import.
		SaveSlotService.cleanupLeftovers();

		// Fork: M0 Lua modding PoC — bootstrap the luaj engine and run scripts/init.lua.
		// Gdx.files is live by now, so LuaEngine.findResource can read assets.
		LuaEngine.init();
	}

	@Override
	public void finish() {
		if (!DeviceCompat.isiOS()) {
			super.finish();
		} else {
			//can't exit on iOS (Apple guidelines), so just go to title screen
			switchScene(TitleScene.class);
		}
	}

	public static void switchNoFade(Class<? extends PixelScene> c){
		switchNoFade(c, null);
	}

	public static void switchNoFade(Class<? extends PixelScene> c, SceneChangeCallback callback) {
		PixelScene.noFade = true;
		switchScene( c, callback );
	}
	
	public static void seamlessResetScene(SceneChangeCallback callback) {
		if (scene() instanceof PixelScene){
			((PixelScene) scene()).saveWindows();
			switchNoFade((Class<? extends PixelScene>) sceneClass, callback );
		} else {
			resetScene();
		}
	}
	
	public static void seamlessResetScene(){
		seamlessResetScene(null);
	}
	
	@Override
	protected void switchScene() {
		super.switchScene();
		if (scene instanceof PixelScene){
			((PixelScene) scene).restoreWindows();
		}
	}
	
	@Override
	public void resize( int width, int height ) {
		if (width == 0 || height == 0){
			return;
		}

		if (scene instanceof PixelScene &&
				(height != Game.height || width != Game.width)) {
			PixelScene.noFade = true;
			((PixelScene) scene).saveWindows();
		}

		// Fork (M16a): if the GL context was lost, super.resize() has already
		// reloaded the engine's TextureCache. Reload our mod sprite textures too,
		// mirroring Game's GLVersion-reference-change detection.
		if (modSpriteContextRef != Gdx.graphics.getGLVersion()) {
			modSpriteContextRef = Gdx.graphics.getGLVersion();
			ModSpriteCache.reload();
		}

		super.resize( width, height );

		updateDisplaySize();

	}
	
	@Override
	public void destroy(){
		super.destroy();
		GameScene.endActorThread();
	}
	
	public void updateDisplaySize(){
		platform.updateDisplaySize();
	}

	public static void updateSystemUI() {
		platform.updateSystemUI();
	}
}