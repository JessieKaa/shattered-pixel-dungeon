package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Berry;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SmallRation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ShopkeeperSprite;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M4c Lua shop tests. Mirrors {@link LuaNpcTest}'s headless harness.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@link LuaShopRegistry} register/getTable/create/contains/size contract.</li>
 *   <li>{@link LuaShopNpc#hydrate}: shop name + default sprite (ShopkeeperSprite) +
 *       item pool with the quantity tri-state (finite / infinite-default /
 *       explicit-infinite / sold-out).</li>
 *   <li>{@link LuaShopItems} whitelist: known id → fresh Item; unknown id → null.</li>
 *   <li><b>{@link LuaShopNpc#attemptBuy} pure-logic routing</b>: success deducts gold
 *       + decrements finite stock (infinite unchanged); refused when poor / sold out /
 *       invalid index / unknown item (gold never changes on a refusal).</li>
 *   <li>{@code register_shop} validation: id/name/items required.</li>
 *   <li>Shipped {@code scripts/shops/test_shop.lua} registers via {@link LuaEngine#init}.</li>
 *   <li>{@link DataDrivenLevel#createMobs} {@code lua_shop:<id>} branch.</li>
 *   <li>{@code lua_shop_id} bundle round-trip + graceful degradation on missing registry.</li>
 *   <li>M1 sandbox regression — {@code luajava.bindClass} still unreachable.</li>
 * </ol>
 *
 * <p>The live WndOptions render + {@code Item.doPickUp} path (linked sprite,
 * {@code Dungeon.hero} present) is verified by the desktop run, not headlessly —
 * same split as {@code LuaNpcTest}. Here {@code Dungeon.hero} is null, so
 * {@code attemptBuy}'s doPickUp branch is skipped and we assert only on gold +
 * stock + item instantiation.
 */
public class LuaShopTest {

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

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	/** Reset gold before each buy test so order doesn't matter. */
	@Before
	public void resetGold() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
		Dungeon.gold = 0;
	}

	private Globals globals() {
		Globals g = LuaSandbox.exposedGlobals();
		g.set("RPD", RpdApi.build());
		return g;
	}

	// ---- LuaShopRegistry ----

	@Test
	public void registryRegisterGetCreate() {
		LuaShopRegistry.clear();
		LuaTable tbl = baseShopTable("reg_shop");
		LuaShopRegistry.register("reg_shop", tbl);
		assertTrue(LuaShopRegistry.contains("reg_shop"));
		assertEquals(1, LuaShopRegistry.size());
		assertTrue(LuaShopRegistry.ids().contains("reg_shop"));
		assertNotNull(LuaShopRegistry.getTable("reg_shop"));
		assertNotNull(LuaShopRegistry.create("reg_shop"));
	}

	@Test
	public void registryCreateReturnsNullForUnknown() {
		LuaShopRegistry.clear();
		assertEquals(null, LuaShopRegistry.create("does_not_exist"));
		assertFalse(LuaShopRegistry.contains("ghost_shop"));
	}

	// ---- LuaShopNpc.hydrate ----

	@Test
	public void hydrateSetsShopNameAndDefaultShopkeeperSprite() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("hydr", baseShopTable("hydr"));
		LuaShopNpc shop = LuaShopRegistry.create("hydr");
		assertNotNull(shop);
		assertEquals("name comes from lua", "测试商店", shop.name());
		assertEquals("description mirrors name (MVP)",
				"测试商店", shop.description());
		assertEquals("no sprite field → default ShopkeeperSprite (shop-themed)",
				ShopkeeperSprite.class, shop.spriteClass);
		assertEquals("three items parsed from the pool", 3, shop.entryCount());
	}

	@Test
	public void hydrateQuantityTriState() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("q", baseShopTable("q"));
		LuaShopNpc shop = LuaShopRegistry.create("q");
		assertEquals("[0] healing finite qty=2", 2, shop.entry(0).quantity);
		assertEquals("[0] healing price=50", 50, shop.entry(0).price);
		assertEquals("[1] ration quantity omitted → INFINITE (-1)",
				-1, shop.entry(1).quantity);
		assertEquals("[2] berry sold out qty=0", 0, shop.entry(2).quantity);
	}

	@Test
	public void hydrateExplicitInfiniteNegativeQuantity() {
		LuaShopRegistry.clear();
		LuaTable tbl = baseShopTable("neg");
		// override item[1] with explicit quantity=-1
		LuaTable items = new LuaTable();
		LuaTable i1 = new LuaTable();
		i1.set("id", LuaValue.valueOf("small_ration"));
		i1.set("price", LuaValue.valueOf(15));
		i1.set("quantity", LuaValue.valueOf(-1));
		items.set(1, i1);
		tbl.set("items", items);
		LuaShopRegistry.register("neg", tbl);
		assertEquals("explicit quantity=-1 → INFINITE",
				-1, LuaShopRegistry.create("neg").entry(0).quantity);
	}

	// ---- LuaShopItems whitelist ----

	@Test
	public void whitelistCreatesKnownItems() {
		assertTrue("healing → PotionOfHealing",
				LuaShopItems.create("potion_of_healing") instanceof PotionOfHealing);
		assertTrue("small_ration → SmallRation",
				LuaShopItems.create("small_ration") instanceof SmallRation);
		assertTrue("berry → Berry",
				LuaShopItems.create("berry") instanceof Berry);
	}

	@Test
	public void whitelistRejectsUnknownId() {
		assertNull("unknown id → null (not a crash)", LuaShopItems.create("nonexistent_item"));
		assertNull("null id → null", LuaShopItems.create(null));
	}

	// ---- attemptBuy pure-logic routing ----

	@Test
	public void attemptBuySuccessDeductsGoldAndDecrementsFiniteStock() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("buy", baseShopTable("buy"));
		LuaShopNpc shop = LuaShopRegistry.create("buy");
		Dungeon.gold = 100;

		assertTrue("buy [0] healing with enough gold", shop.attemptBuy(0));
		assertEquals("gold 100 - 50 = 50", 50, Dungeon.gold);
		assertEquals("finite stock 2 - 1 = 1", 1, shop.entry(0).quantity);

		assertTrue("buy [0] again", shop.attemptBuy(0));
		assertEquals("gold 50 - 50 = 0", 0, Dungeon.gold);
		assertEquals("finite stock 1 - 1 = 0 (sold out now)", 0, shop.entry(0).quantity);
	}

	@Test
	public void attemptBuyInfiniteStockNeverDecrements() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("inf", baseShopTable("inf"));
		LuaShopNpc shop = LuaShopRegistry.create("inf");
		Dungeon.gold = 100;

		assertTrue("buy [1] ration (infinite)", shop.attemptBuy(1));
		assertEquals("gold 100 - 15 = 85", 85, Dungeon.gold);
		assertEquals("infinite stock unchanged at -1", -1, shop.entry(1).quantity);

		assertTrue("buy [1] again still succeeds", shop.attemptBuy(1));
		assertEquals("gold 85 - 15 = 70", 70, Dungeon.gold);
		assertEquals("still -1", -1, shop.entry(1).quantity);
	}

	@Test
	public void attemptBuyRefusesWhenPoorAndDoesNotMutate() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("poor", baseShopTable("poor"));
		LuaShopNpc shop = LuaShopRegistry.create("poor");
		Dungeon.gold = 10;  // < 50 (healing) and < 15 (ration)

		assertFalse("not enough gold for healing", shop.attemptBuy(0));
		assertEquals("gold unchanged on refusal", 10, Dungeon.gold);
		assertEquals("stock unchanged on refusal", 2, shop.entry(0).quantity);

		assertFalse("not enough gold for ration either", shop.attemptBuy(1));
		assertEquals("gold still unchanged", 10, Dungeon.gold);
	}

	@Test
	public void attemptBuyRefusesSoldOut() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("so", baseShopTable("so"));
		LuaShopNpc shop = LuaShopRegistry.create("so");
		Dungeon.gold = 1000;

		assertFalse("[2] berry is sold out (qty=0)", shop.attemptBuy(2));
		assertEquals("gold unchanged", 1000, Dungeon.gold);
	}

	@Test
	public void attemptBuyInvalidIndex() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("idx", baseShopTable("idx"));
		LuaShopNpc shop = LuaShopRegistry.create("idx");
		Dungeon.gold = 1000;

		assertFalse("negative index", shop.attemptBuy(-1));
		assertFalse("out-of-range index", shop.attemptBuy(99));
		assertEquals("gold unchanged", 1000, Dungeon.gold);
	}

	@Test
	public void attemptBuyRefusesUnknownItemIdWithoutMutating() {
		LuaShopRegistry.clear();
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf("unk"));
		tbl.set("name", LuaValue.valueOf("u"));
		LuaTable items = new LuaTable();
		LuaTable i1 = new LuaTable();
		i1.set("id", LuaValue.valueOf("nonexistent_item"));
		i1.set("price", LuaValue.valueOf(5));
		items.set(1, i1);
		tbl.set("items", items);
		LuaShopRegistry.register("unk", tbl);

		LuaShopNpc shop = LuaShopRegistry.create("unk");
		Dungeon.gold = 100;
		assertFalse("unknown item id → refuse", shop.attemptBuy(0));
		assertEquals("gold unchanged on unknown item", 100, Dungeon.gold);
	}

	// ---- register_shop global ----

	@Test
	public void registerShopAcceptsValidTable() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_shop{ id='ok_shop', name='x', items={{id='berry', price=10}} }").call();
		assertTrue("valid table should register", LuaShopRegistry.contains("ok_shop"));
	}

	@Test
	public void registerShopRejectsMissingRequiredFields() {
		LuaEngine.init();
		LuaShopRegistry.clear();
		Globals g = LuaEngine.instance().globals();

		g.load("register_shop{ name='no_id', items={{id='berry', price=10}} }").call();
		assertEquals("missing id → rejected", 0, LuaShopRegistry.size());

		g.load("register_shop{ id='no_name', items={{id='berry', price=10}} }").call();
		assertEquals("missing name → rejected", 0, LuaShopRegistry.size());

		g.load("register_shop{ id='no_items', name='x' }").call();
		assertEquals("missing items → rejected", 0, LuaShopRegistry.size());
		assertFalse(LuaShopRegistry.contains("no_items"));
	}

	@Test
	public void shippedTestShopRegistersViaEngineInit() {
		LuaShopRegistry.clear();
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("scripts/shops/test_shop.lua should register via LuaEngine.init",
				LuaShopRegistry.contains("test_shop"));
	}

	// ---- DataDrivenLevel.createMobs lua_shop:<id> branch ----

	@Test
	public void createMobsInstantiatesLuaShopFromRegistry() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("lvl_shop", baseShopTable("lvl_shop"));

		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(sampleJsonWithLuaShop(), "test");
		lvl.build();
		initLevelActorCollections(lvl);
		lvl.createMobs();

		int luaShopCount = 0;
		for (Mob m : lvl.mobs) {
			if (m instanceof LuaShopNpc) luaShopCount++;
		}
		assertEquals("the lua_shop:test spec should produce one LuaShopNpc on the level",
				1, luaShopCount);
	}

	@Test
	public void createMobsSkipsUnknownLuaShopIdWithoutThrowing() {
		LuaShopRegistry.clear();  // no "ghost_shop" registered
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(sampleJsonWithUnknownLuaShop(), "test");
		lvl.build();
		initLevelActorCollections(lvl);
		lvl.createMobs();

		assertEquals("unknown lua_shop id is skipped, level stays empty",
				0, lvl.mobs.size());
	}

	private static void initLevelActorCollections(DataDrivenLevel lvl) {
		// Mirror Level.create()'s ordering so passable[] is populated for createMobs'
		// pos validation (same trick LuaNpcTest uses).
		lvl.mobs = new HashSet<>();
		lvl.heaps = new com.watabou.utils.SparseArray<>();
		lvl.blobs = new HashMap<>();
		lvl.plants = new com.watabou.utils.SparseArray<>();
		lvl.traps = new com.watabou.utils.SparseArray<>();
		lvl.customTiles = new ArrayList<>();
		lvl.customWalls = new ArrayList<>();
		lvl.visited = new boolean[lvl.length()];
		lvl.mapped = new boolean[lvl.length()];
		lvl.transitions = new ArrayList<>();
		lvl.buildFlagMaps();
	}

	private static com.badlogic.gdx.utils.JsonValue sampleJsonWithLuaShop() {
		String json = ("{'id':'t','name':'t','width':4,'height':4,'entrance':5,'safe':true,"
				+ "'tiles':['wall','wall','wall','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','wall','wall','wall'],"
				+ "'mobs':[{'type':'lua_shop:lvl_shop','pos':6}]"
				+ "}").replace('\'', '"');
		return new com.badlogic.gdx.utils.JsonReader().parse(json);
	}

	private static com.badlogic.gdx.utils.JsonValue sampleJsonWithUnknownLuaShop() {
		String json = ("{'id':'t','name':'t','width':4,'height':4,'entrance':5,'safe':true,"
				+ "'tiles':['wall','wall','wall','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','wall','wall','wall'],"
				+ "'mobs':[{'type':'lua_shop:ghost_shop','pos':6}]"
				+ "}").replace('\'', '"');
		return new com.badlogic.gdx.utils.JsonReader().parse(json);
	}

	// ---- persistence ----

	@Test
	public void bundleRoundTripsLuaShopIdAndRehydratesName() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("rt", baseShopTable("rt"));
		LuaShopNpc original = LuaShopRegistry.create("rt");
		assertEquals("fresh shop name from lua", "测试商店", original.name());
		assertEquals("fresh shop has 3 entries", 3, original.entryCount());

		Bundle b = new Bundle();
		original.storeInBundle(b);
		LuaShopNpc restored = new LuaShopNpc();
		restored.restoreFromBundle(b);

		assertEquals("lua_shop_id round-trips", "rt", restored.luaShopId());
		assertEquals("shopName re-hydrated from registry after restore",
				"测试商店", restored.name());
		assertEquals("sprite re-hydrated to ShopkeeperSprite",
				ShopkeeperSprite.class, restored.spriteClass);
		assertEquals("entries re-parsed from registry", 3, restored.entryCount());
	}

	@Test
	public void restoreDegradesGracefullyWhenRegistryEmpty() {
		LuaShopRegistry.clear();
		LuaShopRegistry.register("gone", baseShopTable("gone"));
		LuaShopNpc src = LuaShopRegistry.create("gone");
		Bundle b = new Bundle();
		src.storeInBundle(b);

		LuaShopRegistry.clear();  // simulate engine init failing / script removed
		LuaShopNpc restored = new LuaShopNpc();
		restored.restoreFromBundle(b);
		assertEquals("missing definition → degraded name, no crash",
				"??? (gone)", restored.name());
		assertEquals("entries empty after degraded restore", 0, restored.entryCount());
		assertEquals("spriteClass falls back to ShopkeeperSprite (not null) so Mob.sprite() won't crash (codex phase-2 must-fix)",
				ShopkeeperSprite.class, restored.spriteClass);
	}

	@Test
	public void hydrateRejectsNegativePrice() {
		// codex phase-2 must-fix: a negative price must be rejected (skip+log),
		// not silently coerced to 0 (which would make the item free).
		LuaShopRegistry.clear();
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf("negprice"));
		tbl.set("name", LuaValue.valueOf("neg"));
		LuaTable items = new LuaTable();
		LuaTable bad = new LuaTable();
		bad.set("id", LuaValue.valueOf("berry"));
		bad.set("price", LuaValue.valueOf(-50));  // negative → must skip
		items.set(1, bad);
		LuaTable ok = new LuaTable();
		ok.set("id", LuaValue.valueOf("small_ration"));
		ok.set("price", LuaValue.valueOf(15));    // valid → kept
		items.set(2, ok);
		tbl.set("items", items);
		LuaShopRegistry.register("negprice", tbl);

		LuaShopNpc shop = LuaShopRegistry.create("negprice");
		assertEquals("negative-price entry skipped, valid one kept", 1, shop.entryCount());
		assertEquals("only the valid entry remains", "small_ration", shop.entry(0).itemId);
		assertEquals("valid entry price intact", 15, shop.entry(0).price);
	}

	@Test
	public void bundleDoesNotLeakDialogNpcMarker() throws Exception {
		// A LuaShopNpc must persist as a shop (lua_shop_id), never also as a dialog
		// NPC (lua_npc_id) — that would double-resolve on restore. Verify the bundle
		// carries lua_shop_id but NOT lua_npc_id.
		LuaShopRegistry.clear();
		LuaShopRegistry.register("nl", baseShopTable("nl"));
		LuaShopNpc shop = LuaShopRegistry.create("nl");
		Bundle b = new Bundle();
		shop.storeInBundle(b);

		assertTrue("lua_shop_id present", b.contains("lua_shop_id"));
		assertFalse("lua_npc_id must NOT leak from a LuaShopNpc",
				b.contains("lua_npc_id"));
	}

	// ---- M1 sandbox regression ----

	@Test
	public void luajavaBindClassStillUnreachableWithShopGlobalsInjected() {
		Globals g = globals();
		LuaValue ok = g.load(
				"return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
		).call();
		assertFalse("luajava.bindClass must still fail with register_shop present",
				ok.toboolean());
		assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
	}

	// ---- LuaShopItems registry lookup (M15d) ----

	@Test
	public void createReturnsLuaItemInstance() {
		LuaItemRegistry.clear();
		LuaItemRegistry.register("m15d_test_blade", luaItemTable("m15d_test_blade", "weapon"));

		Item item = LuaShopItems.create("m15d_test_blade");
		assertNotNull("registered Lua item id → instance", item);
		assertTrue("Lua item is LuaItem", item instanceof LuaItem);
		assertEquals("m15d_test_blade", item.title());
	}

	@Test
	public void createReturnsLuaMaterialInstance() {
		LuaItemRegistry.clear();
		LuaItemRegistry.register("m15d_test_ore", luaItemTable("m15d_test_ore", "material"));

		Item item = LuaShopItems.create("m15d_test_ore");
		assertNotNull("registered Lua material id → instance", item);
		assertTrue("Lua material is LuaMaterial", item instanceof LuaMaterial);
		assertEquals("m15d_test_ore", item.title());
	}

	@Test
	public void createReturnsLuaSpellInstance() {
		LuaSpellRegistry.clear();
		LuaSpellRegistry.register("m15d_test_spell", luaSpellTable("m15d_test_spell"));

		Item item = LuaShopItems.create("m15d_test_spell");
		assertNotNull("registered Lua spell id → instance", item);
		assertTrue("Lua spell is LuaSpell", item instanceof LuaSpell);
		assertEquals("m15d_test_spell", item.title());
	}

	@Test
	public void createPrefersLuaRegistryOverVanillaIdCollision() {
		// A Lua author may register an id that happens to collide with a vanilla
		// consumable id (e.g. "berry"). The Lua registry should win.
		LuaItemRegistry.clear();
		LuaItemRegistry.register("berry", luaItemTable("berry", "weapon"));

		Item item = LuaShopItems.create("berry");
		assertNotNull(item);
		assertTrue("Lua registry takes precedence over vanilla whitelist",
				item instanceof LuaItem);
		assertEquals("berry", item.title());
	}

	@Test
	public void attemptBuyWorksForLuaItemAndLuaSpell() {
		LuaItemRegistry.clear();
		LuaSpellRegistry.clear();
		LuaItemRegistry.register("m15d_buy_blade", luaItemTable("m15d_buy_blade", "weapon"));
		LuaSpellRegistry.register("m15d_buy_spell", luaSpellTable("m15d_buy_spell"));

		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf("m15d_buy_shop"));
		tbl.set("name", LuaValue.valueOf("M15d Shop"));
		LuaTable items = new LuaTable();
		LuaTable i1 = new LuaTable();
		i1.set("id", LuaValue.valueOf("m15d_buy_blade"));
		i1.set("price", LuaValue.valueOf(30));
		i1.set("quantity", LuaValue.valueOf(1));
		items.set(1, i1);
		LuaTable i2 = new LuaTable();
		i2.set("id", LuaValue.valueOf("m15d_buy_spell"));
		i2.set("price", LuaValue.valueOf(20));
		items.set(2, i2);
		tbl.set("items", items);
		LuaShopRegistry.register("m15d_buy_shop", tbl);

		LuaShopNpc shop = LuaShopRegistry.create("m15d_buy_shop");
		Dungeon.gold = 100;

		assertTrue("buy Lua item", shop.attemptBuy(0));
		assertEquals("gold 100 - 30 = 70", 70, Dungeon.gold);
		assertEquals("finite Lua item stock decremented", 0, shop.entry(0).quantity);

		assertTrue("buy Lua spell", shop.attemptBuy(1));
		assertEquals("gold 70 - 20 = 50", 50, Dungeon.gold);
		assertEquals("infinite Lua spell stock stays -1", -1, shop.entry(1).quantity);
	}

	// ---- helpers ----

	private static LuaTable luaItemTable(String id, String type) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf(id));
		tbl.set("desc", LuaValue.valueOf("test"));
		tbl.set("tier", LuaValue.valueOf(1));
		tbl.set("image", LuaValue.valueOf(0));
		tbl.set("type", LuaValue.valueOf(type));
		return tbl;
	}

	private static LuaTable luaSpellTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf(id));
		tbl.set("desc", LuaValue.valueOf("test"));
		tbl.set("image", LuaValue.valueOf(0));
		return tbl;
	}

	private static LuaTable baseShopTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf("测试商店"));
		LuaTable items = new LuaTable();

		// [1] healing, price 50, qty 2 (finite)
		LuaTable i1 = new LuaTable();
		i1.set("id", LuaValue.valueOf("potion_of_healing"));
		i1.set("price", LuaValue.valueOf(50));
		i1.set("quantity", LuaValue.valueOf(2));
		items.set(1, i1);

		// [2] ration, price 15, no quantity (infinite)
		LuaTable i2 = new LuaTable();
		i2.set("id", LuaValue.valueOf("small_ration"));
		i2.set("price", LuaValue.valueOf(15));
		items.set(2, i2);

		// [3] berry, price 10, qty 0 (sold out)
		LuaTable i3 = new LuaTable();
		i3.set("id", LuaValue.valueOf("berry"));
		i3.set("price", LuaValue.valueOf(10));
		i3.set("quantity", LuaValue.valueOf(0));
		items.set(3, i3);

		tbl.set("items", items);
		return tbl;
	}
}
