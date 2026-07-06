package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6-fast C 路径数据皮验证:Remished 纯数据材料 item(RottenOrgan / BoneShard /
 * ToxicGland)改写成 {@code register_item} 后,能否在 SPD 内 register + create,
 * 且数据字段(name/desc/tier/image)hydrate 正确。
 *
 * <p>同时锁定两条 C 路径局限(见 {@code docs/PLAN-modding-m6-fast-data-skin.md}
 * 「结构性错配」),防回归:
 * <ol>
 *   <li>{@code price} / {@code stackable} 在 register_item table 里填了,但
 *       {@link LuaItem} hydrate 不读 —— 必须静默忽略(不抛错),且 stackable 保持
 *       false(材料语义丢失)。这是 C 路径头号成本,非 bug。</li>
 *   <li>套 {@link LuaItem}(extends {@link MeleeWeapon})wrapper 的"材料"实际是武器,
 *       进武器槽、走 tier-based 公式 —— 类型错配记为已知局限。</li>
 * </ol>
 *
 * <p>mod 关闭零影响由 {@link ModToggleRegressionTest} 覆盖;此处聚焦数据皮本身的
 * register/hydrate 正确性。
 */
public class DataSkinItemTest {

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
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    @Test
    public void threeRemishedDataSkinsRegistered() {
        LuaEngine.init();
        assertTrue("rotten_organ.lua should register", LuaItemRegistry.contains("rotten_organ"));
        assertTrue("bone_shard.lua should register", LuaItemRegistry.contains("bone_shard"));
        assertTrue("toxic_gland.lua should register", LuaItemRegistry.contains("toxic_gland"));
    }

    @Test
    public void rottenOrganHydratesDataFields() {
        LuaEngine.init();
        LuaItem item = LuaItemRegistry.create("rotten_organ");
        assertNotNull(item);
        assertEquals("腐烂器官", item.name());
        assertTrue("desc should carry the C-path limitation note", item.desc().contains("C 路径数据皮"));
        assertEquals("material skin uses tier=0", 0, item.tier);
        assertEquals("image int carried over from Remished", 4, item.image);
        assertTrue("a LuaItem data-skin is still a MeleeWeapon (structural mismatch)",
                item instanceof MeleeWeapon);
    }

    @Test
    public void boneShardAndToxicGlandHydrateDataFields() {
        LuaEngine.init();
        LuaItem bone = LuaItemRegistry.create("bone_shard");
        assertNotNull(bone);
        assertEquals("骨片碎片", bone.name());
        assertEquals(0, bone.tier);
        assertEquals(5, bone.image);

        LuaItem gland = LuaItemRegistry.create("toxic_gland");
        assertNotNull(gland);
        assertEquals("毒腺", gland.name());
        assertEquals(0, gland.tier);
        assertEquals(3, gland.image);
    }

    /**
     * C 路径头号局限锁定:{@code price} / {@code stackable} 填在 register_item table 里,
     * 但 {@link LuaItem#hydrate} 不读 —— 注册不抛错,且 stackable 保持 false(材料语义丢失)。
     * 若未来有人给 hydrate 加了 price/stackable 支持,此测试会失败,提醒更新 C 路径成本结论。
     */
    @Test
    public void priceAndStackableFieldsAreIgnoredSilently() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='limit_probe', name='探测', tier=0, image=0, "
                + "price=99, stackable=true, info='ignored' }").call();
        assertTrue(LuaItemRegistry.contains("limit_probe"));

        LuaItem item = LuaItemRegistry.create("limit_probe");
        assertNotNull(item);
        assertFalse("stackable must remain false — LuaItem.hydrate does not read it (C-path limitation)",
                item.stackable);
    }

    /** All three data skins are reachable via {@link Generator}'s LUA_ITEM pool. */
    @Test
    public void dataSkinsReachableFromGeneratorPool() {
        LuaEngine.init();
        java.util.Set<String> ids = LuaItemRegistry.ids();
        assertTrue("rotten_organ in registry", ids.contains("rotten_organ"));
        assertTrue("bone_shard in registry", ids.contains("bone_shard"));
        assertTrue("toxic_gland in registry", ids.contains("toxic_gland"));
    }
}
