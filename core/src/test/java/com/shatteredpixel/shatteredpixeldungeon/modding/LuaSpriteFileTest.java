package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ModMobSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatSprite;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M16a tests: Lua item/spell/mob all correctly parse and round-trip the
 * {@code spriteFile} field from Lua definition through registry creation.
 */
public class LuaSpriteFileTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersionCode = Game.versionCode;
		Game.versionCode = 896;
	}

	@Before
	public void resetModAndLuaState() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
		ModSpriteCache.clear();
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Test
	public void luaItemParsesSpriteFileAndOwnerModId() {
		LuaEngine.init();
		assertTrue("hooked_dagger should register", LuaItemRegistry.contains("hooked_dagger"));
		LuaItem item = LuaItemRegistry.create("hooked_dagger");
		assertNotNull(item);
		assertEquals("sprites/items/item_HookedDagger.png", item.spriteFile());
		assertEquals("test_mod", item.ownerModId());
	}

	@Test
	public void luaSpellParsesSpriteFileAndOwnerModId() {
		LuaEngine.init();
		assertTrue("test_spell should register", LuaSpellRegistry.contains("test_spell"));
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		assertNotNull(spell);
		assertEquals("sprites/items/item_HookedDagger.png", spell.spriteFile());
		assertEquals("test_mod", spell.ownerModId());
	}

	@Test
	public void luaMobParsesSpriteFileAndUsesModMobSprite() {
		LuaEngine.init();
		assertTrue("test_mob should register", LuaMobRegistry.contains("test_mob"));
		LuaMob mob = LuaMobRegistry.create("test_mob");
		assertNotNull(mob);
		assertEquals("sprites/mobs/mob_TestMob.png", mob.spriteFile());
		assertEquals("test_mod", mob.ownerModId());
		assertEquals("spriteFile selects ModMobSprite class",
				ModMobSprite.class, mob.spriteClass);
	}

	@Test
	public void luaMobFallsBackToWhitelistWhenSpriteFileUnresolvable() {
		// A spriteFile that points at a missing file must NOT bind ModMobSprite
		// (which would render blank); the declared whitelist sprite wins instead.
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf("bad_sprite_mob"));
		tbl.set("name", LuaValue.valueOf("Bad Sprite Mob"));
		tbl.set("hp", LuaValue.valueOf(20));
		tbl.set("ht", LuaValue.valueOf(20));
		tbl.set("attack", LuaValue.valueOf(8));
		tbl.set("defense", LuaValue.valueOf(4));
		tbl.set("spriteFile", LuaValue.valueOf("sprites/does_not_exist.png"));
		tbl.set("sprite", LuaValue.valueOf("rat"));
		tbl.set("__mod_id", LuaValue.valueOf("test_mod"));

		LuaMob mob = new LuaMob(tbl);

		assertEquals("unresolvable spriteFile keeps the path for diagnostics",
				"sprites/does_not_exist.png", mob.spriteFile());
		assertEquals("test_mod", mob.ownerModId());
		assertEquals("missing spriteFile falls back to whitelist sprite",
				RatSprite.class, mob.spriteClass);
	}

	@Test
	public void validateSpritePathRejectsUnsafePaths() {
		// Drive-letter / absolute / traversal / backslash / bad-extension must
		// all be rejected; a clean relative image path is accepted as-is.
		assertNull(ModSpriteCache.validateSpritePath("C:/evil.png"));
		assertNull(ModSpriteCache.validateSpritePath("/etc/passwd.png"));
		assertNull(ModSpriteCache.validateSpritePath("../escape.png"));
		assertNull(ModSpriteCache.validateSpritePath("a\\b.png"));
		assertNull(ModSpriteCache.validateSpritePath("no_extension.txt"));
		assertNull(ModSpriteCache.validateSpritePath(""));
		assertEquals("sprites/items/item_HookedDagger.png",
				ModSpriteCache.validateSpritePath("sprites/items/item_HookedDagger.png"));
	}
}
