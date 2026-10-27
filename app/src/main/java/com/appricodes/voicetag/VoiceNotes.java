package com.appricodes.voicetag;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Voice note files in the app's private storage (the primary store). A file is named after the
 * random id written on the tag ("t_...") or, when the tag can't hold that id, after the tag's
 * serial number ("u_...").
 */
final class VoiceNotes {

	static final String EXTENSION = ".m4a";
	private static final String DIR = "voice_notes";
	private static final String PREFIX_TAG_ID = "t_";
	private static final String PREFIX_UID = "u_";
	private static final Pattern FILE_NAME =
			Pattern.compile("^(t_[0-9A-F]{16}|u_[0-9A-F]{2,40})\\.m4a$");
	private static final SecureRandom RANDOM = new SecureRandom();

	private VoiceNotes() {}

	static long newAudioId() {
		return RANDOM.nextLong();
	}

	static String keyForAudioId(long audioId) {
		return String.format(Locale.US, PREFIX_TAG_ID + "%016X", audioId);
	}

	/** Returns null when the tag reports no serial number. */
	static String keyForUid(byte[] uid) {
		if (uid == null || uid.length == 0) return null;
		StringBuilder sb = new StringBuilder(PREFIX_UID);
		for (byte b : uid) sb.append(String.format(Locale.US, "%02X", b));
		return sb.toString();
	}

	static boolean isValidFileName(String name) {
		return name != null && FILE_NAME.matcher(name).matches();
	}

	static boolean isUidFile(File file) {
		return file.getName().startsWith(PREFIX_UID);
	}

	static File dir(Context context) {
		File dir = new File(context.getFilesDir(), DIR);
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		return dir;
	}

	static File file(Context context, String key) {
		return new File(dir(context), key + EXTENSION);
	}

	/** The voice note of a tag: by the id on the tag first, then by its serial number. */
	static File find(Context context, TagData data) {
		if (data.audioId != null) {
			File f = file(context, keyForAudioId(data.audioId));
			if (f.exists()) return f;
		}
		String uidKey = keyForUid(data.uid);
		if (uidKey != null) {
			File f = file(context, uidKey);
			if (f.exists()) return f;
		}
		return null;
	}

	static List<File> list(Context context) {
		File[] files = dir(context).listFiles((d, name) -> isValidFileName(name));
		if (files == null) return new ArrayList<>();
		Arrays.sort(files);
		return new ArrayList<>(Arrays.asList(files));
	}

	static long totalSize(List<File> files) {
		long total = 0;
		for (File f : files) total += f.length();
		return total;
	}

	/** Moves a file, replacing the target. */
	static boolean move(File from, File to) {
		if (from.equals(to)) return true;
		//noinspection ResultOfMethodCallIgnored
		to.delete();
		if (from.renameTo(to)) return true;
		try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
			copy(in, out);
		} catch (IOException e) {
			//noinspection ResultOfMethodCallIgnored
			to.delete();
			return false;
		}
		//noinspection ResultOfMethodCallIgnored
		from.delete();
		return true;
	}

	/** Writes all voice notes into a ZIP stream and returns how many were written. */
	static int exportZip(Context context, OutputStream out) throws IOException {
		List<File> files = list(context);
		ZipOutputStream zip = new ZipOutputStream(out);
		for (File f : files) {
			zip.putNextEntry(new ZipEntry(f.getName()));
			try (InputStream in = new FileInputStream(f)) {
				copy(in, zip);
			}
			zip.closeEntry();
		}
		zip.finish();
		zip.flush();
		return files.size();
	}

	/**
	 * Imports an exported ZIP or a single voice note file; other files are ignored.
	 * Existing notes with the same name are replaced. Returns how many notes were imported.
	 */
	static int importFile(Context context, String displayName, InputStream in) throws IOException {
		if (displayName != null && displayName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			int count = 0;
			ZipInputStream zip = new ZipInputStream(in);
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				// Only the base name is used, so entries can't escape the voice notes folder.
				String name = new File(entry.getName()).getName();
				if (!entry.isDirectory() && isValidFileName(name)) {
					save(context, name, zip);
					count++;
				}
			}
			return count;
		}
		if (isValidFileName(displayName)) {
			save(context, displayName, in);
			return 1;
		}
		return 0;
	}

	private static void save(Context context, String name, InputStream in) throws IOException {
		File dir = dir(context);
		File tmp = new File(dir, name + ".tmp");
		try (OutputStream out = new FileOutputStream(tmp)) {
			copy(in, out);
		}
		if (!move(tmp, new File(dir, name))) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			throw new IOException("Could not save " + name);
		}
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[16 * 1024];
		int n;
		while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
	}
}
