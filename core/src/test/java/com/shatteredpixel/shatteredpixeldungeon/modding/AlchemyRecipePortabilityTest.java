package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Icecap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.watabou.noosa.Game;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M19f portability assessment: remixed has JSON/Lua-driven alchemy recipes, but fork's
 * {@link Recipe} system is a static Java class registry with no mod hook. This test
 * documents the current API gap and protects against accidentally declaring that recipes
 * are portable before the M20 alchemy registry is built.
 *
 * <p>It verifies:
 * <ol>
 *   <li>The fork Lua sandbox exposes no {@code RPD.AlchemyRecipes} surface.</li>
 *   <li>{@link Recipe#findRecipes} still only resolves the built-in static recipe set
 *       (smoke-test with {@link Potion.SeedToPotion}).</li>
 *   <li>After loading the remixed_full mod entry, no alchemy-recipe-related globals appear
 *       and the static recipe set is unchanged.</li>
 * </ol>
 */
public class AlchemyRecipePortabilityTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;
	private static String savedVersion;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersionCode = Game.versionCode;
		savedVersion = Game.version;
		Game.versionCode = 896;
		Game.version = "test";
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		Game.version = savedVersion;
		try { if (application != null) application.exit(); } catch (Throwable ignored) {}
	}

	@Before
	public void resetState() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
	}

	@Test
	public void recipeFindUsesOnlyStaticBuiltInRecipes() {
		// Three different seeds → Potion.SeedToPotion is the only matching built-in recipe.
		ArrayList<Item> ingredients = new ArrayList<>();
		ingredients.add(new Firebloom.Seed());
		ingredients.add(new Icecap.Seed());
		ingredients.add(new Sorrowmoss.Seed());

		ArrayList<Recipe> found = Recipe.findRecipes(ingredients);
		assertFalse("Recipe.findRecipes must not be empty for 3 distinct seeds",
				found.isEmpty());
		assertEquals("Only the built-in SeedToPotion recipe should match",
				1, found.size());
		assertTrue("Matching recipe should be Potion.SeedToPotion",
				found.get(0) instanceof Potion.SeedToPotion);
	}

	@Test
	public void noUnidentifiedOrCursedItemsAllowedByRecipeUsability() {
		// Recipe.usableInRecipe guards against unidentified equipment and cursed items.
		// Seeds are not cursed and the check is based on class; verify a plain seed passes.
		Plant.Seed seed = new Sungrass.Seed();
		assertTrue("Plain seeds should be usable in recipes",
				Recipe.usableInRecipe(seed));
	}

	@Test
	public void luaSandboxHasNoAlchemyRecipeSurface() {
		LuaEngine.init();

		LuaValue globals = LuaEngine.instance().getGlobals();
		LuaValue rpd = globals.get("RPD");
		assertTrue("RPD table must exist", rpd.istable());

		LuaValue alchemyRecipes = rpd.checktable().get("AlchemyRecipes");
		assertTrue("RPD.AlchemyRecipes must not exist (nil)", alchemyRecipes.isnil());

		// Also confirm no top-level register_alchemy_recipe global was added.
		LuaValue registerRecipe = globals.get("register_alchemy_recipe");
		assertTrue("register_alchemy_recipe global must not exist", registerRecipe.isnil());

		// The engine should still have loaded the normal test_mod item.
		assertTrue("test_sword should still be registered",
				LuaItemRegistry.contains("test_sword"));
	}

	@Test
	public void remixedFullEntryDoesNotInjectRecipeRegistry() throws Exception {
		// Load remixed_full entry, which is the mod most likely to carry alchemy content.
		ModRegistry.setEnabled("remixed_full", true);
		LuaEngine.init();

		// Ensure the mod entry actually ran and registered its alpha hub level.
		assertTrue("remixed_full_alpha_hub level should be registered",
				LuaLevelRegistry.contains("remixed_full_alpha_hub"));

		// But alchemy recipe surface must still be absent.
		LuaValue globals = LuaEngine.instance().getGlobals();
		LuaValue rpd = globals.get("RPD");
		assertTrue("RPD table must exist after remixed_full init", rpd.istable());
		LuaValue alchemyRecipes = rpd.checktable().get("AlchemyRecipes");
		assertTrue("RPD.AlchemyRecipes must remain absent even with remixed_full enabled",
				alchemyRecipes.isnil());

		// Confirm the static recipe set still behaves the same.
		ArrayList<Item> ingredients = new ArrayList<>();
		ingredients.add(new Firebloom.Seed());
		ingredients.add(new Icecap.Seed());
		ingredients.add(new Sungrass.Seed());
		ArrayList<Recipe> found = Recipe.findRecipes(ingredients);
		assertEquals("Static recipe set must be unchanged by mod entry",
				1, found.size());
		assertTrue("Static match must remain SeedToPotion",
				found.get(0) instanceof Potion.SeedToPotion);
	}
}
