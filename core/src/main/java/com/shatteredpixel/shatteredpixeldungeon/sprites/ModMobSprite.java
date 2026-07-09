package com.shatteredpixel.shatteredpixeldungeon.sprites;

import com.shatteredpixel.shatteredpixeldungeon.modding.LuaMob;
import com.shatteredpixel.shatteredpixeldungeon.modding.ModSpriteCache;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.TextureFilm;

/**
 * M16a: minimal static sprite implementation for Lua mobs that declare a
 * {@code spriteFile} path. Extends {@link MobSprite} and uses the whole PNG as a
 * single-frame animation for idle/run/attack/die. This is intentionally a
 * degraded/minimal MVP: no animated frames, no directional sprites, no atlas.
 */
public class ModMobSprite extends MobSprite {

	public ModMobSprite() {
		super();
	}

	@Override
	public void linkVisuals(com.shatteredpixel.shatteredpixeldungeon.actors.Char ch) {
		if (ch instanceof LuaMob) {
			LuaMob mob = (LuaMob) ch;
			String spriteFile = mob.spriteFile();
			String ownerModId = mob.ownerModId();
			if (spriteFile != null) {
				SmartTexture modTx = ModSpriteCache.get(ownerModId, spriteFile);
				if (modTx != null) {
					texture(modTx);
					TextureFilm film = new TextureFilm(texture, modTx.width, modTx.height);
					idle = run = attack = die = new Animation(1, true);
					idle.frames(film, 0);
					run.frames(film, 0);
					attack.frames(film, 0);
					die.frames(film, 0);
					play(idle);
					return;
				}
			}
		}
		// Fallback if not a LuaMob or spriteFile failed to load: leave the sprite
		// in its default state. The upstream Mob link path will render nothing until
		// an animation is set, which is acceptable because LuaMob should only use
		// ModMobSprite when spriteFile is valid; otherwise it uses a whitelist class.
	}
}
