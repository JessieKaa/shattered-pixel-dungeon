package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6d material item coverage. M6-fast shipped these three Remished materials
 * (RottenOrgan / BoneShard / ToxicGland) as weapon reskins — a structural
 * mismatch where a "material" was actually a {@link MeleeWeapon}. M6d adds a
 * real {@link LuaMaterial} (plain {@link Item}) and dispatches via
 * {@link LuaItemRegistry#createItem}.
 *
 * <p>Pinned down here:
 * <ol>
 *   <li>All three materials register and hydrate via {@link LuaMaterial}.</li>
 *   <li>{@link LuaMaterial} is NOT a {@link MeleeWeapon} (the M6d fix).</li>
 *   <li>stackable / price / value now take effect (M6-fast ignored them).</li>
 *   <li>{@code createWeapon} rejects a material id instead of swallowing it.</li>
 *   <li>Bundle restore preserves a non-default saved quantity — the script
 *   default never overwrites it (codex plan round-1 must-fix).</li>
 * </ol>
 */
public class DataSkinItemTest {

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

    @Before
    public void resetModAndLuaState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        Game.version = savedVersion;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    @Test
    public void threeRemishedMaterialsRegisteredAsLuaMaterial() {
        LuaEngine.init();
        for (String id : new String[]{"rotten_organ", "bone_shard", "toxic_gland"}) {
            assertTrue(id + " should register", LuaItemRegistry.contains(id));
            Item item = LuaItemRegistry.createItem(id);
            assertNotNull(id + " should create", item);
            assertTrue(id + " must be a LuaMaterial (M6d fix)", item instanceof LuaMaterial);
            assertFalse(id + " must NOT be a MeleeWeapon", item instanceof MeleeWeapon);
        }
    }

    @Test
    public void rottenOrganHydratesMaterialFields() {
        LuaEngine.init();
        LuaMaterial organ = (LuaMaterial) LuaItemRegistry.createItem("rotten_organ");
        assertNotNull(organ);
        assertEquals("腐烂器官", organ.name());
        assertEquals("从瘟疫医生采集系统获得的腐烂器官。", organ.desc());
        assertEquals(4, organ.image);
        assertTrue("materials are stackable", organ.stackable);
        assertEquals("price hydrates", 5, organ.value());
    }

    @Test
    public void createWeaponRejectsMaterialId() {
        LuaEngine.init();
        // Legacy weapon-only entry must not swallow a material as a weapon.
        assertEquals("createWeapon must refuse a material", null, LuaItemRegistry.createWeapon("rotten_organ"));
        // It still works for a real weapon id.
        assertNotNull(LuaItemRegistry.createWeapon("hooked_dagger"));
    }

    @Test
    public void isSimilarMergesSameMaterialOnly() {
        LuaEngine.init();
        LuaMaterial a = (LuaMaterial) LuaItemRegistry.createItem("rotten_organ");
        LuaMaterial b = (LuaMaterial) LuaItemRegistry.createItem("rotten_organ");
        LuaMaterial c = (LuaMaterial) LuaItemRegistry.createItem("bone_shard");
        assertNotNull(a); assertNotNull(b); assertNotNull(c);
        assertTrue("same id materials merge", a.isSimilar(b));
        assertFalse("different id materials do not merge", a.isSimilar(c));
    }

    /**
     * Codex plan round-1 must-fix: bundle restore must not reset quantity to the
     * script default. Save at quantity 7 (≠ default 1), round-trip, and confirm
     * the saved count survives and drives value().
     */
    @Test
    public void bundleRestoreKeepsSavedQuantityNotScriptDefault() {
        LuaEngine.init();
        LuaMaterial organ = (LuaMaterial) LuaItemRegistry.createItem("rotten_organ");
        assertNotNull(organ);
        organ.quantity(7);

        Bundle b = new Bundle();
        organ.storeInBundle(b);
        LuaMaterial restored = new LuaMaterial();
        restored.restoreFromBundle(b);

        assertEquals("saved quantity (7) must survive, not the script default (1)",
                7, restored.quantity());
        assertEquals("value() uses the restored quantity", 5 * 7, restored.value());
        assertEquals("metadata re-hydrates from the registry", "腐烂器官", restored.name());
    }

    /**
     * detach via split path uses Reflection.newInstance + bundle round-trip; the
     * split copy must re-hydrate as the same material id and keep quantity 1.
     */
    @Test
    public void detachSplitCopyRehydratesAsMaterial() {
        LuaEngine.init();
        LuaMaterial organ = (LuaMaterial) LuaItemRegistry.createItem("rotten_organ");
        assertNotNull(organ);
        organ.quantity(2);
        Bag bag = new Bag();
        bag.items.add(organ);

        Item detached = organ.detach(bag);

        assertNotNull(detached);
        assertTrue("split copy is a LuaMaterial", detached instanceof LuaMaterial);
        assertEquals(1, detached.quantity());
        assertEquals("split copy re-hydrates name", "腐烂器官", detached.name());
    }
}
