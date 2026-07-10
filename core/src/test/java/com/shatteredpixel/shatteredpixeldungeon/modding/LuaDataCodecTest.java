package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M19a: {@link LuaDataCodec} round-trips for the safe subset (root scalars,
 * tables, nesting) plus the hardening paths (unsupported types, cycles,
 * over-depth) and {@link LuaDataCodec#deepCopy} isolation. Mirrors the headless
 * Gdx setup of {@link LuaTrapRegistryTest} — the codec logs via
 * {@code Gdx.app.error} on skip paths.
 */
public class LuaDataCodecTest {

	private static HeadlessApplication application;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
	}

	@AfterClass
	public static void shutdown() {
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	// ---- root scalars ----

	@Test
	public void roundTrip_nil() {
		assertEquals(LuaValue.NIL, LuaDataCodec.decode(LuaDataCodec.encode(LuaValue.NIL)));
	}

	@Test
	public void roundTrip_booleans() {
		assertTrue(LuaDataCodec.decode(LuaDataCodec.encode(LuaValue.valueOf(true))).toboolean());
		assertFalse(LuaDataCodec.decode(LuaDataCodec.encode(LuaValue.valueOf(false))).toboolean());
	}

	@Test
	public void roundTrip_string() {
		assertEquals("hello",
				LuaDataCodec.decode(LuaDataCodec.encode(LuaValue.valueOf("hello"))).tojstring());
	}

	@Test
	public void roundTrip_intIsLossless() {
		LuaValue out = LuaDataCodec.decode(LuaDataCodec.encode(LuaValue.valueOf(42)));
		assertTrue("int preserved as integer", out.isint());
		assertEquals(42, out.toint());
	}

	@Test
	public void roundTrip_float() {
		// double is narrowed to float on store; assert we get a number ~= the input
		LuaValue out = LuaDataCodec.decode(LuaDataCodec.encode(LuaValue.valueOf(3.14)));
		assertTrue(out.isnumber());
		assertEquals(3.14, out.todouble(), 1e-3);
	}

	// ---- tables ----

	@Test
	public void roundTrip_flatTable() {
		LuaTable in = new LuaTable();
		in.set("name", LuaValue.valueOf("alice"));
		in.set("count", LuaValue.valueOf(7));
		in.set("flag", LuaValue.valueOf(true));

		LuaTable out = (LuaTable) LuaDataCodec.decode(LuaDataCodec.encode(in));
		assertEquals("alice", out.get("name").tojstring());
		assertTrue(out.get("count").isint());
		assertEquals(7, out.get("count").toint());
		assertTrue(out.get("flag").toboolean());
	}

	@Test
	public void roundTrip_nestedTable() {
		LuaTable in = new LuaTable();
		LuaTable inner = new LuaTable();
		inner.set("x", LuaValue.valueOf(1));
		in.set("outer", inner);

		LuaTable out = (LuaTable) LuaDataCodec.decode(LuaDataCodec.encode(in));
		LuaTable outInner = (LuaTable) out.get("outer");
		assertEquals(1, outInner.get("x").toint());
	}

	// ---- hardening: unsupported types, cycles, depth ----

	@Test
	public void encode_unsupportedType_storedAsNil() {
		LuaValue fn = new ZeroArgFunction() {
			@Override public LuaValue call() { return LuaValue.NIL; }
		};
		LuaValue out = LuaDataCodec.decode(LuaDataCodec.encode(fn));
		assertTrue("function (userdata/thread) must degrade to nil, not throw", out.isnil());
	}

	@Test
	public void encode_tableWithUnsupportedChild_skipsThatChild() {
		LuaTable in = new LuaTable();
		in.set("keep", LuaValue.valueOf("ok"));
		in.set("drop", new ZeroArgFunction() {
			@Override public LuaValue call() { return LuaValue.NIL; }
		});
		LuaTable out = (LuaTable) LuaDataCodec.decode(LuaDataCodec.encode(in));
		assertEquals("ok", out.get("keep").tojstring());
		assertTrue("unsupported child dropped", out.get("drop").isnil());
	}

	@Test
	public void encode_cyclicTable_doesNotOverflow() {
		LuaTable t = new LuaTable();
		t.set("self", t);            // back-edge → cycle
		t.set("val", LuaValue.valueOf(9));

		// Must complete without StackOverflowError.
		LuaTable out = (LuaTable) LuaDataCodec.decode(LuaDataCodec.encode(t));
		assertEquals("non-cyclic sibling key survives", 9, out.get("val").toint());
		assertTrue("cyclic entry cut (stored as nil)", out.get("self").isnil());
	}

	@Test
	public void encode_overDepthTable_doesNotOverflow() {
		// Without the depth guard, recursing 5000 deep in Java overflows the stack.
		LuaTable root = new LuaTable();
		LuaTable cur = root;
		for (int i = 0; i < 5000; i++) {
			LuaTable next = new LuaTable();
			cur.set("k", next);
			cur = next;
		}
		cur.set("leaf", LuaValue.valueOf("bottom"));

		// Completes; the over-depth tail is silently dropped.
		LuaValue out = LuaDataCodec.decode(LuaDataCodec.encode(root));
		assertTrue("top level still a table", out.istable());
	}

	// ---- deepCopy isolation ----

	@Test
	public void deepCopy_isolatesFromOriginal() {
		LuaTable orig = new LuaTable();
		orig.set("msg", LuaValue.valueOf("first"));
		LuaTable origNested = new LuaTable();
		origNested.set("n", LuaValue.valueOf(1));
		orig.set("nested", origNested);

		LuaTable copy = (LuaTable) LuaDataCodec.deepCopy(orig);

		// Mutate the copy (root + nested) — original must be unaffected.
		copy.set("msg", LuaValue.valueOf("changed"));
		((LuaTable) copy.get("nested")).set("n", LuaValue.valueOf(999));

		assertEquals("original root untouched by copy mutation", "first", orig.get("msg").tojstring());
		assertEquals("original nested untouched by copy mutation",
				1, ((LuaTable) orig.get("nested")).get("n").toint());
	}
}
