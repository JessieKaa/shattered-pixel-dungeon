package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blizzard;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ConfusionGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.CorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Electricity;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Freezing;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Inferno;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Regrowth;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SmokeScreen;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StenchGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StormCloud;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Web;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Charm;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Drowsy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Sleep;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Speed;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Terror;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vulnerable;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Weakness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * The narrow {@code RPD.*} surface injected into the Lua sandbox
 * ({@code globals.set("RPD", RpdApi.build())}). Lua never receives a Char
 * object — only an {@code int} char id resolved here via {@link Actor#findById}
 * (D3 option B). Every write path validates its arguments and silently logs +
 * returns NIL on anything illegal, so a misbehaving script cannot crash combat
 * or tunnel past the buff whitelist.
 *
 * <p>The buff whitelist is per-buff ({@link BuffApplier}) because SPD buffs do
 * not share a uniform "apply(duration)" contract: FlavourBuff subclasses use
 * {@link Buff#prolong}, while Bleeding/Poison/Barkskin expose type-specific
 * setters. Calling {@code Actor.spend} from this package is impossible
 * (protected) and would also leave level-based buffs at level 0.
 */
final class RpdApi {

    private static final String TAG = "RpdApi";

    /** Cap for any single amount/duration argument — large enough for gameplay, small enough to bound a runaway script. */
    private static final float MAX_AMOUNT = 1000f;

    /**
     * M6e balance #1: per-hero, per-depth cumulative {@code giveItem} quota. Blocks
     * a Lua script from flooding the backpack by calling {@code giveItem} in a loop
     * (a single call can already grant up to {@link #MAX_AMOUNT}). Fork-local static
     * map — no {@link Hero} field or bundle concern; resets naturally as depth
     * changes, and {@link #resetGiveQuota()} clears it for tests / new runs.
     */
    private static final int GIVE_ITEM_CAP_PER_DEPTH = 20;
    private static final java.util.Map<Integer, java.util.Map<Integer, Integer>> giveQuota = new java.util.HashMap<>();

    /** Test / new-run hook — clears the {@code giveItem} quota. */
    static void resetGiveQuota() {
        giveQuota.clear();
    }

    private static boolean giveQuotaAllows(int heroId, int qty) {
        int depth = Dungeon.depth;
        int already = giveQuota.getOrDefault(heroId, java.util.Collections.emptyMap())
                .getOrDefault(depth, 0);
        return already + qty <= GIVE_ITEM_CAP_PER_DEPTH;
    }

    private static void recordGive(int heroId, int qty) {
        int depth = Dungeon.depth;
        giveQuota.computeIfAbsent(heroId, k -> new java.util.HashMap<>())
                .merge(depth, qty, Integer::sum);
    }

    /**
     * Identifiable singleton passed to {@link Char#damage} as the source.
     * {@code Char.damage} reads {@code src.getClass()} for resistance/death-cause
     * categorisation, so a named object (not {@code RpdApi.class}, whose getClass
     * is {@code java.lang.Class}) keeps logs meaningful.
     */
    static final Object LUA_SOURCE = new Object() {
        @Override public String toString() { return "LuaScript"; }
    };

    /** Per-buff application strategy. */
    @FunctionalInterface
    interface BuffApplier {
        void apply(Char target, float amount);
    }

    private RpdApi() { }

    static LuaTable build() {
        LuaTable rpd = new LuaTable();
        rpd.set("affectBuff", new AffectBuff());
        rpd.set("damageChar", new DamageChar());
        rpd.set("healChar", new HealChar());
        rpd.set("GLog", new GLogI());
        rpd.set("GLogW", new GLogW());
        rpd.set("charHP", new CharHP());
        rpd.set("charPos", new CharPos());
        rpd.set("charName", new CharName());
        // M3a: spawnMob injects a Lua-defined hostile mob at GameScene.add level,
        // bypassing Level.createMob/MobSpawner/mobsToSpawn (C3/C4: vanilla spawn
        // balance untouched). The mob is not part of the rotation pool.
        rpd.set("spawnMob", new SpawnMob());
        // M3b: friendly pets. spawnAlly mirrors spawnMob (same GameScene.add
        // registration point, no Level.createMob); commandAlly dispatches the
        // DirectableAlly follow/defend/attack API the ally inherits; expelAlly
        // removes a summoned ally. Allies are not part of the vanilla spawn pool.
        rpd.set("spawnAlly", new SpawnAlly());
        rpd.set("commandAlly", new CommandAlly());
        rpd.set("expelAlly", new ExpelAlly());
        // M4b: NPC dialog primitives. npcYell routes through Mob.yell (GLog line,
        // NPC-only); showDialog opens a WndMessage on the render thread (onInteract
        // fires from the actor thread). Both take an int charId and validate it.
        rpd.set("npcYell", new NpcYell());
        rpd.set("showDialog", new ShowDialog());
        // M4d: town-portal bridge. enterTown/leaveTown delegate to LuaLevelService
        // on the render thread (onInteract fires on the actor thread; switchScene
        // must run on render). Both are debug-gated inside LuaLevelService.
        rpd.set("enterTown", new EnterTown());
        rpd.set("leaveTown", new LeaveTown());
        // M6a: Remished-bridge subset for blob/immunity driven mobs (D5'-(a):
        // hand-built id-based API, luajava stays disabled). Blobs/Buffs are
        // string-id constant tables (no Java Class handle crosses the sandbox);
        // placeBlob seeds a whitelisted Blob at a cell; addImmunity registers a
        // whitelisted Class in a LuaMob's immunities so its own gas can't hurt it.
        rpd.set("Blobs", blobConstants());
        rpd.set("Buffs", buffConstants());
        rpd.set("placeBlob", new PlaceBlob());
        rpd.set("addImmunity", new AddImmunity());
        rpd.set("setMobAi", new SetMobAi());
        rpd.set("enemyOf", new EnemyOf());
        rpd.set("cellDistance", new CellDistance());
        rpd.set("emptyCellNextTo", new EmptyCellNextTo());
        rpd.set("blink", new Blink());
        // M6c buff API — affect/remove/permanent for both Java whitelist buffs
        // and Lua-defined buffs (id-resolved). RPD.Buffs still covers only the
        // Java whitelist (build() runs before loadBuffScripts()); Lua buff ids
        // are passed as plain strings.
        rpd.set("removeBuff", new RemoveBuff());
        rpd.set("detachBuff", new RemoveBuff());
        rpd.set("permanentBuff", new PermanentBuff());
        rpd.set("setBuffLevel", new SetBuffLevel());
        rpd.set("buffLevel", new BuffLevel());
        // M6d item/spell API: id/index/cell primitives for inventory + spells.
        // No entry returns a Java object/userdata — giveItem returns bool,
        // randomBackpackItem returns a 1-based index, itemName returns a string,
        // cellRay returns a Lua array of ints. ScriptedThief loot is held in the
        // Java-side Mob.loot field and dropped on death via createLoot().
        rpd.set("giveItem", new GiveItem());
        rpd.set("randomBackpackItem", new RandomBackpackItem());
        rpd.set("itemName", new ItemName());
        rpd.set("removeBackpackItem", new RemoveBackpackItem());
        rpd.set("stealRandomItem", new StealRandomItem());
        rpd.set("stolenLootName", new StolenLootName());
        rpd.set("teleportChar", new TeleportChar());
        rpd.set("charAtCell", new CharAtCell());
        rpd.set("cellRay", new CellRay());
        rpd.set("zapEffect", new ZapEffect());
        rpd.set("spawnMobNear", new SpawnMobNear());
        // M7d mana API: hero-only MP primitives (vanilla chars have no mana pool,
        // so these NIL/return-false on non-Hero targets). Used by mana-mode spells
        // (heal-on-cooldown style) and the ManaRegen-equivalent of regen burst.
        rpd.set("heroMana", new HeroMana());
        rpd.set("heroManaMax", new HeroManaMax());
        rpd.set("spendMana", new SpendMana());
        rpd.set("restoreMana", new RestoreMana());
        // M7b hook API: belongings read + char yell. Used by encumbrance /
        // unsuitable_item buffs (charAct random yell about an encumbering
        // equipped item). id-resolved, no Java object crosses the sandbox.
        rpd.set("encumbranceItemName", new EncumbranceItemName());
        rpd.set("yell", new Yell());
        // M8b shield API: unified shield-points pool (ShieldTracker) shared by
        // Lua shield buffs. id/int only across the sandbox (D5'-(a)); the Char
        // key is resolved here and never exposed to Lua. addShield feeds the
        // pool, charShield reads it, absorbShield drains it and returns the
        // leftover damage for a defenseProc to pass back to Char.
        rpd.set("addShield", new AddShield());
        rpd.set("charShield", new CharShield());
        rpd.set("absorbShield", new AbsorbShield());
        // M10b: Terrain constants for Lua painter scripts (setTile target ints).
        // Only the decorative subset Lua painters are allowed to set is exposed —
        // the adapter's setTile gate rejects anything outside this whitelist, but
        // exposing the full enum would mislead script authors. Plus a few
        // read-only reference constants (WALL/DOOR/WATER/GRASS/TRAP) so scripts
        // can branch on level.tileAt(cell) without magic numbers.
        rpd.set("Terrain", terrainConstants());
        // M11c: terrain read/write API for item scripts (pickaxe mining).
        rpd.set("terrain", new TerrainAt());
        rpd.set("setTerrain", new SetTerrain());
        rpd.set("isWall", new IsWall());
        rpd.set("isSolid", new IsSolid());
        rpd.set("dig", new Dig());
        rpd.set("dropItem", new DropItem());
        rpd.set("levelWidth", new LevelWidth());
        return rpd;
    }

    /**
     * M10b: {@code RPD.Terrain} — int constants for Lua painter scripts. Combines
     * the writable decorative subset (EMPTY/EMPTY_DECO/EMPTY_SP/EMBERS, matching
     * {@link LuaPainterAdapter}'s target whitelist) with read-only reference
     * constants scripts use to inspect existing tiles. WALL_DECO is deliberately
     * NOT in the writable subset: its flags equal WALL (SOLID|LOS_BLOCKING).
     */
    private static LuaTable terrainConstants() {
        LuaTable t = new LuaTable();
        t.set("EMPTY", LuaValue.valueOf(Terrain.EMPTY));
        t.set("EMPTY_DECO", LuaValue.valueOf(Terrain.EMPTY_DECO));
        t.set("EMPTY_SP", LuaValue.valueOf(Terrain.EMPTY_SP));
        t.set("EMBERS", LuaValue.valueOf(Terrain.EMBERS));
        // read-only references
        t.set("WALL", LuaValue.valueOf(Terrain.WALL));
        t.set("WALL_DECO", LuaValue.valueOf(Terrain.WALL_DECO));
        t.set("DOOR", LuaValue.valueOf(Terrain.DOOR));
        t.set("WATER", LuaValue.valueOf(Terrain.WATER));
        t.set("GRASS", LuaValue.valueOf(Terrain.GRASS));
        t.set("HIGH_GRASS", LuaValue.valueOf(Terrain.HIGH_GRASS));
        t.set("FURROWED_GRASS", LuaValue.valueOf(Terrain.FURROWED_GRASS));
        t.set("SECRET_DOOR", LuaValue.valueOf(Terrain.SECRET_DOOR));
        t.set("BARRICADE", LuaValue.valueOf(Terrain.BARRICADE));
        t.set("TRAP", LuaValue.valueOf(Terrain.TRAP));
        t.set("SECRET_TRAP", LuaValue.valueOf(Terrain.SECRET_TRAP));
        return t;
    }

    /** Resolve a Lua-passed char id to a live Char, or null (logged) if missing/wrong type. */
    private static Char resolveChar(LuaValue idVal) {
        if (!idVal.isint()) {
            Gdx.app.error(TAG, "expected int charId, got " + idVal.typename());
            return null;
        }
        Actor a = Actor.findById(idVal.toint());
        if (!(a instanceof Char)) {
            Gdx.app.error(TAG, "charId " + idVal.toint() + " is not a live Char");
            return null;
        }
        return (Char) a;
    }

    private static boolean validAmount(double amt) {
        return amt > 0 && amt <= MAX_AMOUNT && !Double.isNaN(amt);
    }

    // ---- functions ----

    /** {@code RPD.affectBuff(charId, buffName, amount)} — amount semantics are per-buff (duration or level). */
    private static final class AffectBuff extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue buffName, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                String name = buffName.optjstring("");
                BuffApplier applier = BuffWhitelist.lookup(name);
                if (applier != null) {
                    double amt = amount.isnumber() ? amount.todouble() : -1;
                    if (!validAmount(amt)) {
                        Gdx.app.error(TAG, "affectBuff rejected amount " + amt + " for " + name);
                        return NIL;
                    }
                    applier.apply(target, (float) amt);
                    return NIL;
                }
                // M6c: fall through to Lua buff registry (id-resolved). amount
                // is interpreted as a level for Lua buffs (Remished scripts use
                // buff:level(...)); a missing/invalid amount defaults to 1.
                if (LuaBuffRegistry.contains(name)) {
                    int lvl = amount.isnumber() ? Math.max(1, amount.toint()) : 1;
                    affectLuaBuff(target, name, lvl, 0f);
                } else {
                    Gdx.app.error(TAG, "affectBuff rejected non-whitelisted buff: " + name);
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "affectBuff threw", e);
            }
            return NIL;
        }
    }

    /**
     * Attach or refresh a Lua buff on {@code target}. Stacks by Lua id (not by
     * class — {@code target.buff(LuaBuff.class)} is class-exact and would mix
     * all Lua buffs). An existing instance is {@code refresh}ed in place; a new
     * one is created via the registry and attached.
     */
    private static void affectLuaBuff(Char target, String id, int level, float duration) {
        LuaBuff existing = findLuaBuff(target, id);
        if (existing != null) {
            existing.refresh(level, duration);
            return;
        }
        LuaBuff lb = LuaBuffRegistry.create(id);
        if (lb == null) {
            Gdx.app.error(TAG, "affectLuaBuff: registry create failed for " + id);
            return;
        }
        if (lb.attachTo(target)) {
            lb.refresh(level, duration);
        }
    }

    private static LuaBuff findLuaBuff(Char target, String id) {
        for (Buff b : target.buffs(LuaBuff.class)) {
            LuaBuff lb = (LuaBuff) b;
            if (lb.sameLuaId(id)) return lb;
        }
        return null;
    }

    /**
     * {@code RPD.removeBuff(charId, buffId)} — detach the named buff. For Java
     * whitelist ids, delegates to {@link Buff#detach(Char, Class)}; for Lua
     * buff ids, detaches the matching LuaBuff instance (id-resolved).
     */
    private static final class RemoveBuff extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue buffId) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                String name = buffId.optjstring("");
                Class<? extends Buff> clazz = BuffWhitelist.lookupClass(name);
                if (clazz != null) {
                    Buff.detach(target, clazz);
                    return NIL;
                }
                if (LuaBuffRegistry.contains(name)) {
                    for (Buff b : target.buffs(LuaBuff.class)) {
                        LuaBuff lb = (LuaBuff) b;
                        if (lb.sameLuaId(name)) lb.detach();
                    }
                } else {
                    Gdx.app.error(TAG, "removeBuff rejected unknown buff id: " + name);
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "removeBuff threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.permanentBuff(charId, buffId[, level])} — make a Lua buff
     * permanent (parked at MAX_VALUE so it never acts). Java whitelist buffs
     * are intentionally excluded: FlavourBuff/source-aware buffs have
     * time/level semantics that "permanent" would silently break. An already
     * attached Lua buff is upgraded in place; otherwise a fresh one is created.
     */
    private static final class PermanentBuff extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue buffId, LuaValue level) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                String name = buffId.optjstring("");
                if (!LuaBuffRegistry.contains(name)) {
                    Gdx.app.error(TAG, "permanentBuff only applies to Lua buffs; rejected " + name);
                    return NIL;
                }
                int lvl = level.isnumber() ? Math.max(1, level.toint()) : 1;
                LuaBuff existing = null;
                for (Buff b : target.buffs(LuaBuff.class)) {
                    LuaBuff lb = (LuaBuff) b;
                    if (lb.sameLuaId(name)) { existing = lb; break; }
                }
                if (existing == null) {
                    existing = LuaBuffRegistry.create(name);
                    if (existing == null) return NIL;
                    if (!existing.attachTo(target)) return NIL;
                }
                existing.refresh(lvl, 0f);
                existing.makePermanent();
            } catch (Exception e) {
                Gdx.app.error(TAG, "permanentBuff threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.setBuffLevel(charId, luaBuffId, level)} — set a LuaBuff's level.
     * Java whitelist buffs intentionally excluded because their level/count
     * semantics are per-class and not uniformly mutable.
     */
    private static final class SetBuffLevel extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue buffId, LuaValue level) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                String name = buffId.optjstring("");
                if (!LuaBuffRegistry.contains(name)) {
                    Gdx.app.error(TAG, "setBuffLevel only applies to Lua buffs; rejected " + name);
                    return NIL;
                }
                LuaBuff lb = findLuaBuff(target, name);
                if (lb == null) return NIL;
                if (!level.isnumber()) {
                    Gdx.app.error(TAG, "setBuffLevel expected number level for " + name);
                    return NIL;
                }
                lb.setLevel(level.toint());
            } catch (Exception e) {
                Gdx.app.error(TAG, "setBuffLevel threw", e);
            }
            return NIL;
        }
    }

    /** {@code RPD.buffLevel(charId, luaBuffId)} — return an attached LuaBuff's level or NIL. */
    private static final class BuffLevel extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue buffId) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                String name = buffId.optjstring("");
                LuaBuff lb = findLuaBuff(target, name);
                return lb == null ? NIL : LuaValue.valueOf(lb.level());
            } catch (Exception e) {
                Gdx.app.error(TAG, "buffLevel threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.damageChar(charId, amount)} — routes through Char.damage so shields/death/immunity still apply. */
    private static final class DamageChar extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "damageChar rejected amount " + amt);
                    return NIL;
                }
                target.damage((int) amt, LUA_SOURCE);
            } catch (Exception e) {
                Gdx.app.error(TAG, "damageChar threw", e);
            }
            return NIL;
        }
    }

    /** {@code RPD.healChar(charId, amount)} — positive only, clamped to [0, HT]. */
    private static final class HealChar extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "healChar rejected amount " + amt);
                    return NIL;
                }
                target.HP = Math.min(target.HT, target.HP + (int) amt);
            } catch (Exception e) {
                Gdx.app.error(TAG, "healChar threw", e);
            }
            return NIL;
        }
    }

    private static final class GLogI extends OneArgFunction {
        @Override public LuaValue call(LuaValue msg) { GLog.i(msg.optjstring("")); return NIL; }
    }
    private static final class GLogW extends OneArgFunction {
        @Override public LuaValue call(LuaValue msg) { GLog.w(msg.optjstring("")); return NIL; }
    }
    private static final class CharHP extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            Char c = resolveChar(charId);
            return c == null ? NIL : LuaValue.valueOf(c.HP);
        }
    }
    private static final class CharPos extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            Char c = resolveChar(charId);
            return c == null ? NIL : LuaValue.valueOf(c.pos);
        }
    }
    private static final class CharName extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            Char c = resolveChar(charId);
            return c == null ? NIL : LuaValue.valueOf(c.name());
        }
    }

    // ---- M7d mana API ----

    /** Resolve a char id to a Hero, or null if missing / not a Hero (mana is hero-only). */
    private static Hero resolveHero(LuaValue idVal) {
        Char c = resolveChar(idVal);
        return c instanceof Hero ? (Hero) c : null;
    }

    /** MP is an int pool — reject fractional/zero/negative amounts up front (round-1 #4). */
    private static boolean validManaAmount(LuaValue v) {
        return v.isint() && v.toint() >= 1 && v.toint() <= (int) MAX_AMOUNT;
    }

    /** {@code RPD.heroMana(heroId)} → current MP (NIL for non-Hero / missing). */
    private static final class HeroMana extends OneArgFunction {
        @Override public LuaValue call(LuaValue heroId) {
            Hero h = resolveHero(heroId);
            return h == null ? NIL : LuaValue.valueOf(h.MP);
        }
    }

    /** {@code RPD.heroManaMax(heroId)} → MP cap (NIL for non-Hero / missing). */
    private static final class HeroManaMax extends OneArgFunction {
        @Override public LuaValue call(LuaValue heroId) {
            Hero h = resolveHero(heroId);
            return h == null ? NIL : LuaValue.valueOf(h.MPMax);
        }
    }

    /** {@code RPD.spendMana(heroId, amt)} → true iff hero had at least amt (deducts). */
    private static final class SpendMana extends TwoArgFunction {
        @Override public LuaValue call(LuaValue heroId, LuaValue amount) {
            try {
                Hero h = resolveHero(heroId);
                if (h == null) return LuaValue.FALSE;
                if (!validManaAmount(amount)) {
                    Gdx.app.error(TAG, "spendMana rejected amount " + amount + " (need int 1.." + (int) MAX_AMOUNT + ")");
                    return LuaValue.FALSE;
                }
                int amt = amount.toint();
                if (h.MP < amt) return LuaValue.FALSE;
                h.MP -= amt;
                return LuaValue.TRUE;
            } catch (Exception e) {
                Gdx.app.error(TAG, "spendMana threw", e);
                return LuaValue.FALSE;
            }
        }
    }

    /** {@code RPD.restoreMana(heroId, amt)} → true iff hero gained mana (clamped to MPMax). */
    private static final class RestoreMana extends TwoArgFunction {
        @Override public LuaValue call(LuaValue heroId, LuaValue amount) {
            try {
                Hero h = resolveHero(heroId);
                if (h == null) return LuaValue.FALSE;
                if (!validManaAmount(amount)) {
                    Gdx.app.error(TAG, "restoreMana rejected amount " + amount + " (need int 1.." + (int) MAX_AMOUNT + ")");
                    return LuaValue.FALSE;
                }
                h.MP = Math.min(h.MPMax, h.MP + amount.toint());
                return LuaValue.TRUE;
            } catch (Exception e) {
                Gdx.app.error(TAG, "restoreMana threw", e);
                return LuaValue.FALSE;
            }
        }
    }

    // ---- M7b hook API: belongings read + char yell ----

    /**
     * {@code RPD.encumbranceItemName(heroId)} → name of the first equipped item
     * whose STR requirement exceeds the hero's effective STR, or NIL. Faithful
     * port of Remished {@code Belongings.encumbranceCheck()} (which iterates
     * equipped items and returns the first with {@code requiredSTR() > STR()}).
     * Hero-only: mobs have no {@link Belongings}. Only {@link Weapon} and
     * {@link Armor} expose a STR requirement in SPD, so those are the slots
     * checked (weapon, secondWep, armor).
     */
    private static final class EncumbranceItemName extends OneArgFunction {
        @Override public LuaValue call(LuaValue heroId) {
            try {
                Hero h = resolveHero(heroId, "encumbranceItemName");
                if (h == null) return NIL;
                int str = h.STR();
                Belongings b = h.belongings;
                if (b.weapon() instanceof Weapon
                        && ((Weapon) b.weapon()).STRReq() > str) {
                    return LuaValue.valueOf(b.weapon().name());
                }
                if (b.secondWep() instanceof Weapon
                        && ((Weapon) b.secondWep()).STRReq() > str) {
                    return LuaValue.valueOf(b.secondWep().name());
                }
                if (b.armor() != null && b.armor().STRReq() > str) {
                    return LuaValue.valueOf(b.armor().name());
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "encumbranceItemName threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.yell(charId, text)} — write a quoted GLog line from the char
     * ("Name: \"text\""). Mirrors {@link Mob#yell} but accepts any Char (so the
     * hero can yell too; {@code npcYell} is NPC-only). {@code Mob.yell} is used
     * directly for mobs to preserve its exact formatting; for non-Mob chars the
     * same format is emitted via {@link GLog#n}.
     */
    private static final class Yell extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue text) {
            try {
                Char c = resolveChar(charId);
                if (c == null) return NIL;
                String line = text.optjstring("");
                if (c instanceof Mob) {
                    ((Mob) c).yell(line);
                } else {
                    GLog.newLine();
                    GLog.n("%s: \"%s\" ", Messages.titleCase(c.name()), line);
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "yell threw", e);
            }
            return NIL;
        }
    }

    // ---- M8b shield API ----

    /**
     * {@code RPD.addShield(charId, amt)} — add points to the shared shield pool
     * ({@link ShieldTracker}) on {@code charId}. Used by shield buffs' attach
     * (declarative seed is done Java-side in {@link LuaBuff}, but a script can
     * add more — e.g. a recharge {@code act} callback). Returns NIL on bad
     * input; never throws.
     */
    private static final class AddShield extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                if (!validAmount(amount.isnumber() ? amount.todouble() : -1)) {
                    Gdx.app.error(TAG, "addShield rejected amount " + amount);
                    return NIL;
                }
                ShieldTracker.addShield(target, amount.toint());
            } catch (Exception e) {
                Gdx.app.error(TAG, "addShield threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.charShield(charId)} — current shield points on {@code charId}
     * (int), or NIL for a missing/wrong id. Lets a Lua defenseProc decide
     * whether to self-detach after an absorb.
     */
    private static final class CharShield extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                return LuaValue.valueOf(ShieldTracker.getShield(target));
            } catch (Exception e) {
                Gdx.app.error(TAG, "charShield threw", e);
                return NIL;
            }
        }
    }

    /**
     * {@code RPD.absorbShield(charId, dmg)} — drain up to {@code dmg} from the
     * shared shield pool on {@code charId}; returns the leftover damage (0 if
     * fully absorbed) for the Lua {@code defenseProc} to pass back. Rejects
     * non-positive / out-of-range damage (passes it through as-is is wrong here
     * — we return NIL so the caller can detect the misuse rather than silently
     * nullify a hit).
     */
    private static final class AbsorbShield extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue dmg) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                if (!validAmount(dmg.isnumber() ? dmg.todouble() : -1)) {
                    Gdx.app.error(TAG, "absorbShield rejected dmg " + dmg);
                    return NIL;
                }
                return LuaValue.valueOf(ShieldTracker.absorb(target, dmg.toint()));
            } catch (Exception e) {
                Gdx.app.error(TAG, "absorbShield threw", e);
                return NIL;
            }
        }
    }

    // ---- M3a spawnMob ----

    /**
     * {@code RPD.spawnMob(mobId, pos)} — inject a Lua-defined hostile mob at the
     * {@link GameScene#add(Mob)} level. This is the same registration point the
     * vanilla spawner reaches via {@code Level.spawnMob} (Level.java:756), but it
     * <b>does not</b> call {@code Level.createMob}, touch {@code mobsToSpawn}, or
     * go through {@code MobSpawner} — so the rotation pool and per-level spawn
     * balance are untouched (C3/C4). Lua mobs only appear when a script summons
     * them. Returns NIL on any bad input; logs but never throws.
     */
    private static final class SpawnMob extends TwoArgFunction {
        @Override public LuaValue call(LuaValue mobId, LuaValue pos) {
            try {
                if (!mobId.isstring()) {
                    Gdx.app.error(TAG, "spawnMob expected string mobId, got " + mobId.typename());
                    return NIL;
                }
                String id = mobId.checkjstring();
                if (!LuaMobRegistry.contains(id)) {
                    Gdx.app.error(TAG, "spawnMob: unknown mob id '" + id + "'");
                    return NIL;
                }
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "spawnMob expected int pos, got " + pos.typename());
                    return NIL;
                }
                LuaMob mob = LuaMobRegistry.create(id);
                if (mob == null) return NIL;
                mob.pos = pos.toint();
                // Note: GameScene.add handles Dungeon.level.mobs registration,
                // sprite setup, Actor.addDelayed and spendToWhole. It does NOT
                // route through Level.createMob — verified by grep (C3/C4).
                GameScene.add(mob);
            } catch (Exception e) {
                Gdx.app.error(TAG, "spawnMob threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.spawnAlly(allyId, pos)} — inject a Lua-defined friendly pet at
     * the {@link GameScene#add(Mob)} level, mirroring {@link SpawnMob}. Like the
     * hostile path, this <b>does not</b> call {@code Level.createMob}, touch
     * {@code mobsToSpawn}, or go through {@code MobSpawner} (C3/C4). Returns the
     * spawned ally's int char id on success (so Lua can later pass it to
     * {@code commandAlly}/{@code expelAlly}); NIL on any bad input.
     */
    private static final class SpawnAlly extends TwoArgFunction {
        @Override public LuaValue call(LuaValue allyId, LuaValue pos) {
            try {
                if (!allyId.isstring()) {
                    Gdx.app.error(TAG, "spawnAlly expected string allyId, got " + allyId.typename());
                    return NIL;
                }
                String id = allyId.checkjstring();
                if (!LuaAllyRegistry.contains(id)) {
                    Gdx.app.error(TAG, "spawnAlly: unknown ally id '" + id + "'");
                    return NIL;
                }
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "spawnAlly expected int pos, got " + pos.typename());
                    return NIL;
                }
                LuaAlly ally = LuaAllyRegistry.create(id);
                if (ally == null) return NIL;
                ally.pos = pos.toint();
                // Note: GameScene.add handles Dungeon.level.mobs registration,
                // sprite setup, Actor.addDelayed and spendToWhole. It does NOT
                // route through Level.createMob — verified by grep (C3/C4).
                GameScene.add(ally);
                return LuaValue.valueOf(ally.id());
            } catch (Exception e) {
                Gdx.app.error(TAG, "spawnAlly threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.commandAlly(allyCharId, cmd, targetId)} — dispatch the
     * follow/defend/attack state machine that {@link LuaAlly} inherits from
     * {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.DirectableAlly}.
     * {@code allyCharId} is the int char id returned by {@code spawnAlly} (NOT
     * the registry id — multiple allies of the same definition each need their
     * own handle). {@code cmd} is one of:
     * <ul>
     *   <li>{@code "follow"}  — ally.followHero(); targetId ignored.</li>
     *   <li>{@code "defend"}  — ally.defendPos(targetId); targetId is a cell int.</li>
     *   <li>{@code "attack"}  — ally.targetChar(enemy); targetId is an enemy char id.</li>
     * </ul>
     * After the Java dispatch the ally's Lua {@code onCommand(selfId, cmd, targetId)}
     * callback (if present) is invoked for feedback/effects — Lua does not take
     * over scheduling (same super-then-Lua split as the {@code act} hook).
     */
    private static final class CommandAlly extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue allyCharId, LuaValue cmd, LuaValue targetId) {
            try {
                LuaAlly ally = resolveLuaAlly(allyCharId);
                if (ally == null) return NIL;
                String command = cmd.optjstring("");
                int targetInt = targetId.isint() ? targetId.toint() : -1;
                switch (command) {
                    case "follow":
                        ally.followHero();
                        break;
                    case "defend":
                        ally.defendPos(targetInt);
                        break;
                    case "attack":
                        Char enemy = resolveChar(targetId);
                        if (enemy == null) {
                            Gdx.app.error(TAG, "commandAlly attack: targetId "
                                    + targetInt + " is not a live Char");
                            return NIL;
                        }
                        ally.targetChar(enemy);
                        break;
                    default:
                        Gdx.app.error(TAG, "commandAlly: unknown command '" + command + "'");
                        return NIL;
                }
                // Lua onCommand is advisory only (feedback/particles/GLog). It
                // runs AFTER the Java dispatch and cannot change ally state.
                LuaTable tbl = LuaAllyRegistry.getTable(ally.luaAllyId());
                if (tbl != null) {
                    LuaItemCallbacks.callOpt(tbl, "onCommand",
                            LuaValue.valueOf(ally.id()),
                            LuaValue.valueOf(command),
                            LuaValue.valueOf(targetInt));
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "commandAlly threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.expelAlly(allyCharId)} — remove a summoned ally. Mirrors the
     * {@link com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany.LightAlly}
     * release path (die(null) → normal Mob/Char cleanup: loot skip for ALLY
     * alignment, sprite death, Actor/level.mobs removal). {@code die(null)} is
     * safe because {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob#die}
     * only special-cases {@code cause == Chasm.class} / hero/weapon instances,
     * none of which match null.
     */
    private static final class ExpelAlly extends OneArgFunction {
        @Override public LuaValue call(LuaValue allyCharId) {
            try {
                LuaAlly ally = resolveLuaAlly(allyCharId);
                if (ally == null) return NIL;
                ally.die(null);
            } catch (Exception e) {
                Gdx.app.error(TAG, "expelAlly threw", e);
            }
            return NIL;
        }
    }

    /** Resolve a Lua-passed char id to a live {@link LuaAlly}, or null (logged) if missing/wrong type. */
    private static LuaAlly resolveLuaAlly(LuaValue idVal) {
        if (!idVal.isint()) {
            Gdx.app.error(TAG, "expected int allyCharId, got " + idVal.typename());
            return null;
        }
        Actor a = Actor.findById(idVal.toint());
        if (!(a instanceof LuaAlly)) {
            Gdx.app.error(TAG, "charId " + idVal.toint() + " is not a live LuaAlly");
            return null;
        }
        return (LuaAlly) a;
    }

    /**
     * {@code RPD.npcYell(charId, text)} — write an NPC's line to the GLog via
     * {@link Mob#yell}. NPC-only by intent ({@code npcYell} rejects any non-NPC
     * Char such as the hero or a hostile mob, so a script cannot spoof a yell
     * from the wrong speaker). {@code yell} only formats a GLog entry, so it is
     * safe to call directly from the actor thread that fires {@code onInteract}.
     */
    private static final class NpcYell extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue text) {
            try {
                Char c = resolveChar(charId);
                if (c == null) return NIL;
                if (!(c instanceof NPC)) {
                    Gdx.app.error(TAG, "npcYell rejected non-NPC charId " + charId.toint());
                    return NIL;
                }
                // Mob.yell lives on Mob; NPC inherits it. Cast safe.
                ((Mob) c).yell(text.optjstring(""));
            } catch (Exception e) {
                Gdx.app.error(TAG, "npcYell threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.showDialog(charId, text)} — open a {@link WndMessage} showing
     * {@code text}. The {@code charId} is validated as a live Char (anchor only —
     * any valid char id is accepted so a script can open a dialog in any
     * onInteract context) but is not otherwise used: WndMessage has no per-NPC
     * styling in MVP. Wrapped in {@link Game#runOnRenderThread} because
     * {@code onInteract} fires on the actor thread and {@link GameScene#show}
     * must touch the scene graph from the render thread.
     */
    private static final class ShowDialog extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue text) {
            try {
                Char c = resolveChar(charId);
                if (c == null) return NIL;
                String body = text.optjstring("");
                Game.runOnRenderThread(() -> GameScene.show(new WndMessage(body)));
            } catch (Exception e) {
                Gdx.app.error(TAG, "showDialog threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.enterTown(levelId)} — swap the live level for the named JSON level
     * (M4d). Delegates to {@link LuaLevelService#enterLevel} on the render thread:
     * {@code onInteract} fires on the actor thread, but the level build + scene
     * switch must run on render. The {@code levelId} must match a
     * {@code mods/levels/<id>.json} asset. {@link LuaLevelService#enterLevel} is
     * debug-gated and validates hero/level-state itself; this wrapper only
     * forwards. Silently logs + returns NIL on a non-string id (never throws).
     */
    private static final class EnterTown extends OneArgFunction {
        @Override public LuaValue call(LuaValue levelId) {
            try {
                if (!levelId.isstring()) {
                    Gdx.app.error(TAG, "enterTown expected string levelId, got " + levelId.typename());
                    return NIL;
                }
                String id = levelId.checkjstring();
                Game.runOnRenderThread(() -> LuaLevelService.enterLevel(id));
            } catch (Exception e) {
                Gdx.app.error(TAG, "enterTown threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.leaveTown()} — leave the JSON level and return to the main run,
     * preserving hero in-memory state (M4d, R4). Delegates to
     * {@link LuaLevelService#leaveLevel} on the render thread. Any argument is
     * ignored (Lua {@code RPD.leaveTown()} is conventionally nil-arg; tolerating
     * a stray arg costs nothing and avoids a hard failure if a scripter writes
     * {@code RPD.leaveTown(nil)}).
     */
    private static final class LeaveTown extends OneArgFunction {
        @Override public LuaValue call(LuaValue ignored) {
            try {
                Game.runOnRenderThread(LuaLevelService::leaveLevel);
            } catch (Exception e) {
                Gdx.app.error(TAG, "leaveTown threw", e);
            }
            return NIL;
        }
    }

    // ---- M6a: blobs + immunity ----

    /**
     * {@code RPD.Blobs} constant table — maps stable Lua names to themselves
     * (the internal whitelist key). The indirection means internals can be
     * renamed without breaking scripts; Lua never receives a Class handle
     * (D5'-(a): luajava stays disabled). The set of ids is exactly
     * {@link BlobRegistry#lookup}'s keys.
     */
    private static LuaTable blobConstants() {
        LuaTable t = new LuaTable();
        for (String id : BlobRegistry.ids()) t.set(id, LuaValue.valueOf(id));
        return t;
    }

    /**
     * {@code RPD.Buffs} — same pattern as {@link #blobConstants()} for the buff
     * whitelist ({@link BuffWhitelist}). Each value is the simple class name that
     * {@code affectBuff} already accepts, so {@code RPD.affectBuff(charId,
     * RPD.Buffs.Poison, amt)} works without changing affectBuff's signature.
     */
    private static LuaTable buffConstants() {
        LuaTable t = new LuaTable();
        for (String id : BuffWhitelist.ids()) t.set(id, LuaValue.valueOf(id));
        return t;
    }

    /**
     * {@code RPD.placeBlob(blobId, pos, amount)} — seed a whitelisted
     * {@link Blob} (gas/fire/web/...) at {@code pos}, mirroring the canonical
     * vanilla idiom {@code GameScene.add(Blob.seed(cell, amount, TypeClass))}
     * (DM200/FetidRat/Elemental). {@code blobId} is resolved through
     * {@link BlobRegistry} — only registered blobs can be placed, so a script
     * cannot summon an arbitrary/unbalanced blob. Guards headless (no
     * {@code Dungeon.level}) and out-of-map cells so it never throws; logs +
     * returns NIL on any bad input.
     */
    private static final class PlaceBlob extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue blobId, LuaValue pos, LuaValue amount) {
            try {
                String id = blobId.optjstring("");
                Class<? extends Blob> clazz = BlobRegistry.lookup(id);
                if (clazz == null) {
                    Gdx.app.error(TAG, "placeBlob rejected unknown blob id: " + id);
                    return NIL;
                }
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "placeBlob expected int pos, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "placeBlob rejected amount " + amt + " for " + id);
                    return NIL;
                }
                if (Dungeon.level == null) {
                    // headless / pre-scene: no-op, never throw (PLAN step 4).
                    Gdx.app.error(TAG, "placeBlob no-op: Dungeon.level is null");
                    return NIL;
                }
                if (!Dungeon.level.insideMap(cell)) {
                    // out-of-map cell would let Blob.seed's cur[cell] += amount
                    // throw and leave a half-initialised blob in level.blobs.
                    Gdx.app.error(TAG, "placeBlob rejected out-of-map cell " + cell);
                    return NIL;
                }
                GameScene.add(Blob.seed(cell, (int) amt, clazz));
            } catch (Exception e) {
                Gdx.app.error(TAG, "placeBlob threw", e);
            }
            return NIL;
        }
    }

    /**
     * {@code RPD.addImmunity(charId, id)} — register a whitelisted Class in a
     * {@link LuaMob}'s {@code immunities} so the mob is unaffected by the named
     * blob/buff (the FetidRat pattern: a gas-emitting mob must be immune to its
     * own gas). {@code id} is resolved first against {@link BlobRegistry} then
     * {@link BuffWhitelist#lookupClass}; non-LuaMob targets are rejected so a
     * script can't mutate vanilla actors' hardcoded immunities. Persisted across
     * save/load by {@link LuaMob} (it records each Class's FQCN).
     */
    private static final class AddImmunity extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue idVal) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                if (!(target instanceof LuaMob)) {
                    Gdx.app.error(TAG, "addImmunity only applies to LuaMob; rejected "
                            + target.getClass().getSimpleName());
                    return NIL;
                }
                String id = idVal.optjstring("");
                Class<?> type = BlobRegistry.lookup(id);
                if (type == null) type = BuffWhitelist.lookupClass(id);
                if (type == null) {
                    Gdx.app.error(TAG, "addImmunity rejected unknown id: " + id);
                    return NIL;
                }
                ((LuaMob) target).addLuaImmunity(id, type);
            } catch (Exception e) {
                Gdx.app.error(TAG, "addImmunity threw", e);
            }
            return NIL;
        }
    }

    // ---- M6b: AI + positioning helpers ----

    private static LuaMob resolveLuaMob(LuaValue idVal, String fnName) {
        Char c = resolveChar(idVal);
        if (c == null) return null;
        if (!(c instanceof LuaMob)) {
            Gdx.app.error(TAG, fnName + " only applies to LuaMob; rejected "
                    + c.getClass().getSimpleName());
            return null;
        }
        return (LuaMob) c;
    }

    private static final class SetMobAi extends TwoArgFunction {
        @Override public LuaValue call(LuaValue mobId, LuaValue aiTag) {
            try {
                LuaMob mob = resolveLuaMob(mobId, "setMobAi");
                if (mob == null) return NIL;
                String tag = aiTag.optjstring("");
                if (!mob.setAiTag(tag)) {
                    Gdx.app.error(TAG, "setMobAi rejected unknown ai tag: " + tag);
                }
            } catch (Exception e) {
                Gdx.app.error(TAG, "setMobAi threw", e);
            }
            return NIL;
        }
    }

    private static final class EnemyOf extends OneArgFunction {
        @Override public LuaValue call(LuaValue mobId) {
            try {
                LuaMob mob = resolveLuaMob(mobId, "enemyOf");
                if (mob == null) return NIL;
                Char enemy = mob.chooseAndRememberEnemy();
                return enemy == null ? NIL : LuaValue.valueOf(enemy.id());
            } catch (Exception e) {
                Gdx.app.error(TAG, "enemyOf threw", e);
                return NIL;
            }
        }
    }

    private static final class CellDistance extends TwoArgFunction {
        @Override public LuaValue call(LuaValue posA, LuaValue posB) {
            try {
                if (Dungeon.level == null) {
                    Gdx.app.error(TAG, "cellDistance no-op: Dungeon.level is null");
                    return NIL;
                }
                if (!posA.isint() || !posB.isint()) {
                    Gdx.app.error(TAG, "cellDistance expected int positions");
                    return NIL;
                }
                int a = posA.toint();
                int b = posB.toint();
                if (!Dungeon.level.insideMap(a) || !Dungeon.level.insideMap(b)) {
                    Gdx.app.error(TAG, "cellDistance rejected out-of-map positions " + a + ", " + b);
                    return NIL;
                }
                return LuaValue.valueOf(Dungeon.level.distance(a, b));
            } catch (Exception e) {
                Gdx.app.error(TAG, "cellDistance threw", e);
                return NIL;
            }
        }
    }

    private static final class EmptyCellNextTo extends OneArgFunction {
        @Override public LuaValue call(LuaValue pos) {
            try {
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "emptyCellNextTo expected int pos, got " + pos.typename());
                    return NIL;
                }
                int cell = LuaMob.findEmptyNextTo(pos.toint());
                return cell < 0 ? NIL : LuaValue.valueOf(cell);
            } catch (Exception e) {
                Gdx.app.error(TAG, "emptyCellNextTo threw", e);
                return NIL;
            }
        }
    }

    private static final class Blink extends TwoArgFunction {
        @Override public LuaValue call(LuaValue mobId, LuaValue pos) {
            try {
                LuaMob mob = resolveLuaMob(mobId, "blink");
                if (mob == null) return NIL;
                if (Dungeon.level == null) {
                    Gdx.app.error(TAG, "blink no-op: Dungeon.level is null");
                    return NIL;
                }
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "blink expected int pos, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!Dungeon.level.insideMap(cell)) {
                    Gdx.app.error(TAG, "blink rejected out-of-map cell " + cell);
                    return NIL;
                }
                if (!Dungeon.level.passable[cell] || Actor.findChar(cell) != null) {
                    Gdx.app.error(TAG, "blink rejected blocked cell " + cell);
                    return NIL;
                }
                ScrollOfTeleportation.appear(mob, cell);
                Dungeon.level.occupyCell(mob);
            } catch (Exception e) {
                Gdx.app.error(TAG, "blink threw", e);
            }
            return NIL;
        }
    }

    /**
     * The placeable-blob whitelist. Maps a Lua-facing simple class name to the
     * Blob subclass; names not in this map are rejected by {@link PlaceBlob}.
     * Fourteen common gameplay blobs (gas/fire/web/electricity/...); a script
     * cannot reach arbitrary Blob subclasses (e.g. quest-only blobs).
     */
    static final class BlobRegistry {
        private static final java.util.Map<String, Class<? extends Blob>> ENTRIES = new java.util.LinkedHashMap<>();
        static {
            ENTRIES.put("ToxicGas", ToxicGas.class);
            ENTRIES.put("ParalyticGas", ParalyticGas.class);
            ENTRIES.put("ConfusionGas", ConfusionGas.class);
            ENTRIES.put("StenchGas", StenchGas.class);
            ENTRIES.put("CorrosiveGas", CorrosiveGas.class);
            ENTRIES.put("Fire", Fire.class);
            ENTRIES.put("Web", Web.class);
            ENTRIES.put("Freezing", Freezing.class);
            ENTRIES.put("Blizzard", Blizzard.class);
            ENTRIES.put("Inferno", Inferno.class);
            ENTRIES.put("Regrowth", Regrowth.class);
            ENTRIES.put("SmokeScreen", SmokeScreen.class);
            ENTRIES.put("StormCloud", StormCloud.class);
            ENTRIES.put("Electricity", Electricity.class);
        }

        static Class<? extends Blob> lookup(String simpleName) {
            return ENTRIES.get(simpleName);
        }

        static java.util.Set<String> ids() {
            return ENTRIES.keySet();
        }

        private BlobRegistry() { }
    }

    /**
     * The buff whitelist. Each entry maps a Lua-facing simple class name to the
     * correct application strategy for that buff type. Names not in this map are
     * rejected by {@link AffectBuff} — this is what stops a script from injecting
     * an invulnerability/hero-clone buff.
     *
     * <p>FlavourBuff subclasses share the {@link Buff#prolong} strategy; the
     * level-based debuffs (Bleeding/Poison/Barkskin) use their public setters.
     */
    static final class BuffWhitelist {
        private static final java.util.Map<String, BuffApplier> ENTRIES = new java.util.HashMap<>();
        // Parallel id→Class map for addImmunity (resolve a Lua buff id to its
        // Class without an extra applier). Kept in insertion order so
        // buffConstants() emits a stable Lua table.
        private static final java.util.Map<String, Class<? extends Buff>> BUFF_CLASSES = new java.util.LinkedHashMap<>();
        static {
            // FlavourBuff family: prolong(target, clazz, duration).
            putFlavour("Roots", Roots.class);
            putFlavour("Slow", Slow.class);
            putFlavour("Cripple", Cripple.class);
            putFlavour("Paralysis", Paralysis.class);
            putFlavour("Vertigo", Vertigo.class);
            putFlavour("Haste", Haste.class);
            // M6c: extra FlavourBuff entries referenced by Remished buff scripts.
            // All use the same prolong(target,clazz,duration) strategy, so the
            // existing putFlavour helper covers them without per-buff code.
            putFlavour("Invisibility", Invisibility.class);
            putFlavour("Levitation", Levitation.class);
            putFlavour("Light", Light.class);
            putFlavour("Bless", Bless.class);
            putFlavour("Speed", Speed.class);
            putFlavour("Sleep", Sleep.class);
            putFlavour("Drowsy", Drowsy.class);
            putFlavour("Blindness", Blindness.class);
            putFlavour("Weakness", Weakness.class);
            putFlavour("Vulnerable", Vulnerable.class);
            putFlavour("Chill", Chill.class);
            // M7a: Charm/Terror are FlavourBuff subclasses, covered by the same
            // prolong(target,clazz,duration) strategy as the M6c flavour entries.
            putFlavour("Charm", Charm.class);
            putFlavour("Terror", Terror.class);
            // Level-based buffs with type-specific setters.
            ENTRIES.put("Bleeding", (t, amt) -> {
                Bleeding b = Buff.affect(t, Bleeding.class);
                b.set(amt);
            });
            BUFF_CLASSES.put("Bleeding", Bleeding.class);
            ENTRIES.put("Poison", (t, amt) -> {
                Poison b = Buff.affect(t, Poison.class);
                b.set(amt);
            });
            BUFF_CLASSES.put("Poison", Poison.class);
            ENTRIES.put("Ooze", (t, amt) -> {
                Ooze b = Buff.affect(t, Ooze.class);
                b.set(amt);
            });
            BUFF_CLASSES.put("Ooze", Ooze.class);
            ENTRIES.put("Barkskin", (t, amt) -> {
                Barkskin b = Buff.affect(t, Barkskin.class);
                int v = Math.max(1, (int) amt);
                b.set(v, v);
            });
            BUFF_CLASSES.put("Barkskin", Barkskin.class);
            // Frost has no public set(float); left out (script can use the
            // FlavourBuff-family Chill instead, or a Lua buff). Documented in
            // PLAN §Pending Issues as a degraded Java mapping.
            // Burning is applied via reignite(target, duration).
            ENTRIES.put("Burning", (t, amt) -> {
                com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning b =
                        Buff.affect(t, com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning.class);
                b.reignite(t, amt);
            });
            BUFF_CLASSES.put("Burning",
                    com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning.class);
        }

        private static <T extends FlavourBuff> void putFlavour(String name, Class<T> clazz) {
            ENTRIES.put(name, (t, amt) -> Buff.prolong(t, clazz, amt));
            BUFF_CLASSES.put(name, clazz);
        }

        static BuffApplier lookup(String simpleName) {
            return ENTRIES.get(simpleName);
        }

        /** Resolve a Lua buff id to its Class for {@link AddImmunity}, or null. */
        static Class<? extends Buff> lookupClass(String simpleName) {
            return BUFF_CLASSES.get(simpleName);
        }

        static java.util.Set<String> ids() {
            return BUFF_CLASSES.keySet();
        }

        private BuffWhitelist() { }
    }

    // ---- M6d item/spell API ----

    private static Hero resolveHero(LuaValue idVal, String fnName) {
        Char c = resolveChar(idVal);
        if (c == null) return null;
        if (!(c instanceof Hero)) {
            Gdx.app.error(TAG, fnName + " only applies to the hero; rejected "
                    + c.getClass().getSimpleName());
            return null;
        }
        return (Hero) c;
    }

    private static boolean validQty(double qty) {
        return qty > 0 && qty <= MAX_AMOUNT && !Double.isNaN(qty);
    }

    private static int qtyOf(LuaValue v) {
        double q = v.isnumber() ? v.todouble() : -1;
        return validQty(q) ? (int) q : -1;
    }

    /** {@code RPD.giveItem(charId, itemId, qty)} — create + collect into hero backpack. Returns bool. */
    private static final class GiveItem extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue itemId, LuaValue qty) {
            try {
                Hero hero = resolveHero(charId, "giveItem");
                if (hero == null) return NIL;
                if (!itemId.isstring()) {
                    Gdx.app.error(TAG, "giveItem expected string itemId, got " + itemId.typename());
                    return NIL;
                }
                int n = qtyOf(qty);
                if (n < 0) {
                    Gdx.app.error(TAG, "giveItem rejected qty " + qty);
                    return NIL;
                }
                if (!giveQuotaAllows(hero.id(), n)) {
                    Gdx.app.error(TAG, "giveItem quota exceeded for hero " + hero.id()
                            + " at depth " + Dungeon.depth
                            + " (cap " + GIVE_ITEM_CAP_PER_DEPTH + "/depth)");
                    return LuaValue.valueOf(false);
                }
                Item item = LuaItemRegistry.createItem(itemId.checkjstring());
                if (item == null) {
                    Gdx.app.error(TAG, "giveItem: unknown item id '" + itemId.tojstring() + "'");
                    return NIL;
                }
                item.quantity(n);
                boolean ok = item.collect(hero.belongings.backpack);
                if (ok) recordGive(hero.id(), n);
                return LuaValue.valueOf(ok);
            } catch (Exception e) {
                Gdx.app.error(TAG, "giveItem threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.randomBackpackItem(charId)} — uniform 1-based index of a backpack item, or nil. */
    private static final class RandomBackpackItem extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            try {
                Hero hero = resolveHero(charId, "randomBackpackItem");
                if (hero == null) return NIL;
                java.util.List<Item> items = hero.belongings.backpack.items;
                if (items.isEmpty()) return NIL;
                int idx = Random.Int(items.size());
                return LuaValue.valueOf(idx + 1);
            } catch (Exception e) {
                Gdx.app.error(TAG, "randomBackpackItem threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.itemName(charId, index)} — name of the backpack item at 1-based index, or nil. */
    private static final class ItemName extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue index) {
            try {
                Hero hero = resolveHero(charId, "itemName");
                if (hero == null) return NIL;
                if (!index.isint()) {
                    Gdx.app.error(TAG, "itemName expected int index, got " + index.typename());
                    return NIL;
                }
                java.util.List<Item> items = hero.belongings.backpack.items;
                int i = index.toint() - 1;
                if (i < 0 || i >= items.size()) return NIL;
                return LuaValue.valueOf(items.get(i).name());
            } catch (Exception e) {
                Gdx.app.error(TAG, "itemName threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.removeBackpackItem(charId, index, qty)} — detach qty from item at 1-based index. Returns bool. */
    private static final class RemoveBackpackItem extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue index, LuaValue qty) {
            try {
                Hero hero = resolveHero(charId, "removeBackpackItem");
                if (hero == null) return NIL;
                if (!index.isint()) {
                    Gdx.app.error(TAG, "removeBackpackItem expected int index, got " + index.typename());
                    return NIL;
                }
                int n = qtyOf(qty);
                if (n < 0) {
                    Gdx.app.error(TAG, "removeBackpackItem rejected qty " + qty);
                    return NIL;
                }
                java.util.List<Item> items = hero.belongings.backpack.items;
                int i = index.toint() - 1;
                if (i < 0 || i >= items.size()) return NIL;
                Item item = items.get(i);
                for (int k = 0; k < n; k++) {
                    if (item.quantity() <= 0) break;
                    item.detach(hero.belongings.backpack);
                }
                return LuaValue.valueOf(true);
            } catch (Exception e) {
                Gdx.app.error(TAG, "removeBackpackItem threw", e);
                return NIL;
            }
        }
    }

    /**
     * {@code RPD.stealRandomItem(mobId, targetHeroId)} — detach one random backpack
     * item from the hero and stash it on the mob's {@code loot} field so
     * {@link Mob#createLoot()} drops it on death. Returns the stolen item name or
     * nil. The Java side holds the {@link Item}; Lua never sees the object.
     */
    private static final class StealRandomItem extends TwoArgFunction {
        @Override public LuaValue call(LuaValue mobId, LuaValue targetHeroId) {
            try {
                LuaMob mob = resolveLuaMob(mobId, "stealRandomItem");
                if (mob == null) return NIL;
                Hero hero = resolveHero(targetHeroId, "stealRandomItem");
                if (hero == null) return NIL;
                java.util.List<Item> items = hero.belongings.backpack.items;
                if (items.isEmpty()) return NIL;
                Item stolen = items.get(Random.Int(items.size())).detach(hero.belongings.backpack);
                if (stolen == null) return NIL;
                mob.stolenLoot(stolen);
                return LuaValue.valueOf(stolen.name());
            } catch (Exception e) {
                Gdx.app.error(TAG, "stealRandomItem threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.stolenLootName(mobId)} — name of the item currently held as the mob's loot, or nil. */
    private static final class StolenLootName extends OneArgFunction {
        @Override public LuaValue call(LuaValue mobId) {
            try {
                LuaMob mob = resolveLuaMob(mobId, "stolenLootName");
                if (mob == null) return NIL;
                Item loot = mob.stolenLoot();
                return loot == null ? NIL : LuaValue.valueOf(loot.name());
            } catch (Exception e) {
                Gdx.app.error(TAG, "stolenLootName threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.teleportChar(charId, pos)} — ScrollOfTeleportation.appear; guards level/blocked cell. */
    private static final class TeleportChar extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue pos) {
            try {
                Char c = resolveChar(charId);
                if (c == null) return NIL;
                if (Dungeon.level == null) {
                    Gdx.app.error(TAG, "teleportChar no-op: Dungeon.level is null");
                    return NIL;
                }
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "teleportChar expected int pos, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!Dungeon.level.insideMap(cell) || !Dungeon.level.passable[cell]) {
                    Gdx.app.error(TAG, "teleportChar rejected cell " + cell);
                    return NIL;
                }
                ScrollOfTeleportation.appear(c, cell);
                return LuaValue.valueOf(true);
            } catch (Exception e) {
                Gdx.app.error(TAG, "teleportChar threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.charAtCell(cell)} — live Char id at a cell, or nil. */
    private static final class CharAtCell extends OneArgFunction {
        @Override public LuaValue call(LuaValue cellVal) {
            try {
                if (!cellVal.isint()) {
                    Gdx.app.error(TAG, "charAtCell expected int cell, got " + cellVal.typename());
                    return NIL;
                }
                Char c = Actor.findChar(cellVal.toint());
                return c == null ? NIL : LuaValue.valueOf(c.id());
            } catch (Exception e) {
                Gdx.app.error(TAG, "charAtCell threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.cellRay(fromCell, toCell)} — int array of Ballistica PROJECTILE path cells, or nil. */
    private static final class CellRay extends TwoArgFunction {
        @Override public LuaValue call(LuaValue fromCell, LuaValue toCell) {
            try {
                if (Dungeon.level == null) {
                    Gdx.app.error(TAG, "cellRay no-op: Dungeon.level is null");
                    return NIL;
                }
                if (!fromCell.isint() || !toCell.isint()) {
                    Gdx.app.error(TAG, "cellRay expected int cells");
                    return NIL;
                }
                int a = fromCell.toint();
                int b = toCell.toint();
                if (!Dungeon.level.insideMap(a) || !Dungeon.level.insideMap(b)) {
                    Gdx.app.error(TAG, "cellRay rejected out-of-map cells " + a + ", " + b);
                    return NIL;
                }
                Ballistica ray = new Ballistica(a, b, Ballistica.PROJECTILE);
                LuaTable out = new LuaTable();
                int i = 1;
                for (int cell : ray.subPath(0, ray.dist)) {
                    out.set(i++, LuaValue.valueOf(cell));
                }
                return out;
            } catch (Exception e) {
                Gdx.app.error(TAG, "cellRay threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.zapEffect(fromCell, toCell)} — visual/logging hook placeholder; returns bool if cells are valid. */
    private static final class ZapEffect extends TwoArgFunction {
        @Override public LuaValue call(LuaValue fromCell, LuaValue toCell) {
            try {
                if (Dungeon.level == null) {
                    Gdx.app.error(TAG, "zapEffect no-op: Dungeon.level is null");
                    return NIL;
                }
                if (!fromCell.isint() || !toCell.isint()) {
                    Gdx.app.error(TAG, "zapEffect expected int cells");
                    return NIL;
                }
                int a = fromCell.toint();
                int b = toCell.toint();
                if (!Dungeon.level.insideMap(a) || !Dungeon.level.insideMap(b)) {
                    Gdx.app.error(TAG, "zapEffect rejected out-of-map cells " + a + ", " + b);
                    return NIL;
                }
                return LuaValue.valueOf(true);
            } catch (Exception e) {
                Gdx.app.error(TAG, "zapEffect threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.spawnMobNear(mobId, centerCell)} — spawn a LuaMob at an empty neighbour cell. Returns char id or nil. */
    private static final class SpawnMobNear extends TwoArgFunction {
        @Override public LuaValue call(LuaValue mobId, LuaValue centerCell) {
            try {
                if (!mobId.isstring()) {
                    Gdx.app.error(TAG, "spawnMobNear expected string mobId, got " + mobId.typename());
                    return NIL;
                }
                String id = mobId.checkjstring();
                if (!LuaMobRegistry.contains(id)) {
                    Gdx.app.error(TAG, "spawnMobNear: unknown mob id '" + id + "'");
                    return NIL;
                }
                if (!centerCell.isint()) {
                    Gdx.app.error(TAG, "spawnMobNear expected int cell, got " + centerCell.typename());
                    return NIL;
                }
                int cell = LuaMob.findEmptyNextTo(centerCell.toint());
                if (cell < 0) {
                    Gdx.app.error(TAG, "spawnMobNear: no empty cell near " + centerCell.toint());
                    return NIL;
                }
                LuaMob mob = LuaMobRegistry.create(id);
                if (mob == null) return NIL;
                mob.pos = cell;
                GameScene.add(mob);
                return LuaValue.valueOf(mob.id());
            } catch (Exception e) {
                Gdx.app.error(TAG, "spawnMobNear threw", e);
                return NIL;
            }
        }
    }

    // ---- M11c terrain API ----

    private static boolean levelOk(int cell) {
        return Dungeon.level != null && Dungeon.level.insideMap(cell);
    }

    /**
     * True only when a real {@link GameScene} is live. The terrain/drop APIs are
     * exercised by headless tests (which set {@code Dungeon.level} but never
     * build a scene), so every live-only visual/FOV refresh must be gated on this
     * to stay headless-safe while still updating the tilemap/sprites on device.
     */
    private static boolean hasLiveScene() {
        return Game.instance != null && (Game.scene() instanceof GameScene);
    }

    /** Live-only: repaint one tile on the tilemap after a terrain change. */
    private static void refreshMapVisuals(int cell) {
        if (hasLiveScene()) GameScene.updateMap(cell);
    }

    /** {@code RPD.terrain(cell)} → terrain id at cell, or nil. */
    private static final class TerrainAt extends OneArgFunction {
        @Override public LuaValue call(LuaValue pos) {
            try {
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "terrain expected int cell, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!levelOk(cell)) return NIL;
                return LuaValue.valueOf(Dungeon.level.map[cell]);
            } catch (Exception e) {
                Gdx.app.error(TAG, "terrain threw", e);
                return NIL;
            }
        }
    }

    /**
     * {@code RPD.setTerrain(cell, terrainId)} → set cell to a whitelisted safe
     * terrain and update flags. Both the destination AND the current (source)
     * terrain must be in the safe decorative set — this stops a script from
     * erasing entrances/exits/traps/locked doors by repainting them to EMPTY.
     */
    private static final class SetTerrain extends TwoArgFunction {
        // M11c: only allow the same safe decorative targets as LuaPainterAdapter.
        private static final java.util.Set<Integer> TARGET_WHITELIST =
                new java.util.HashSet<>(java.util.Arrays.asList(
                        Terrain.EMPTY, Terrain.EMPTY_DECO, Terrain.EMPTY_SP, Terrain.EMBERS));
        // Source must already be a repaintable floor/decorative tile; structural
        // tiles (walls/doors/water/traps/transitions) are protected from erasure.
        private static final java.util.Set<Integer> SOURCE_WHITELIST =
                new java.util.HashSet<>(java.util.Arrays.asList(
                        Terrain.EMPTY, Terrain.EMPTY_DECO, Terrain.EMPTY_SP, Terrain.EMBERS,
                        Terrain.GRASS, Terrain.HIGH_GRASS, Terrain.FURROWED_GRASS));

        @Override public LuaValue call(LuaValue pos, LuaValue terrainId) {
            try {
                if (!pos.isint() || !terrainId.isint()) {
                    Gdx.app.error(TAG, "setTerrain expected int cell and terrain id");
                    return NIL;
                }
                int cell = pos.toint();
                int terr = terrainId.toint();
                if (!levelOk(cell)) return NIL;
                if (!TARGET_WHITELIST.contains(terr)) {
                    Gdx.app.error(TAG, "setTerrain rejected non-whitelisted target " + terr);
                    return NIL;
                }
                int src = Dungeon.level.map[cell];
                if (!SOURCE_WHITELIST.contains(src)) {
                    Gdx.app.error(TAG, "setTerrain rejected protected source terrain " + src + " at " + cell);
                    return NIL;
                }
                Level.set(cell, terr);
                refreshMapVisuals(cell);
                return LuaValue.valueOf(true);
            } catch (Exception e) {
                Gdx.app.error(TAG, "setTerrain threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.levelWidth()} → current level width (int), or nil if no level. Used by item scripts to compute neighbour offsets width-agnostically. */
    private static final class LevelWidth extends ZeroArgFunction {
        @Override public LuaValue call() {
            if (Dungeon.level == null) return NIL;
            return LuaValue.valueOf(Dungeon.level.width());
        }
    }

    /** {@code RPD.isWall(cell)} → true iff terrain at cell is WALL or WALL_DECO. */
    private static final class IsWall extends OneArgFunction {
        @Override public LuaValue call(LuaValue pos) {
            try {
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "isWall expected int cell, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!levelOk(cell)) return NIL;
                int t = Dungeon.level.map[cell];
                return LuaValue.valueOf(t == Terrain.WALL || t == Terrain.WALL_DECO);
            } catch (Exception e) {
                Gdx.app.error(TAG, "isWall threw", e);
                return NIL;
            }
        }
    }

    /** {@code RPD.isSolid(cell)} → true iff SOLID flag is set at cell. */
    private static final class IsSolid extends OneArgFunction {
        @Override public LuaValue call(LuaValue pos) {
            try {
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "isSolid expected int cell, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!levelOk(cell)) return NIL;
                return LuaValue.valueOf(Dungeon.level.solid[cell]);
            } catch (Exception e) {
                Gdx.app.error(TAG, "isSolid threw", e);
                return NIL;
            }
        }
    }

    /**
     * {@code RPD.dig(cell)} — break destructible terrain (WALL/WALL_DECO/DOOR/
     * BARRICADE/SECRET_DOOR) into EMPTY (flammable terrain becomes EMBERS).
     * Returns true if something was dug. Drops are intentionally left to the
     * caller via {@code RPD.dropItem} so item scripts control the loot table.
     */
    private static final class Dig extends OneArgFunction {
        @Override public LuaValue call(LuaValue pos) {
            try {
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "dig expected int cell, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!levelOk(cell)) return NIL;
                int t = Dungeon.level.map[cell];
                if (!isDigTarget(t)) {
                    Gdx.app.error(TAG, "dig rejected non-diggable terrain " + t + " at " + cell);
                    return LuaValue.valueOf(false);
                }
                int replacement = (Terrain.flags[t] & Terrain.FLAMABLE) != 0 ? Terrain.EMBERS : Terrain.EMPTY;
                Level.set(cell, replacement);
                // Repaint the tile and recompute FOV (a dug wall opens/closes LOS).
                refreshMapVisuals(cell);
                if (hasLiveScene() && Dungeon.hero != null) Dungeon.observe();
                return LuaValue.valueOf(true);
            } catch (Exception e) {
                Gdx.app.error(TAG, "dig threw", e);
                return NIL;
            }
        }

        private boolean isDigTarget(int t) {
            return t == Terrain.WALL
                    || t == Terrain.WALL_DECO
                    || t == Terrain.DOOR
                    || t == Terrain.BARRICADE
                    || t == Terrain.SECRET_DOOR;
        }
    }

    /** {@code RPD.dropItem(cell, itemId, qty)} — create a Lua item and drop it on the floor at cell. Returns bool. */
    private static final class DropItem extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue pos, LuaValue itemId, LuaValue qty) {
            try {
                if (!pos.isint()) {
                    Gdx.app.error(TAG, "dropItem expected int cell, got " + pos.typename());
                    return NIL;
                }
                int cell = pos.toint();
                if (!levelOk(cell)) return NIL;
                if (!itemId.isstring()) {
                    Gdx.app.error(TAG, "dropItem expected string itemId, got " + itemId.typename());
                    return NIL;
                }
                int n = qtyOf(qty);
                if (n < 0) {
                    Gdx.app.error(TAG, "dropItem rejected qty " + qty);
                    return NIL;
                }
                Item item = LuaItemRegistry.createItem(itemId.checkjstring());
                if (item == null) {
                    item = createNativeDropItem(itemId.checkjstring());
                }
                if (item == null) {
                    Gdx.app.error(TAG, "dropItem: unknown item id '" + itemId.tojstring() + "'");
                    return NIL;
                }
                item.quantity(n);
                // Mirror Level.drop's heap setup: pos MUST be set before put so
                // the heap restores at the right cell on save/load and its sprite
                // places correctly (Heap.storeInBundle writes pos).
                com.shatteredpixel.shatteredpixeldungeon.items.Heap heap = Dungeon.level.heaps.get(cell);
                if (heap == null) {
                    heap = new com.shatteredpixel.shatteredpixeldungeon.items.Heap();
                    heap.pos = cell;
                    Dungeon.level.heaps.put(cell, heap);
                }
                heap.drop(item);
                if (hasLiveScene()) GameScene.add(heap); // give the heap a live sprite on device
                return LuaValue.valueOf(true);
            } catch (Exception e) {
                Gdx.app.error(TAG, "dropItem threw", e);
                return NIL;
            }
        }
    }

    /**
     * M11c: small whitelist for native items that Lua item scripts are allowed to
     * drop via {@code RPD.dropItem}. These are quest/material items with no
     * gameplay-breaking side effects; arbitrary native Class lookup is intentionally
     * NOT exposed.
     */
    private static Item createNativeDropItem(String id) {
        switch (id) {
            case "dark_gold": return new com.shatteredpixel.shatteredpixeldungeon.items.quest.DarkGold();
            case "mystery_meat": return new com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat();
            default: return null;
        }
    }
}
