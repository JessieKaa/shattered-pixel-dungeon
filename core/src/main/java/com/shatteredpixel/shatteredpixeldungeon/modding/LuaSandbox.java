package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The M1 Lua sandbox boundary.
 *
 * <p>Two mechanisms, layered:
 * <ol>
 *   <li><b>{@link #exposedGlobals()}</b> — the actual runtime enforcement. Built from
 *       {@link JsePlatform#standardGlobals()} with every dangerous library/function
 *       removed ({@code io}, {@code os}, {@code package}, {@code debug},
 *       {@code luajava}, {@code load}, {@code loadfile}, {@code dofile},
 *       {@code loadstring}, {@code require}, {@code getfenv}, {@code setfenv}).
 *       Crucially, removing {@code luajava} is what stops Lua from reaching arbitrary
 *       Java via {@code luajava.bindClass}; removing {@code load}/{@code loadstring}
 *       stops runtime compilation of smuggled source. The host Java side still
 *       compiles scripts via {@code Globals.load(source, chunk)} (a Java method,
 *       not the removed Lua global).</li>
 *   <li><b>The {@code lua-interface-map.json} whitelist</b> — generated at compile
 *       time by the {@code :processor} module from {@code @LuaInterface} annotations.
 *       M1 exposes it via {@link #canAccessClass}/{@code Method}/{@code Field} as the
 *       foundation for finer-grained Java-to-Lua binding in M2+. M1 does not wire
 *       per-call interception into luaj (that needs a CoerceJava hook neither Remixed
 *       nor M1 implement); the curated globals above are the load-bearing boundary.</li>
 * </ol>
 *
 * <p>The map is optional: if it is missing from the classpath (e.g. the processor
 * did not run), {@code canAccess*} conservatively returns {@code false} and
 * {@link #exposedGlobals()} is unaffected — the sandbox still enforces.
 */
public final class LuaSandbox {

    private static final Logger LOG = Logger.getLogger(LuaSandbox.class.getName());
    private static final String MAP_RESOURCE = "lua-interface-map.json";

    private static final Map<String, Set<String>> CLASS_METHODS = new HashMap<>();
    private static final Map<String, Set<String>> CLASS_FIELDS = new HashMap<>();
    private static final Map<String, Set<String>> CLASS_CONSTRUCTORS = new HashMap<>();
    private static final Set<String> CLASS_ANNOTATED = new HashSet<>();
    private static boolean initialized = false;
    private static boolean mapPresent = false;

    private LuaSandbox() { }

    static {
        initialize();
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try (InputStream is = LuaSandbox.class.getClassLoader().getResourceAsStream(MAP_RESOURCE)) {
            if (is == null) {
                LOG.warning(MAP_RESOURCE + " not found on classpath; canAccess* will deny all. "
                        + "Sandbox globals enforcement is unaffected.");
                return;
            }
            mapPresent = true;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            parseJsonMap(sb.toString());
            LOG.info("LuaSandbox loaded " + MAP_RESOURCE + ": " + CLASS_METHODS.size()
                    + " method-entries, " + CLASS_ANNOTATED.size() + " class-annotated");
        } catch (Exception e) {
            LOG.severe("Failed to load " + MAP_RESOURCE + ": " + e.getMessage());
        }
    }

    /**
     * A curated {@link Globals} with all dangerous libraries and functions stripped.
     * Lua handed these globals cannot reach {@code io}/{@code os}/{@code luajava},
     * cannot {@code require}/{@code load}/{@code loadstring} smuggled code, and
     * cannot {@code dofile} — so it is confined to pure computation plus whatever
     * safe globals the host injects (e.g. {@code register_item}).
     *
     * <p>Returns a fresh {@link Globals} each call so callers (e.g. tests) cannot
     * mutate a shared instance.
     */
    public static Globals exposedGlobals() {
        Globals g = JsePlatform.standardGlobals();
        // Library tables.
        strip(g, "io");
        strip(g, "os");
        strip(g, "package");     // also kills `require`'s backing, but see explicit strip below
        strip(g, "debug");
        strip(g, "luajava");     // critical: prevents luajava.bindClass / newInstance into Java
        // Global functions registered by BaseLib / PackageLib / IoLib.
        strip(g, "load");        // no runtime source compilation
        strip(g, "loadfile");
        strip(g, "dofile");
        strip(g, "loadstring");
        strip(g, "require");     // package=nil does not remove the require global, strip explicitly
        strip(g, "getfenv");
        strip(g, "setfenv");
        return g;
    }

    private static void strip(Globals g, String name) {
        g.set(name, LuaValue.NIL);
    }

    public static boolean isMapPresent() {
        return mapPresent;
    }

    public static boolean canAccessClass(String className) {
        return CLASS_ANNOTATED.contains(className)
                || CLASS_METHODS.containsKey(className)
                || CLASS_FIELDS.containsKey(className)
                || CLASS_CONSTRUCTORS.containsKey(className);
    }

    public static boolean canAccessMethod(String className, String methodName) {
        if (!canAccessClass(className)) return false;
        Set<String> methods = CLASS_METHODS.get(className);
        return methods != null && methods.contains(methodName);
    }

    public static boolean canAccessField(String className, String fieldName) {
        if (!canAccessClass(className)) return false;
        Set<String> fields = CLASS_FIELDS.get(className);
        return fields != null && fields.contains(fieldName);
    }

    public static boolean canAccessConstructor(String className) {
        if (!canAccessClass(className)) return false;
        Set<String> ctors = CLASS_CONSTRUCTORS.get(className);
        return ctors != null && ctors.contains("<init>");
    }

    public static Set<String> classes() {
        return Collections.unmodifiableSet(CLASS_ANNOTATED);
    }

    /**
     * Compact JSON parser tailored to the processor's fixed schema:
     * <pre>{ "fqcn": { "methods": ["..", ..], "fields": [...], "constructors": [...], "classAnnotated": true } }</pre>
     * No nested objects beyond one level, no escapes beyond what {@link #parseJsonString} handles.
     */
    private static void parseJsonMap(String json) {
        int i = skipWs(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') return;
        i++;
        while (i < json.length()) {
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) == '}') break;

            String className = parseJsonString(json, i);
            if (className == null) break;
            i = skipWs(json, i + className.length() + 2);

            if (i >= json.length() || json.charAt(i) != ':') break;
            i = skipWs(json, i + 1);
            if (i >= json.length() || json.charAt(i) != '{') break;
            i++;

            Set<String> methods = new HashSet<>();
            Set<String> fields = new HashSet<>();
            Set<String> constructors = new HashSet<>();
            boolean classAnnotated = false;

            while (i < json.length()) {
                i = skipWs(json, i);
                if (i >= json.length()) break;
                if (json.charAt(i) == '}') { i++; break; }

                String prop = parseJsonString(json, i);
                if (prop == null) break;
                i = skipWs(json, i + prop.length() + 2);
                if (i >= json.length() || json.charAt(i) != ':') break;
                i = skipWs(json, i + 1);
                if (i >= json.length()) break;

                switch (prop) {
                    case "methods":      i = parseJsonArray(json, i, methods); break;
                    case "fields":       i = parseJsonArray(json, i, fields); break;
                    case "constructors": i = parseJsonArray(json, i, constructors); break;
                    case "classAnnotated":
                        if (json.charAt(i) == 't') classAnnotated = true;
                        while (i < json.length() && Character.isLetter(json.charAt(i))) i++;
                        break;
                    default:
                        return; // unknown property → schema drift, bail
                }

                i = skipWs(json, i);
                if (i < json.length() && json.charAt(i) == ',') i++;
            }

            if (classAnnotated) CLASS_ANNOTATED.add(className);
            if (!methods.isEmpty()) CLASS_METHODS.put(className, methods);
            if (!fields.isEmpty()) CLASS_FIELDS.put(className, fields);
            if (!constructors.isEmpty()) CLASS_CONSTRUCTORS.put(className, constructors);

            i = skipWs(json, i);
            if (i < json.length() && json.charAt(i) == ',') i++;
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String parseJsonString(String s, int i) {
        i = skipWs(s, i);
        if (i >= s.length() || s.charAt(i) != '"') return null;
        i++;
        StringBuilder out = new StringBuilder();
        while (i < s.length() && s.charAt(i) != '"') {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                char c = s.charAt(i + 1);
                switch (c) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(c); break;
                }
                i += 2;
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static int parseJsonArray(String s, int i, Set<String> out) {
        i = skipWs(s, i);
        if (i >= s.length() || s.charAt(i) != '[') return i;
        i++;
        while (i < s.length()) {
            i = skipWs(s, i);
            if (i >= s.length()) break;
            if (s.charAt(i) == ']') { i++; break; }
            String str = parseJsonString(s, i);
            if (str != null) {
                out.add(str);
                i = skipWs(s, i + str.length() + 2);
            } else {
                // malformed; advance to avoid infinite loop
                while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != ']') i++;
            }
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        return i;
    }
}
