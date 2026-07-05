/*
 * Fork-only cross-check: a zip produced by tools/save-editor/spd_bundle.py
 * must pass SaveSlotIO.readSlotFromStream without modification.
 *
 * Python pack_slot_zip writes real bytes to /tmp; the path is passed in via
 * the SPD_ZIP_PATH environment variable (gradle's `test` task does not
 * forward arbitrary -D system properties to the forked JVM by default, so
 * env var is the zero-config channel).
 *
 * Test flow:
 *   1. Read SPD_ZIP_PATH; skip if unset (so this fixture is harmless when
 *      run in the normal `./gradlew :core:test` pass).
 *   2. Stream the zip through SaveSlotIO.readSlotFromStream.
 *   3. Assert ok=true, meta.version=896.
 *   4. try/finally: cleanupStaging, then scan for `.import-*` leftovers.
 */

package com.shatteredpixel.shatteredpixeldungeon.saveslot;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Mirrors the headless init / TmpRedirectFiles pattern from
 * {@code SaveSlotIOHeadlessTest} so SaveSlotIO sees an isolated Local fs.
 *
 * <p>The fixture is driven by {@code SPD_ZIP_PATH}: if unset, the single
 * test is a no-op (so a normal gradle pass without Python involvement does
 * not error out).</p>
 */
public class SaveSlotIOPythonZipTest {

    private static HeadlessApplication application;
    private static int savedVersionCode;
    private static boolean savedDaily;
    private static boolean savedDailyReplay;
    private static Files savedGdxFiles;
    private static File localRoot;

    @BeforeClass
    public static void initHeadless() throws IOException {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);

        savedVersionCode = Game.versionCode;
        Game.versionCode = 896;

        savedDaily = Dungeon.daily;
        savedDailyReplay = Dungeon.dailyReplay;
        savedGdxFiles = Gdx.files;

        // Per-suite tmp root so cleanupLeftovers scan has a known base.
        localRoot = java.nio.file.Files.createTempDirectory("spd-python-zip-test").toFile();
        Gdx.files = new TmpRedirectFiles(savedGdxFiles, localRoot.getAbsolutePath());
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
    }

    @AfterClass
    public static void shutdownHeadless() {
        try { if (application != null) application.exit(); } catch (Throwable ignored) {}
        Game.versionCode = savedVersionCode;
        Dungeon.daily = savedDaily;
        Dungeon.dailyReplay = savedDailyReplay;
        if (savedGdxFiles != null) Gdx.files = savedGdxFiles;
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
        if (localRoot != null) recursivelyDelete(localRoot);
    }

    @Test
    public void pythonPackedZipImportsCleanly() throws IOException {
        String zipPath = System.getenv("SPD_ZIP_PATH");
        if (zipPath == null || zipPath.isEmpty()) {
            // Skip when run outside Python orchestration. We still assert
            // true so the test reports as passing in a standalone gradle run.
            return;
        }
        Path p = Paths.get(zipPath);
        assertTrue("SPD_ZIP_PATH does not exist: " + zipPath, java.nio.file.Files.exists(p));
        byte[] zipBytes = java.nio.file.Files.readAllBytes(p);

        SlotImportResult result;
        try (ByteArrayInputStream in = new ByteArrayInputStream(zipBytes)) {
            result = SaveSlotIO.readSlotFromStream(in, "python-test-slot");
        }

        try {
            assertTrue(
                "readSlotFromStream failed: " + result.message,
                result.ok
            );
            assertNotNull("meta must be parsed on success", result.meta);
            assertEquals(
                "meta.version must match Game.versionCode",
                896, result.meta.version
            );
            assertNotNull("stagingRelPath must be set on success", result.stagingRelPath);
        } finally {
            // SaveSlotIO contract: caller must commit or cleanup. We are not
            // promoting, so cleanupStaging.
            if (result != null && result.stagingRelPath != null) {
                SaveSlotIO.cleanupStaging(result.stagingRelPath);
            }
        }

        // No .import-* leftovers under save_slots/.
        List<String> leftovers = scanStagingLeftovers();
        assertTrue(
            "staging leftovers after cleanup: " + leftovers,
            leftovers.isEmpty()
        );
    }

    private List<String> scanStagingLeftovers() {
        List<String> found = new ArrayList<>();
        File slotsRoot = new File(localRoot, "save_slots");
        if (!slotsRoot.isDirectory()) return found;
        String[] children = slotsRoot.list();
        if (children == null) return found;
        for (String c : children) {
            if (c.startsWith(".import-")) found.add(c);
        }
        return found;
    }

    private static void recursivelyDelete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            String[] kids = f.list();
            if (kids != null) {
                for (String k : kids) recursivelyDelete(new File(f, k));
            }
        }
        // best-effort; ignore failure
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * Mirror of SaveSlotIOHeadlessTest.TmpRedirectFiles: redirect Local
     * paths into a per-suite tmp dir.
     */
    private static final class TmpRedirectFiles implements Files {
        private final Files delegate;
        private final String tmpAbs;

        TmpRedirectFiles(Files delegate, String tmpAbs) {
            this.delegate = delegate;
            this.tmpAbs = tmpAbs;
        }

        private String resolve(String path) {
            if (path == null) return path;
            if (path.equals(tmpAbs) || path.startsWith(tmpAbs + "/")) {
                return path;
            }
            return tmpAbs + "/" + path;
        }

        @Override public FileHandle local(String filename) { return delegate.local(resolve(filename)); }
        @Override public FileHandle absolute(String filename) { return delegate.absolute(filename); }
        @Override public FileHandle classpath(String path) { return delegate.classpath(path); }
        @Override public FileHandle internal(String path) { return delegate.internal(path); }
        @Override public FileHandle external(String path) { return delegate.external(path); }
        @Override public FileHandle getFileHandle(String fileName, Files.FileType type) {
            if (type == Files.FileType.Local) return delegate.local(resolve(fileName));
            return delegate.getFileHandle(fileName, type);
        }
        @Override public String getLocalStoragePath() { return delegate.getLocalStoragePath(); }
        @Override public String getExternalStoragePath() { return delegate.getExternalStoragePath(); }
        @Override public boolean isExternalStorageAvailable() { return delegate.isExternalStorageAvailable(); }
        @Override public boolean isLocalStorageAvailable() { return delegate.isLocalStorageAvailable(); }
    }
}
