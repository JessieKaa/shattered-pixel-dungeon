# retain these to support class references for the bundling and translation systems
-keepnames class com.shatteredpixel.** { *; }
-keepnames class com.watabou.** { *; }

# keep classes that are instantiated via reflection
-keep class * extends com.watabou.noosa.Gizmo { *; }
-keep class * extends com.watabou.glscripts.Script { *; }
-keep class * implements com.watabou.utils.Bundlable { *; }

# retained to support meaningful stack traces
# note that the mapping file must be referenced in order to make sense of line numbers
# mapping file can be found in core/build/outputs/mapping after running a release build
-keepattributes SourceFile,LineNumberTable

# libGDX stuff
-dontwarn android.support.**
-dontwarn com.badlogic.gdx.backends.android.AndroidFragmentApplication
-dontwarn com.badlogic.gdx.utils.GdxBuild
-dontwarn com.badlogic.gdx.physics.box2d.utils.Box2DBuild
-dontwarn com.badlogic.gdx.jnigen.BuildTarget*

# needed for libGDX skin reflection used in text fields. Perhaps just don't use skin?
-keep class com.badlogic.gdx.graphics.Color { *; }
-keep class com.badlogic.gdx.scenes.scene2d.ui.TextField$TextFieldStyle { *; }
-keepnames class com.badlogic.gdx.scenes.scene2d.ui.TextField { *; }

# needed for libGDX controllers
-keep class com.badlogic.gdx.controllers.android.AndroidControllers { *; }

-keepclassmembers class com.badlogic.gdx.backends.android.AndroidInput* {
    <init>(com.badlogic.gdx.Application, android.content.Context, java.lang.Object, com.badlogic.gdx.backends.android.AndroidApplicationConfiguration);
}

# Fork (M1 modding): luaj is reflection-heavy (CoerceJava, LuaTable), keep it
# intact so the runtime sandbox and Lua item pipeline survive R8 minification.
# The @LuaInterface whitelist (lua-interface-map.json) is a classpath resource
# and is not stripped; the modding.* package is already covered by the
# -keepnames com.shatteredpixel.** rule at the top of this file.
-keep class org.luaj.vm2.** { *; }

# luaj-jse ships optional code paths that reference the javax.script ScriptEngine
# SPI and Apache BCEL bytecode classes. Neither exists on Android and SPD never
# calls them, but R8 sees the references inside the luaj jar.
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**

# M2 Lua modding: RpdApi references these buff classes by Class literal to build
# the affectBuff whitelist. The broad -keepnames com.shatteredpixel.** rule above
# already retains them; this explicit rule documents the dependency and keeps
# the members RpdApi calls (Bleeding.set / Poison.set / Barkskin.set / Buff.prolong)
# in case the broad rule is ever narrowed during R8 config experiments.
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste { *; }
-keep class com.shatteredpixel.shatteredpixeldungeon.modding.** { *; }