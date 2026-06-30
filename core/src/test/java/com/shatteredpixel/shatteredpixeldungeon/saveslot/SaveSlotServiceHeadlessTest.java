/*
 * Fork-only test harness for the save-slot service layer.
 */

package com.shatteredpixel.shatteredpixeldungeon.saveslot;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Headless JUnit coverage for the service-layer guards in
 * {@link SaveSlotService} and the package-private copy helper used by
 * {@link SaveSlotService#loadFromSlot(String)}.
 *
 * <p>Fixture mirrors {@link SaveSlotIOHeadlessTest}: same tmp-redirect Files
 * wrapper, same global-state save/restore contract.</p>
 */
public class SaveSlotServiceHeadlessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static HeadlessApplication application;
    private static int savedVersionCode;
    private static boolean savedDaily;
    private static boolean savedDailyReplay;
    private static int savedCurSlot;
    private static Files savedGdxFiles;

    private File localRoot;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);

        savedVersionCode = Game.versionCode;
        String sysProp = System.getProperty("spd.appVersionCode");
        if (sysProp != null && !sysProp.isEmpty()) {
            try {
                Game.versionCode = Integer.parseInt(sysProp);
            } catch (NumberFormatException ignored) {
                Game.versionCode = 896;
            }
        } else {
            Game.versionCode = 896;
        }

        savedDaily = Dungeon.daily;
        savedDailyReplay = Dungeon.dailyReplay;
        savedCurSlot = GamesInProgress.curSlot;
        savedGdxFiles = Gdx.files;
    }

    @AfterClass
    public static void shutdownHeadless() {
        try {
            if (application != null) application.exit();
        } catch (Throwable ignored) {}
        Game.versionCode = savedVersionCode;
        Dungeon.daily = savedDaily;
        Dungeon.dailyReplay = savedDailyReplay;
        GamesInProgress.curSlot = savedCurSlot;
        if (savedGdxFiles != null) Gdx.files = savedGdxFiles;
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
    }

    @Before
    public void isolateLocalStorage() throws IOException {
        Dungeon.daily = false;
        Dungeon.dailyReplay = false;
        // Pick a deterministic test slot (1..MAX_SLOTS) and clear any cached state.
        GamesInProgress.curSlot = 1;
        GamesInProgress.setUnknown(GamesInProgress.curSlot);

        localRoot = tmp.newFolder("local-root");
        final String tmpAbs = localRoot.getAbsolutePath();
        Gdx.files = new TmpRedirectFiles(savedGdxFiles, tmpAbs);
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
    }

    @After
    public void restoreLocalStorage() {
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
        if (savedGdxFiles != null) Gdx.files = savedGdxFiles;
        // Reset cached slot state so it doesn't leak into other test classes.
        try { GamesInProgress.setUnknown(GamesInProgress.curSlot); } catch (Throwable ignored) {}
    }

    // ---- TmpRedirectFiles (mirror SaveSlotIOHeadlessTest) --------------------

    private static final class TmpRedirectFiles implements Files {
        private final Files delegate;
        private final String tmpAbs;

        TmpRedirectFiles(Files delegate, String tmpAbs) {
            this.delegate = delegate;
            this.tmpAbs = tmpAbs;
        }

        private String resolve(String path) {
            if (path == null) return path;
            if (path.equals(tmpAbs) || path.startsWith(tmpAbs + "/")) return path;
            return tmpAbs + "/" + path;
        }

        @Override public FileHandle local(String filename) { return delegate.local(resolve(filename)); }
        @Override public FileHandle absolute(String filename) { return delegate.absolute(filename); }
        @Override public FileHandle classpath(String path) { return delegate.classpath(path); }
        @Override public FileHandle internal(String path) { return delegate.internal(path); }
        @Override public FileHandle external(String path) { return delegate.external(path); }
        @Override public FileHandle getFileHandle(String fileName, FileType type) {
            if (type == FileType.Local) return delegate.local(resolve(fileName));
            return delegate.getFileHandle(fileName, type);
        }
        @Override public String getLocalStoragePath() { return delegate.getLocalStoragePath(); }
        @Override public String getExternalStoragePath() { return delegate.getExternalStoragePath(); }
        @Override public boolean isExternalStorageAvailable() { return delegate.isExternalStorageAvailable(); }
        @Override public boolean isLocalStorageAvailable() { return delegate.isLocalStorageAvailable(); }
    }

    // ---- helpers -------------------------------------------------------------

    private static final String SLOT_ROOT = "save_slots/";
    private static final String META_FILE = "meta.bundle";

    private void writeSlot(String name, int version) throws IOException {
        String dir = SLOT_ROOT + name;
        FileUtils.getFileHandle(dir).mkdirs();

        Bundle meta = new Bundle();
        meta.put("name", name);
        meta.put("version", version);
        meta.put("depth", 5);
        meta.put("level", 7);
        meta.put("hero_class", HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        FileUtils.bundleToFile(dir + "/" + META_FILE, meta);

        try (OutputStream os = FileUtils.getFileHandle(dir + "/game.dat").write(false)) {
            os.write("dummy".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeCurrentGameFolder(int slot, String marker) throws IOException {
        String dir = GamesInProgress.gameFolder(slot);
        FileUtils.getFileHandle(dir).mkdirs();
        try (OutputStream os = FileUtils.getFileHandle(dir + "/game.dat").write(false)) {
            os.write(marker.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readCurrentGameFolderMarker(int slot) throws IOException {
        FileHandle fh = FileUtils.getFileHandle(GamesInProgress.gameFolder(slot) + "/game.dat");
        if (!fh.exists()) return null;
        return new String(fh.readBytes(), StandardCharsets.UTF_8);
    }

    private byte[] buildValidZip(String slotName, int version) throws IOException {
        Bundle meta = new Bundle();
        meta.put("name", slotName);
        meta.put("version", version);
        meta.put("depth", 5);
        meta.put("level", 7);
        meta.put("hero_class", HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        ByteArrayOutputStream metaBytes = new ByteArrayOutputStream();
        Bundle.write(meta, metaBytes);
        byte[] metaRaw = metaBytes.toByteArray();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        zos.putNextEntry(new ZipEntry(META_FILE));
        zos.write(metaRaw);
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("game.dat"));
        zos.write("imported-game".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    // ---- tests --------------------------------------------------------------

    @Test
    public void export_requires_valid_existing_slot() throws IOException {
        // Invalid name -> IllegalArgumentException
        try {
            SaveSlotService.exportToStream("../escape", new ByteArrayOutputStream());
            fail("expected IllegalArgumentException for invalid name");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // Valid name but slot does not exist -> IOException
        try {
            SaveSlotService.exportToStream("missing", new ByteArrayOutputStream());
            fail("expected IOException for nonexistent slot");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void daily_disables_export_import() throws IOException {
        // set up a real slot so we can prove the daily guard, not the existence guard, rejects
        writeSlot("alpha-daily", Game.versionCode);

        boolean dailyWas = Dungeon.daily;
        boolean replayWas = Dungeon.dailyReplay;
        try {
            // daily=true: export must throw IllegalStateException
            Dungeon.daily = true;
            Dungeon.dailyReplay = false;
            try {
                SaveSlotService.exportToStream("alpha-daily", new ByteArrayOutputStream());
                fail("export should be rejected under daily");
            } catch (IllegalStateException expected) {
                // ok
            }

            // daily=true: import must return daily_disabled
            byte[] zip = buildValidZip("alpha-daily", Game.versionCode);
            SlotImportResult r = SaveSlotService.importFromStream(
                    new ByteArrayInputStream(zip), "alpha-daily");
            assertFalse("import ok should be false under daily", r.ok);
            assertEquals("daily_disabled", r.message);

            // dailyReplay=true (daily=false): both must still be rejected
            Dungeon.daily = false;
            Dungeon.dailyReplay = true;
            try {
                SaveSlotService.exportToStream("alpha-daily", new ByteArrayOutputStream());
                fail("export should be rejected under dailyReplay");
            } catch (IllegalStateException expected) {
                // ok
            }

            SlotImportResult r2 = SaveSlotService.importFromStream(
                    new ByteArrayInputStream(zip), "alpha-daily");
            assertFalse(r2.ok);
            assertEquals("daily_disabled", r2.message);
        } finally {
            Dungeon.daily = dailyWas;
            Dungeon.dailyReplay = replayWas;
        }
    }

    @Test
    public void load_from_imported_slot_copies_into_current_game_folder() throws IOException {
        // 1. Set up an existing slot "beta-load" with marker content.
        writeSlot("beta-load", Game.versionCode);

        // 2. Pre-populate the current game folder with a different marker so we
        //    can detect that copySlotToCurrentGame replaced it. Also plant a
        //    stale file that does NOT exist in the slot, to prove the helper
        //    wipes the destination rather than merely overwriting same-name files.
        int slot = GamesInProgress.curSlot;
        writeCurrentGameFolder(slot, "old-current");
        String curDir = GamesInProgress.gameFolder(slot);
        try (OutputStream os = FileUtils.getFileHandle(curDir + "/stale.dat").write(false)) {
            os.write("should-be-wiped".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue("precondition: stale file must exist before copy",
                FileUtils.fileExists(curDir + "/stale.dat"));

        // 3. Invoke the package-private helper directly. This is the same code
        //    path that loadFromSlot runs after passing all daily/meta/version
        //    guards; the difference is that we skip the scene switch.
        SaveSlotService.copySlotToCurrentGame("beta-load");

        // 4. Assert current game folder now holds the slot's game.dat content.
        String marker = readCurrentGameFolderMarker(slot);
        assertNotNull("current game folder must exist after copy", marker);
        assertEquals("dummy", marker);

        // 5. The stale file must be gone — copySlotToCurrentGame does
        //    deleteDir(dst) + copyDir(src, dst), so any pre-existing files in
        //    dst that aren't in src must not survive.
        assertFalse("stale file must be wiped from current game folder",
                FileUtils.fileExists(curDir + "/stale.dat"));

        // 6. Slot root must remain isolated from game{n} root.
        assertTrue("slot must still exist after copy",
                FileUtils.dirExists(SLOT_ROOT + "beta-load"));
        assertFalse("slot must not collide with game{n} folder",
                (SLOT_ROOT + "beta-load").equals(GamesInProgress.gameFolder(slot)));
        // Sanity: the slot's meta is still readable and unchanged.
        SaveSlotMeta meta = SaveSlotService.readMeta("beta-load");
        assertNotNull(meta);
        assertEquals("beta-load", meta.name);
        assertEquals(Game.versionCode, meta.version);

        // 7. Clear cached slot state so it does not leak across tests.
        GamesInProgress.setUnknown(slot);
    }
}
