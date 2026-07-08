package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import com.watabou.noosa.Game;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M12b: {@link ModInstaller} platform-neutral zip install. Drives the package-private
 * {@link ModInstaller#installInto(FileHandle, java.io.InputStream, ModImporter.ImportCallback)}
 * test seam against a temp {@code mods_user/} dir (mirrors {@code LuaEngineExternalLoadTest}'s
 * {@code new FileHandle(tmpDir)} pattern). The public {@code installFromStream} resolves a live
 * {@code Gdx.files.local} root and is not exercised here.
 *
 * <p>{@link Game#versionCode} is a static int and does not need a headless libgdx app; the installer
 * only reads it for the {@code spd_version} gate. Each test gets a fresh temp dir so filesystem
 * state never leaks between cases.
 */
public class ModInstallerTest {

	private static final int TEST_VERSION_CODE = 896;
	private static int savedVersionCode;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void setVersionCode() {
		savedVersionCode = Game.versionCode;
		Game.versionCode = TEST_VERSION_CODE;
	}

	@AfterClass
	public static void restoreVersionCode() {
		Game.versionCode = savedVersionCode;
	}

	@Before
	public void ensureDefault() {
		// Defensive: restore in case a prior test in another class left a stale value.
		Game.versionCode = TEST_VERSION_CODE;
	}

	// ---- success cases -------------------------------------------------------

	@Test
	public void install_flatZip_modJsonAtRoot_success() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("imp_a", TEST_VERSION_CODE));
		entries.put("scripts/items/x.lua", "-- item".getBytes(StandardCharsets.UTF_8));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("imp_a", cb.ok);
		assertNull("expected no error, got " + cb.err, cb.err);
		File installed = new File(modsUser, "imp_a");
		assertTrue("mods_user/imp_a/ must exist", installed.isDirectory());
		assertTrue("mod.json must be promoted", new File(installed, "mod.json").exists());
		assertTrue("nested script must survive", new File(installed, "scripts/items/x.lua").exists());
		assertNoStaging(modsUser);
	}

	@Test
	public void install_zipWithTopDir_success() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mymod/mod.json", modJson("mymod", TEST_VERSION_CODE));
		entries.put("mymod/scripts/items/y.lua", "-- y".getBytes(StandardCharsets.UTF_8));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("mymod", cb.ok);
		assertTrue(new File(modsUser, "mymod/mod.json").exists());
		assertTrue(new File(modsUser, "mymod/scripts/items/y.lua").exists());
		assertFalse("top dir must not leak as a sibling staging name",
				new File(modsUser, "mymod/mymod").exists());
		assertNoStaging(modsUser);
	}

	@Test
	public void install_topDirDiffersFromId_renamesToId() throws Exception {
		// zip packed under top dir "mymod/" but manifest declares id=imp_b → installs as imp_b/
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mymod/mod.json", modJson("imp_b", TEST_VERSION_CODE));
		entries.put("mymod/init.lua", "-- init".getBytes(StandardCharsets.UTF_8));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("imp_b", cb.ok);
		assertTrue("target dir must be the manifest id, not the zip top-dir name",
				new File(modsUser, "imp_b/mod.json").exists());
		assertFalse("zip top-dir name must not survive as the install dir",
				new File(modsUser, "mymod").exists());
	}

	@Test
	public void install_directoryEntries_created() throws Exception {
		// Explicit directory entry "scripts/items/" plus a file inside it → dir is mkdirs'd, file lands.
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("dirmod", TEST_VERSION_CODE));
		entries.put("scripts/items/", null);   // directory entry (trailing "/")
		entries.put("scripts/items/a.lua", "-- a".getBytes(StandardCharsets.UTF_8));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("dirmod", cb.ok);
		assertTrue(new File(modsUser, "dirmod/scripts/items").isDirectory());
		assertTrue(new File(modsUser, "dirmod/scripts/items/a.lua").exists());
	}

	// ---- failure cases -------------------------------------------------------

	@Test
	public void install_pathTraversal_rejected() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("evil_a", TEST_VERSION_CODE));
		entries.put("../evil.lua", "pwned".getBytes(StandardCharsets.UTF_8));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("invalid_zip", cb.err);
		assertNull(cb.ok);
		assertFalse("no mod dir must be created on failure", new File(modsUser, "evil_a").exists());
		assertFalse("traversal target must not escape staging",
				new File(modsUser.getParentFile(), "evil.lua").exists());
		assertNoStaging(modsUser);
	}

	@Test
	public void install_pathTraversalDir_rejected() throws Exception {
		// Malicious directory entry "../evil/" must be validated like any other entry.
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("evil_b", TEST_VERSION_CODE));
		entries.put("../evil/", null);   // directory entry attempting to escape
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("invalid_zip", cb.err);
		assertFalse("traversal dir must not be created outside staging",
				new File(modsUser.getParentFile(), "evil").exists());
		assertFalse(new File(modsUser, "evil_b").exists());
		assertNoStaging(modsUser);
	}

	@Test
	public void install_missingModJson_fails() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("readme.txt", "no manifest here".getBytes(StandardCharsets.UTF_8));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("bad_manifest", cb.err);
		assertNoStaging(modsUser);
	}

	@Test
	public void install_versionMismatch_fails() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("vmod", TEST_VERSION_CODE + 1));   // wrong spd_version
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("version_mismatch", cb.err);
		assertFalse(new File(modsUser, "vmod").exists());
		assertNoStaging(modsUser);
	}

	@Test
	public void install_alreadyExists_fails() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		// Pre-existing mod dir — must NOT be overwritten.
		File existing = new File(modsUser, "imp_a");
		existing.mkdirs();
		byte[] sentinel = "ORIGINAL".getBytes(StandardCharsets.UTF_8);
		java.nio.file.Files.write(new File(existing, "mod.json").toPath(), sentinel);

		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("imp_a", TEST_VERSION_CODE));
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("already_exists", cb.err);
		byte[] after = java.nio.file.Files.readAllBytes(new File(existing, "mod.json").toPath());
		assertEquals("existing mod must be untouched", "ORIGINAL", new String(after, StandardCharsets.UTF_8));
		assertNoStaging(modsUser);
	}

	@Test
	public void install_tooManyEntries_fails() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("mod.json", modJson("bigmod", TEST_VERSION_CODE));
		// MAX_ENTRY_COUNT == 256; push past it. Directory entries count too (verified by mixing).
		for (int i = 0; i <= ModInstaller.MAX_ENTRY_COUNT; i++) {
			entries.put("f" + i + ".txt", ("f" + i).getBytes(StandardCharsets.UTF_8));
		}
		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser), zipStream(entries), cb);

		assertEquals("too_many_entries", cb.err);
		assertFalse(new File(modsUser, "bigmod").exists());
		assertNoStaging(modsUser);
	}

	@Test
	public void install_zipTooLarge_fails() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		long overCap = ModInstaller.MAX_TOTAL_BYTES + 1;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ZipOutputStream zos = new ZipOutputStream(baos);
		zos.putNextEntry(new ZipEntry("mod.json"));
		zos.write(modJson("fatmod", TEST_VERSION_CODE));
		zos.closeEntry();
		zos.putNextEntry(new ZipEntry("blob.bin"));
		// Write >64MB of compressible data in 1MB chunks; the zip itself stays small (deflate),
		// but on read ZipInputStream inflates it and the byte counter trips the cap.
		byte[] chunk = new byte[1024 * 1024];
		java.util.Arrays.fill(chunk, (byte) 'A');
		long remaining = overCap;
		while (remaining > 0) {
			int n = (int) Math.min(chunk.length, remaining);
			zos.write(chunk, 0, n);
			remaining -= n;
		}
		zos.closeEntry();
		zos.close();

		CapturingCb cb = new CapturingCb();
		ModInstaller.installInto(new FileHandle(modsUser),
				new ByteArrayInputStream(baos.toByteArray()), cb);

		assertEquals("zip_too_large", cb.err);
		assertFalse(new File(modsUser, "fatmod").exists());
		assertNoStaging(modsUser);
	}

	// ---- helpers -------------------------------------------------------------

	/** Build an in-memory zip. A key ending in {@code "/"} is a directory entry (null content). */
	private static ByteArrayInputStream zipStream(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ZipOutputStream zos = new ZipOutputStream(baos);
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			zos.putNextEntry(new ZipEntry(e.getKey()));
			if (!e.getKey().endsWith("/") && e.getValue() != null) zos.write(e.getValue());
			zos.closeEntry();
		}
		zos.close();
		return new ByteArrayInputStream(baos.toByteArray());
	}

	private static byte[] modJson(String id, int spdVersion) {
		return ("{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"0.1\","
				+ "\"spd_version\":" + spdVersion + "}").getBytes(StandardCharsets.UTF_8);
	}

	private static void assertNoStaging(File modsUser) {
		for (String name : modsUser.list()) {
			assertFalse("staging dir leaked: " + name, name.startsWith(ModInstaller.STAGING_PREFIX));
		}
	}

	private static final class CapturingCb implements ModImporter.ImportCallback {
		String ok;
		String err;
		boolean cancelled;

		@Override public void onSuccess(String modId) { this.ok = modId; }
		@Override public void onError(String code) { this.err = code; }
		@Override public void onCancel() { this.cancelled = true; }
	}
}
