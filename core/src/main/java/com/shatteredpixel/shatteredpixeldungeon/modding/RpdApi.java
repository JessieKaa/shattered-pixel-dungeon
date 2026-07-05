package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

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
        return rpd;
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
                if (applier == null) {
                    Gdx.app.error(TAG, "affectBuff rejected non-whitelisted buff: " + name);
                    return NIL;
                }
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "affectBuff rejected amount " + amt + " for " + name);
                    return NIL;
                }
                applier.apply(target, (float) amt);
            } catch (Exception e) {
                Gdx.app.error(TAG, "affectBuff threw", e);
            }
            return NIL;
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
        static {
            // FlavourBuff family: prolong(target, clazz, duration).
            putFlavour("Roots", Roots.class);
            putFlavour("Slow", Slow.class);
            putFlavour("Cripple", Cripple.class);
            putFlavour("Paralysis", Paralysis.class);
            putFlavour("Vertigo", Vertigo.class);
            putFlavour("Haste", Haste.class);
            // Level-based buffs with type-specific setters.
            ENTRIES.put("Bleeding", (t, amt) -> {
                Bleeding b = Buff.affect(t, Bleeding.class);
                b.set(amt);
            });
            ENTRIES.put("Poison", (t, amt) -> {
                Poison b = Buff.affect(t, Poison.class);
                b.set(amt);
            });
            ENTRIES.put("Barkskin", (t, amt) -> {
                Barkskin b = Buff.affect(t, Barkskin.class);
                int v = Math.max(1, (int) amt);
                b.set(v, v);
            });
        }

        private static <T extends FlavourBuff> void putFlavour(String name, Class<T> clazz) {
            ENTRIES.put(name, (t, amt) -> Buff.prolong(t, clazz, amt));
        }

        static BuffApplier lookup(String simpleName) {
            return ENTRIES.get(simpleName);
        }

        private BuffWhitelist() { }
    }
}
