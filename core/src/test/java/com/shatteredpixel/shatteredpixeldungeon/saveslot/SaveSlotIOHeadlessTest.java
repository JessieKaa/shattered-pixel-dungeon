/*
 * This is a fork-only test harness for the save-slot export/import feature.
 * It runs headless (no real render loop) under JUnit4 + gdx-backend-headless.
 */

package com.shatteredpixel.shatteredpixeldungeon.saveslot;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless JUnit coverage for {@link SaveSlotIO} zip export/import logic.
 *
 * <p>Fixture isolates each test under a private temp directory by repointing
 * {@link FileUtils#setDefaultFileProperties(Files.FileType, String)} at
 * {@code Files.FileType.Local} + an absolute tmp path. Static globals
 * ({@link Game#versionCode}, {@link Dungeon#daily}, {@link Dungeon#dailyReplay})
 * are saved and restored around every test so failures cannot leak into siblings.</p>
 */
public class SaveSlotIOHeadlessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static HeadlessApplication application;
    private static int savedVersionCode;
    private static boolean savedDaily;
    private static boolean savedDailyReplay;
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
        if (savedGdxFiles != null) Gdx.files = savedGdxFiles;
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
    }

    @Before
    public void isolateLocalStorage() throws IOException {
        // reset per-test global state that production guards rely on
        Dungeon.daily = false;
        Dungeon.dailyReplay = false;

        localRoot = tmp.newFolder("local-root");
        final String tmpAbs = localRoot.getAbsolutePath();

        // Wrap Gdx.files so all Local paths redirect to tmpAbs. The double-prefix
        // guard is required because production code re-passes FileHandle.path()
        // (absolute) back through FileUtils.bundleFromFile, which would otherwise
        // concatenate basePath + absolutePath and resolve to a bogus location.
        // basePath stays "" to match production (Android/iOS launchers).
        Gdx.files = new TmpRedirectFiles(savedGdxFiles, tmpAbs);
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
    }

    @After
    public void restoreLocalStorage() {
        FileUtils.setDefaultFileProperties(Files.FileType.Local, "");
        if (savedGdxFiles != null) Gdx.files = savedGdxFiles;
    }

    /**
     * Wrapper around the headless {@link Files} that resolves Local paths
     * against a per-test tmp directory. Detects already-resolved absolute
     * paths to avoid double-prefixing when production code re-passes
     * {@code FileHandle.path()} through {@code FileUtils.bundleFromFile}.
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
        @Override public FileHandle getFileHandle(String fileName, FileType type) {
            if (type == FileType.Local) return delegate.local(resolve(fileName));
            return delegate.getFileHandle(fileName, type);
        }
        @Override public String getLocalStoragePath() { return delegate.getLocalStoragePath(); }
        @Override public String getExternalStoragePath() { return delegate.getExternalStoragePath(); }
        @Override public boolean isExternalStorageAvailable() { return delegate.isExternalStorageAvailable(); }
        @Override public boolean isLocalStorageAvailable() { return delegate.isLocalStorageAvailable(); }
    }

    // ---- helpers --------------------------------------------------------------

    private static final String SLOT_ROOT = "save_slots/";
    private static final String META_FILE = "meta.bundle";

    private void writeSlot(String name, HeroClass heroClass, int depth, int level, int version, String... extraFiles) throws IOException {
        String dir = SLOT_ROOT + name;
        // ensure parent dir exists before writing files into it
        FileUtils.getFileHandle(dir).mkdirs();

        Bundle meta = new Bundle();
        meta.put("name", name);
        meta.put("version", version);
        meta.put("depth", depth);
        meta.put("level", level);
        meta.put("hero_class", heroClass != null ? heroClass.name() : HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        FileUtils.bundleToFile(dir + "/" + META_FILE, meta);

        for (int i = 0; i < extraFiles.length; i += 2) {
            String fname = extraFiles[i];
            String content = extraFiles[i + 1];
            try (OutputStream os = FileUtils.getFileHandle(dir + "/" + fname).write(false)) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private boolean slotDirExists(String name) {
        return FileUtils.dirExists(SLOT_ROOT + name);
    }

    private boolean slotFileExists(String name, String fileName) {
        return FileUtils.fileExists(SLOT_ROOT + name + "/" + fileName);
    }

    private ArrayList<String> listSlotFiles(String name) {
        ArrayList<String> result = new ArrayList<>();
        for (String f : FileUtils.filesInDir(SLOT_ROOT + name)) {
            result.add(f);
        }
        return result;
    }

    private ArrayList<String> listSaveSlotsRoot() {
        return FileUtils.filesInDir(SLOT_ROOT);
    }

    /** Read the full contents of a file under save_slots/ as a UTF-8 string. */
    private String readSlotFileUtf8(String slotName, String fileName) {
        FileHandle fh = FileUtils.getFileHandle(SLOT_ROOT + slotName + "/" + fileName);
        if (!fh.exists()) return null;
        return new String(fh.readBytes(), StandardCharsets.UTF_8);
    }

    /** Build a malicious zip from a list of (entryName, content) pairs; returns raw bytes. */
    private static byte[] buildZip(Object... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        for (int i = 0; i < entries.length; i += 2) {
            String entryName = (String) entries[i];
            byte[] content = entries[i + 1] instanceof byte[]
                    ? (byte[]) entries[i + 1]
                    : ((String) entries[i + 1]).getBytes(StandardCharsets.UTF_8);
            ZipEntry e = new ZipEntry(entryName);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    /** Serialize a Bundle to raw bytes in-memory (bypasses FileUtils path resolution). */
    private static byte[] bundleToBytes(Bundle bundle) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Bundle.write(bundle, baos);
        return baos.toByteArray();
    }

    /** Build a "valid" zip in-memory whose meta.bundle encodes the given version. */
    private static byte[] buildValidZip(String slotName, int version) throws IOException {
        Bundle meta = new Bundle();
        meta.put("name", slotName);
        meta.put("version", version);
        meta.put("depth", 5);
        meta.put("level", 7);
        meta.put("hero_class", HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        byte[] metaRaw = bundleToBytes(meta);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        ZipEntry e = new ZipEntry(META_FILE);
        zos.putNextEntry(e);
        zos.write(metaRaw);
        zos.closeEntry();
        // also include a game bundle so the slot has content
        ZipEntry game = new ZipEntry("game.dat");
        zos.putNextEntry(game);
        zos.write("dummy-game-data".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    /** Build a zip in-memory with arbitrary (entryName, bytes) pairs. */
    private static byte[] buildZipInMemory(Object... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        for (int i = 0; i < entries.length; i += 2) {
            String entryName = (String) entries[i];
            byte[] content = entries[i + 1] instanceof byte[]
                    ? (byte[]) entries[i + 1]
                    : ((String) entries[i + 1]).getBytes(StandardCharsets.UTF_8);
            ZipEntry e = new ZipEntry(entryName);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    /**
     * Hand-rolled minimal zip byte constructor using STORED method (no
     * compression). Used for the duplicate-entry test because
     * {@link ZipOutputStream} refuses duplicate names at write time.
     *
     * <p>Args are (name1, bytes1, name2, bytes2, ...). Two consecutive pairs
     * with the same name produce a duplicate-entry zip that
     * {@link java.util.zip.ZipInputStream} will yield twice.</p>
     *
     * <p>ZIP uses little-endian; {@link java.io.DataOutputStream} defaults to
     * big-endian, so we hand-write LE shorts/ints.</p>
     */
    private static byte[] buildRawZipWithDuplicate(Object... entries) throws IOException {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("odd entries");
        int n = entries.length / 2;
        String[] names = new String[n];
        byte[][] datas = new byte[n][];
        long[] crcs = new long[n];
        for (int i = 0; i < n; i++) {
            names[i] = (String) entries[2 * i];
            datas[i] = entries[2 * i + 1] instanceof byte[]
                    ? (byte[]) entries[2 * i + 1]
                    : ((String) entries[2 * i + 1]).getBytes(StandardCharsets.UTF_8);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(datas[i]);
            crcs[i] = crc.getValue();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long[] localOffsets = new long[n];

        // local file headers + data
        for (int i = 0; i < n; i++) {
            localOffsets[i] = baos.size();
            byte[] nameBytes = names[i].getBytes(StandardCharsets.UTF_8);
            writeLEInt(baos, 0x04034b50);             // local file header sig
            writeLEShort(baos, 20);                   // version needed
            writeLEShort(baos, 0);                    // general purpose flag
            writeLEShort(baos, 0);                    // compression method (stored)
            writeLEShort(baos, 0);                    // mod time
            writeLEShort(baos, 0);                    // mod date
            writeLEInt(baos, (int) crcs[i]);          // CRC-32
            writeLEInt(baos, datas[i].length);        // compressed size
            writeLEInt(baos, datas[i].length);        // uncompressed size
            writeLEShort(baos, nameBytes.length);     // filename length
            writeLEShort(baos, 0);                    // extra field length
            baos.write(nameBytes);
            baos.write(datas[i]);
        }

        // central directory
        long cdStart = baos.size();
        for (int i = 0; i < n; i++) {
            byte[] nameBytes = names[i].getBytes(StandardCharsets.UTF_8);
            writeLEInt(baos, 0x02014b50);             // central directory sig
            writeLEShort(baos, 20);                   // version made by
            writeLEShort(baos, 20);                   // version needed
            writeLEShort(baos, 0);                    // general purpose flag
            writeLEShort(baos, 0);                    // compression method (stored)
            writeLEShort(baos, 0);                    // mod time
            writeLEShort(baos, 0);                    // mod date
            writeLEInt(baos, (int) crcs[i]);          // CRC
            writeLEInt(baos, datas[i].length);        // compressed size
            writeLEInt(baos, datas[i].length);        // uncompressed size
            writeLEShort(baos, nameBytes.length);     // filename length
            writeLEShort(baos, 0);                    // extra field length
            writeLEShort(baos, 0);                    // comment length
            writeLEShort(baos, 0);                    // disk number
            writeLEShort(baos, 0);                    // internal attrs
            writeLEInt(baos, 0);                      // external attrs
            writeLEInt(baos, (int) localOffsets[i]);  // local header offset
            baos.write(nameBytes);
        }

        long cdSize = baos.size() - cdStart;
        // end of central directory
        writeLEInt(baos, 0x06054b50);                 // EOCD sig
        writeLEShort(baos, 0);                        // disk number
        writeLEShort(baos, 0);                        // disk with CD
        writeLEShort(baos, n);                        // entries on this disk
        writeLEShort(baos, n);                        // total entries
        writeLEInt(baos, (int) cdSize);               // CD size
        writeLEInt(baos, (int) cdStart);              // CD offset
        writeLEShort(baos, 0);                        // comment length

        return baos.toByteArray();
    }

    private static void writeLEShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void writeLEInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    public void export_then_import_round_trip_preserves_slot_files_and_meta() throws IOException {
        // Arrange: a real slot written through the production file API
        writeSlot("alpha", HeroClass.MAGE, 12, 9, Game.versionCode,
                "game.dat", "game-data-alpha",
                "depth12.dat", "depth12-data");

        assertTrue("slot should exist before export", slotDirExists("alpha"));

        // Act 1: export to zip bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SaveSlotService.exportToStream("alpha", out);
        byte[] zipBytes = out.toByteArray();
        assertTrue("export zip should be non-empty", zipBytes.length > 0);

        // Act 2: delete the original slot
        SaveSlotService.deleteSlot("alpha");
        assertFalse("slot should be gone after delete", slotDirExists("alpha"));

        // Act 3: import the zip back
        ByteArrayInputStream in = new ByteArrayInputStream(zipBytes);
        SlotImportResult result = SaveSlotService.importFromStream(in, "alpha");
        assertTrue("import should succeed: " + result.message, result.ok);
        assertEquals("alpha", result.name);
        assertEquals(Game.versionCode, result.meta.version);
        assertEquals(12, result.meta.depth);
        assertEquals(9, result.meta.level);
        assertEquals(HeroClass.MAGE, result.meta.heroClass);

        // Act 4: commit (no overwrite needed because we deleted)
        boolean committed = SaveSlotService.commitImport(result, "alpha", false);
        assertTrue("commit should succeed", committed);

        // Assert: file list matches what we wrote
        assertTrue(slotDirExists("alpha"));
        assertTrue(slotFileExists("alpha", META_FILE));
        assertTrue(slotFileExists("alpha", "game.dat"));
        assertTrue(slotFileExists("alpha", "depth12.dat"));

        // meta should be rewritten with the final name (which is also "alpha" here)
        SaveSlotMeta roundTrip = SaveSlotService.readMeta("alpha");
        assertNotNull(roundTrip);
        assertEquals("alpha", roundTrip.name);
        assertEquals(12, roundTrip.depth);
        assertEquals(9, roundTrip.level);
        assertEquals(Game.versionCode, roundTrip.version);
        assertEquals(HeroClass.MAGE, roundTrip.heroClass);

        // no staging/tmp/bak leftovers
        ArrayList<String> rootListing = listSaveSlotsRoot();
        for (String child : rootListing) {
            assertFalse("leftover leaked: " + child, child.startsWith(".import-"));
            assertFalse("leftover leaked: " + child, child.endsWith(".tmp"));
            assertFalse("leftover leaked: " + child, child.endsWith(".bak"));
        }
    }

    @Test
    public void import_rejects_version_mismatch_and_cleans_staging() throws IOException {
        byte[] zip = buildValidZip("beta", Game.versionCode - 1);

        ByteArrayInputStream in = new ByteArrayInputStream(zip);
        SlotImportResult result = SaveSlotService.importFromStream(in, "beta");

        assertFalse("version mismatch should fail", result.ok);
        assertEquals("version_mismatch", result.message);
        assertNull(result.stagingRelPath);

        // staging must be cleaned up
        for (String child : listSaveSlotsRoot()) {
            assertFalse("staging leaked: " + child, child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_dotdot_path() throws IOException {
        byte[] zip = buildZip("../evil.txt", "pwned");
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "gamma");
        assertFalse(r.ok);
        assertEquals("invalid_zip_entry", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
        // ensure evil.txt wasn't written above the local root
        assertFalse(new File(localRoot, "../evil.txt").exists());
    }

    @Test
    public void import_rejects_subdir_path() throws IOException {
        byte[] zip = buildZip("subdir/file.txt", "pwned");
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "delta");
        assertFalse(r.ok);
        assertEquals("invalid_zip_entry", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_windows_drive_path() throws IOException {
        byte[] zip = buildZip("C:\\evil.txt", "pwned");
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "epsilon");
        assertFalse(r.ok);
        assertEquals("invalid_zip_entry", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_colon_in_name() throws IOException {
        byte[] zip = buildZip("evil:file.txt", "pwned");
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "zeta");
        assertFalse(r.ok);
        assertEquals("invalid_zip_entry", r.message);
    }

    @Test
    public void import_rejects_too_many_entries() throws IOException {
        // 65 entries (1 meta + 65 dummy) exceeds MAX_ENTRY_COUNT=64
        Bundle meta = new Bundle();
        meta.put("name", "eta");
        meta.put("version", Game.versionCode);
        meta.put("depth", 1);
        meta.put("level", 1);
        meta.put("hero_class", HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        byte[] metaRaw = bundleToBytes(meta);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        zos.putNextEntry(new ZipEntry(META_FILE));
        zos.write(metaRaw);
        zos.closeEntry();
        for (int i = 0; i < 65; i++) {
            zos.putNextEntry(new ZipEntry("file" + i + ".dat"));
            zos.write(("data-" + i).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        zos.finish();
        zos.close();

        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(baos.toByteArray()), "eta");
        assertFalse(r.ok);
        assertEquals("too_many_entries", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_total_bytes_exceeded() throws IOException {
        Bundle meta = new Bundle();
        meta.put("name", "theta");
        meta.put("version", Game.versionCode);
        meta.put("depth", 1);
        meta.put("level", 1);
        meta.put("hero_class", HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        byte[] metaRaw = bundleToBytes(meta);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        zos.putNextEntry(new ZipEntry(META_FILE));
        zos.write(metaRaw);
        zos.closeEntry();

        // 65MB of highly compressible data
        zos.putNextEntry(new ZipEntry("big.dat"));
        byte[] chunk = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < 65; i++) {
            zos.write(chunk);
        }
        zos.closeEntry();

        zos.finish();
        zos.close();

        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(baos.toByteArray()), "theta");
        assertFalse(r.ok);
        assertEquals("zip_too_large", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void commit_import_without_overwrite_refuses_existing_slot() throws IOException {
        // pre-existing slot "iota" with marker file content
        writeSlot("iota", HeroClass.WARRIOR, 1, 1, Game.versionCode,
                "game.dat", "original-iota");

        // build a valid import zip for the same name
        byte[] zip = buildValidZip("iota", Game.versionCode);
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "iota");
        assertTrue(r.ok);
        assertTrue("conflict should be detected", r.conflict);

        // attempt commit without overwrite -> must fail
        boolean committed = SaveSlotService.commitImport(r, "iota", false);
        assertFalse("commit without overwrite must fail", committed);

        // Original slot must still contain ORIGINAL bytes (not the imported ones).
        assertEquals("original-iota", readSlotFileUtf8("iota", "game.dat"));

        // Staging must still exist (caller can cancel or retry).
        assertTrue("staging must remain after non-overwrite refusal",
                FileUtils.dirExists(r.stagingRelPath));

        // Clean up staging so it doesn't leak into the next test.
        SaveSlotService.cancelImport(r);
    }

    @Test
    public void commit_import_overwrite_restores_or_replaces_atomically() throws IOException {
        // pre-existing slot "kappa" with original content
        writeSlot("kappa", HeroClass.WARRIOR, 1, 1, Game.versionCode,
                "game.dat", "original-kappa");

        // import a fresh zip with the same name. buildValidZip writes game.dat
        // with a different marker so we can prove the slot was actually replaced.
        byte[] zip = buildValidZip("kappa", Game.versionCode);
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "kappa");
        assertTrue(r.ok);
        assertTrue(r.conflict);

        boolean committed = SaveSlotService.commitImport(r, "kappa", true);
        assertTrue("overwrite commit should succeed", committed);

        // Slot's game.dat must now be the IMPORTED bytes, not the original.
        String gameData = readSlotFileUtf8("kappa", "game.dat");
        assertEquals("dummy-game-data", gameData);

        // No leftover staging/.tmp/.bak/.import-complete markers anywhere under save_slots/
        ArrayList<String> listing = listSaveSlotsRoot();
        Set<String> leftover = new HashSet<>();
        for (String child : listing) {
            if (child.startsWith(".import-")) leftover.add(child);
            if (child.endsWith(".tmp")) leftover.add(child);
            if (child.endsWith(".bak")) leftover.add(child);
            if (child.endsWith(".import-complete")) leftover.add(child);
        }
        // Also check inside the live slot dir
        for (String f : listSlotFiles("kappa")) {
            if (".import-complete".equals(f)) leftover.add("kappa/.import-complete");
        }
        assertTrue("leftover artifacts: " + leftover, leftover.isEmpty());

        // slot still exists and has a valid meta
        SaveSlotMeta meta = SaveSlotService.readMeta("kappa");
        assertNotNull(meta);
        assertEquals("kappa", meta.name);
    }

    @Test
    public void cleanup_leftovers_removes_staging_tmp_bak_but_not_real_slots() throws IOException {
        // Build a "real" slot
        writeSlot("lambda", HeroClass.ROGUE, 3, 3, Game.versionCode,
                "game.dat", "lambda-data");

        // Build a stale staging dir directly under save_slots/
        FileUtils.getFileHandle(SLOT_ROOT + ".import-deadbeef").mkdirs();
        // .tmp leftover tied to a real slot
        FileUtils.getFileHandle(SLOT_ROOT + "lambda.tmp").mkdirs();
        // .bak leftover whose live slot is healthy (overwrite completed)
        writeSlot("lambda.bak", HeroClass.ROGUE, 1, 1, Game.versionCode);
        // Stamp commit marker in live slot to signal "overwrite completed"
        try (OutputStream os = FileUtils.getFileHandle(SLOT_ROOT + "lambda/.import-complete").write(false)) {
            os.write(1);
        }

        SaveSlotIO.cleanupLeftovers();

        ArrayList<String> after = listSaveSlotsRoot();
        // real slot remains
        assertTrue("real slot must survive: " + after, after.contains("lambda"));
        // staging gone
        for (String child : after) {
            assertFalse("staging leaked: " + child, child.startsWith(".import-"));
            assertFalse(".tmp leaked: " + child, child.endsWith(".tmp"));
        }
        // .bak with marker-present live slot -> deleted
        assertFalse(".bak should be deleted: " + after, after.contains("lambda.bak"));
        // .import-complete marker inside live slot also dropped
        for (String f : listSlotFiles("lambda")) {
            assertFalse(".import-complete leaked into live slot: " + f,
                    ".import-complete".equals(f));
        }
    }

    @Test
    public void cleanup_leftovers_restores_bak_when_live_slot_missing() throws IOException {
        // live slot "mu" is absent, but .bak exists -> restore .bak as mu
        writeSlot("mu.bak", HeroClass.WARRIOR, 2, 2, Game.versionCode,
                "game.dat", "mu-backup-data");

        SaveSlotIO.cleanupLeftovers();

        assertTrue("bak should be restored as live slot", slotDirExists("mu"));
        assertFalse("bak should be gone after restore", slotDirExists("mu.bak"));
        // contents of former .bak are now in live slot
        SaveSlotMeta meta = SaveSlotService.readMeta("mu");
        assertNotNull(meta);
        // NOTE: readMeta uses the dir name as fallback; we wrote name=mu.bak earlier
        // because we used writeSlot("mu.bak"). After restore, the dir is "mu" so the
        // fallback will be "mu", but the stored bundle field is whatever writeSlot wrote.
        // We don't assert exact name here — only that the dir was promoted.
        assertEquals(2, meta.depth);
    }

    @Test
    public void cleanup_leftovers_restores_bak_when_live_slot_lacks_marker() throws IOException {
        // Crash recovery scenario: the import got far enough to create `mu` with
        // the new content, but the `.import-complete` marker never landed
        // (or was lost). cleanupLeftovers must treat this as "overwrite did not
        // complete" and restore the prior `.bak`.
        writeSlot("mu", HeroClass.WARRIOR, 99, 99, Game.versionCode,
                "game.dat", "new-import-data");
        writeSlot("mu.bak", HeroClass.WARRIOR, 2, 2, Game.versionCode,
                "game.dat", "old-backup-data");
        // Deliberately do NOT stamp `.import-complete` inside live `mu`.

        SaveSlotIO.cleanupLeftovers();

        // bak should be gone (consumed by restore)
        assertFalse("bak should be gone after restore", slotDirExists("mu.bak"));
        // live slot must now hold the OLD backup content, not the partial new import
        assertEquals("old-backup-data", readSlotFileUtf8("mu", "game.dat"));
        // No marker should be present on the restored slot
        for (String f : listSlotFiles("mu")) {
            assertFalse(".import-complete leaked: " + f, ".import-complete".equals(f));
        }
    }

    @Test
    public void import_rejects_missing_meta_bundle() throws IOException {
        // zip with only a game.dat, no meta.bundle
        byte[] zip = buildZip("game.dat", "no-meta-here");
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "nu");
        assertFalse(r.ok);
        assertEquals("missing_meta", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_empty_zip() throws IOException {
        byte[] zip = buildZip(); // no entries at all
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "xi");
        assertFalse(r.ok);
        assertEquals("missing_meta", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_duplicate_entries() throws IOException {
        // JDK's ZipOutputStream refuses duplicate entry names, so we craft raw
        // zip bytes by hand: STORED method, no compression, two entries with
        // the same filename "dup.dat" plus a valid meta.bundle.
        Bundle meta = new Bundle();
        meta.put("name", "omicron");
        meta.put("version", Game.versionCode);
        meta.put("depth", 1);
        meta.put("level", 1);
        meta.put("hero_class", HeroClass.WARRIOR.name());
        meta.put("saved_at", 1700000000000L);
        byte[] metaRaw = bundleToBytes(meta);

        byte[] zip = buildRawZipWithDuplicate(
                META_FILE, metaRaw,
                "dup.dat", "first".getBytes(StandardCharsets.UTF_8),
                "dup.dat", "second".getBytes(StandardCharsets.UTF_8));

        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "omicron");
        assertFalse(r.ok);
        assertEquals("invalid_zip_entry", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void import_rejects_corrupted_meta_bundle() throws IOException {
        // meta.bundle is junk bytes, not a valid bundle
        byte[] zip = buildZip(META_FILE, new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 'x', 'y', 'z'});
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "pi");
        assertFalse(r.ok);
        assertEquals("meta_read_failed", r.message);
        for (String child : listSaveSlotsRoot()) {
            assertFalse(child.startsWith(".import-"));
        }
    }

    @Test
    public void commit_import_with_missing_staging_returns_false() throws IOException {
        // Build a "valid" import result pointing at a non-existent staging dir
        // (simulating a caller bug or stale result after cleanup).
        // We can't construct SlotImportResult directly (private ctor), so we
        // exercise the path by doing a real import then deleting the staging
        // dir before commit.
        byte[] zip = buildValidZip("rho", Game.versionCode);
        SlotImportResult r = SaveSlotService.importFromStream(
                new ByteArrayInputStream(zip), "rho");
        assertTrue(r.ok);

        // sabotage: delete the staging dir
        FileUtils.deleteDir(r.stagingRelPath);

        boolean committed = SaveSlotService.commitImport(r, "rho", false);
        assertFalse("commit with missing staging must return false", committed);
        assertFalse("no slot should be created", slotDirExists("rho"));
    }
}
