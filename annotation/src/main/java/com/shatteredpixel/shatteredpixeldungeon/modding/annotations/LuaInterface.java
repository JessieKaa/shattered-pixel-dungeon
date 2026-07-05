package com.shatteredpixel.shatteredpixeldungeon.modding.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java type/member as safe to expose to the Lua sandbox. The
 * {@code processor} module walks every annotated element at compile time and
 * emits {@code lua-interface-map.json} — a whitelist of classes/methods/fields
 * that Lua code is permitted to reach.
 *
 * <p>{@link RetentionPolicy#SOURCE} on purpose: the annotation is a compile-time
 * signal for the processor only. It is not retained in the {@code .class} files,
 * which keeps the runtime artifact free of annotation references and lets
 * {@code core} depend on the {@code :annotation} module via {@code compileOnly}
 * without R8/release-build churn.
 *
 * <p>M1 keeps the generated map as a foundation for finer-grained exposure (M2+);
 * the runtime sandbox boundary itself is enforced by curating luaj globals
 * ({@code LuaSandbox.exposedGlobals()}) rather than per-call interception.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface LuaInterface {
}
