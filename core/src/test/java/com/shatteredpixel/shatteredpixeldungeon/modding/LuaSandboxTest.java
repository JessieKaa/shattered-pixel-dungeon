package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the M1 sandbox boundary: {@link LuaSandbox#exposedGlobals()} must
 * drop every library/function that could break containment, while leaving the
 * pure-computation globals intact. It also confirms the compile-time-generated
 * {@code lua-interface-map.json} is on the classpath and drives {@code canAccess*}.
 */
public class LuaSandboxTest {

    private static Globals globals;

    @BeforeClass
    public static void buildGlobals() {
        globals = LuaSandbox.exposedGlobals();
    }

    @Test
    public void dangerousLibrariesAreStripped() {
        String[] dangerous = {"io", "os", "package", "debug", "luajava",
                "load", "loadfile", "loadstring", "dofile", "require",
                "getfenv", "setfenv"};
        for (String name : dangerous) {
            assertTrue("sandbox must strip '" + name + "'",
                    globals.get(name).isnil());
        }
    }

    @Test
    public void safeLibrariesAreKept() {
        // Sanity check: we didn't strip the pure-computation libs Lua scripts rely on.
        assertFalse("math should remain", globals.get("math").isnil());
        assertFalse("string should remain", globals.get("string").isnil());
        assertFalse("table should remain", globals.get("table").isnil());
        assertFalse("print should remain", globals.get("print").isnil());
        assertFalse("pairs should remain", globals.get("pairs").isnil());
        assertFalse("type should remain", globals.get("type").isnil());
    }

    @Test
    public void luaReadingIoReturnsNil() {
        // `return io` from inside Lua resolves to the (now nil) global.
        LuaValue v = globals.load("return io").call();
        assertTrue("Lua `return io` must yield nil", v.isnil());
    }

    /** The single most important assertion: Lua cannot reach arbitrary Java. */
    @Test
    public void luajavaBindClassUnreachable() {
        // pcall returns (ok, ...); ok==false means the indexing of nil `luajava` threw.
        LuaValue ok = globals.load(
                "return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
        ).call();
        assertTrue("luajava.bindClass must fail (pcall ok must be false/nil)", !ok.toboolean());
    }

    @Test
    public void requireUnreachable() {
        LuaValue ok = globals.load("return pcall(function() return require('os') end)").call();
        assertTrue("require must fail (pcall ok must be false/nil)", !ok.toboolean());
    }

    @Test
    public void loadstringUnreachable() {
        // Lua cannot compile source at runtime once loadstring/load are stripped.
        assertTrue("loadstring must be nil", globals.get("loadstring").isnil());
        LuaValue ok = globals.load(
                "return pcall(function() return loadstring('return 1') end)"
        ).call();
        assertTrue("loadstring call must fail", !ok.toboolean());
    }

    @Test
    public void interfaceMapLoadedFromProcessor() {
        // Generated at compile time by :processor from @LuaInterface on LuaItem.name()/desc().
        assertTrue("lua-interface-map.json should be on the classpath", LuaSandbox.isMapPresent());

        String luaItem = "com.shatteredpixel.shatteredpixeldungeon.modding.LuaItem";
        assertTrue("canAccessClass(LuaItem)", LuaSandbox.canAccessClass(luaItem));
        assertTrue("name() is @LuaInterface-annotated",
                LuaSandbox.canAccessMethod(luaItem, "name"));
        assertTrue("desc() is @LuaInterface-annotated",
                LuaSandbox.canAccessMethod(luaItem, "desc"));
        assertFalse("storeInBundle is NOT annotated → must be denied",
                LuaSandbox.canAccessMethod(luaItem, "storeInBundle"));
    }

    @Test
    public void canAccessDeniesUnknownClass() {
        assertFalse(LuaSandbox.canAccessClass("java.lang.Runtime"));
        assertFalse(LuaSandbox.canAccessMethod("java.lang.Runtime", "exec"));
    }

    @Test
    public void hostSideLoadStillWorks() {
        // The Java Globals.load(source, chunk) method is independent of the removed
        // Lua `load` global — the host must still be able to bootstrap scripts.
        assertEquals(42, globals.load("return 6 * 7").call().toint());
    }
}
