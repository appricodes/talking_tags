package com.appricodes.voicetag;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.TagTechnology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads and writes tags. A tag holds an optional text record (readable by any NFC app) followed
 * by an optional external record with {@link TagMeta}. Blocking methods must run off the UI thread.
 */
final class TagIo {

	private static final String META_DOMAIN = "appricodes.com";
	private static final String META_TYPE = "vt";
	private static final byte[] META_TYPE_BYTES =
			(META_DOMAIN + ":" + META_TYPE).getBytes(StandardCharsets.US_ASCII);

	enum Failure { TAG_LOST, TOO_LARGE, READ_ONLY, NOT_SUPPORTED, IO }

	static final class TagException extends Exception {
		final Failure failure;

		TagException(Failure failure) {
			this(failure, null);
		}

		TagException(Failure failure, Throwable cause) {
			super(failure.name(), cause);
			this.failure = failure;
		}
	}

	/** A message ready to write, and whether the voice note id fit on the tag. */
	static final class Plan {
		final NdefMessage message;
		final boolean includesAudio;

		Plan(NdefMessage message, boolean includesAudio) {
			this.message = message;
			this.includesAudio = includesAudio;
		}
	}

	private TagIo() {}

	/** Reads the message cached when the tag was discovered; does no RF activity. */
	static TagData read(Tag tag) {
		Ndef ndef = Ndef.get(tag);
		return read(tag, ndef != null ? ndef.getCachedNdefMessage() : null);
	}

	/** Describes the tag as if it held {@code message}, e.g. right after writing it. */
	static TagData read(Tag tag, NdefMessage message) {
		TagData data = new TagData();
		data.uid = tag.getId();
		Ndef ndef = Ndef.get(tag);
		if (ndef != null) {
			data.ndef = true;
			data.writable = ndef.isWritable();
			data.maxSize = ndef.getMaxSize();
			try {
				data.canLock = data.writable && ndef.canMakeReadOnly();
			} catch (RuntimeException e) {
				data.canLock = false;
			}
		} else if (NdefFormatable.get(tag) != null) {
			data.formatable = true;
			data.writable = true;
		}
		if (message != null) parseRecords(data, message.getRecords());
		return data;
	}

	private static void parseRecords(TagData data, NdefRecord[] records) {
		String rawText = null;
		TagMeta meta = null;
		for (NdefRecord record : records) {
			short tnf = record.getTnf();
			if (tnf == NdefRecord.TNF_EMPTY) continue;
			if (rawText == null && tnf == NdefRecord.TNF_WELL_KNOWN
					&& Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
				TagText.Parsed parsed = TagText.parseTextPayload(record.getPayload());
				rawText = parsed.text;
				data.language = parsed.language;
				continue;
			}
			if (meta == null && tnf == NdefRecord.TNF_EXTERNAL_TYPE
					&& Arrays.equals(record.getType(), META_TYPE_BYTES)) {
				meta = TagMeta.decode(record.getPayload());
				if (meta != null) continue;
			}
			data.foreign.add(TagContent.fromRecord(record));
		}
		if (rawText != null) {
			if (meta == null) {
				TagText.Legacy legacy = TagText.splitLegacy(rawText);
				data.text = legacy.text.trim();
				data.writtenAtMillis = legacy.writtenAtMillis;
			} else {
				data.text = rawText.trim();
			}
		}
		if (meta != null) {
			data.writtenAtMillis = meta.writtenAtMillis;
			data.audioId = meta.audioId;
		}
	}

	/**
	 * Builds the largest message that fits in {@code maxSize} (-1 = unknown). The text always
	 * goes in; then the voice note id, then the date, as space allows. Returns null when even
	 * the text alone is too large.
	 */
	static Plan plan(String text, String language, long nowMillis, Long audioId, int maxSize) {
		NdefRecord textRecord = text.isEmpty() ? null : NdefRecord.createTextRecord(language, text);
		List<TagMeta> candidates = new ArrayList<>();
		if (audioId != null) {
			candidates.add(new TagMeta(nowMillis, audioId));
			candidates.add(new TagMeta(null, audioId));
		}
		candidates.add(new TagMeta(nowMillis, null));
		candidates.add(null);
		for (TagMeta meta : candidates) {
			NdefMessage message = buildMessage(textRecord, meta);
			if (maxSize < 0 || message.getByteArrayLength() <= maxSize) {
				return new Plan(message, meta != null && meta.audioId != null);
			}
		}
		return null;
	}

	/** Bytes the text alone takes on a tag, for the capacity counter. */
	static int textSize(String text, String language) {
		if (text.isEmpty()) return 0;
		return new NdefMessage(NdefRecord.createTextRecord(language, text)).getByteArrayLength();
	}

	private static NdefMessage buildMessage(NdefRecord textRecord, TagMeta meta) {
		List<NdefRecord> records = new ArrayList<>();
		if (textRecord != null) records.add(textRecord);
		if (meta != null) records.add(NdefRecord.createExternal(META_DOMAIN, META_TYPE, meta.encode()));
		if (records.isEmpty()) records.add(emptyRecord());
		return new NdefMessage(records.toArray(new NdefRecord[0]));
	}

	private static NdefRecord emptyRecord() {
		return new NdefRecord(NdefRecord.TNF_EMPTY, null, null, null);
	}

	static void write(Tag tag, NdefMessage message) throws TagException {
		TagTechnology tech = null;
		try {
			Ndef ndef = Ndef.get(tag);
			if (ndef != null) {
				tech = ndef;
				ndef.connect();
				if (!ndef.isWritable()) throw new TagException(Failure.READ_ONLY);
				if (message.getByteArrayLength() > ndef.getMaxSize()) throw new TagException(Failure.TOO_LARGE);
				ndef.writeNdefMessage(message);
			} else {
				NdefFormatable formatable = NdefFormatable.get(tag);
				if (formatable == null) throw new TagException(Failure.NOT_SUPPORTED);
				tech = formatable;
				formatable.connect();
				formatable.format(message);
			}
		} catch (TagLostException | SecurityException e) {
			// SecurityException: the Tag object is stale because another tag was discovered since.
			throw new TagException(Failure.TAG_LOST, e);
		} catch (IOException | FormatException e) {
			throw new TagException(Failure.IO, e);
		} finally {
			closeQuietly(tech);
		}
	}

	/** Replaces the content with a single empty record and returns the message written. */
	static NdefMessage erase(Tag tag) throws TagException {
		NdefMessage empty = new NdefMessage(emptyRecord());
		write(tag, empty);
		return empty;
	}

	/** Makes the tag permanently read-only. */
	static void lock(Tag tag) throws TagException {
		Ndef ndef = Ndef.get(tag);
		if (ndef == null) throw new TagException(Failure.NOT_SUPPORTED);
		try {
			ndef.connect();
			if (!ndef.makeReadOnly()) throw new TagException(Failure.NOT_SUPPORTED);
		} catch (TagLostException | SecurityException e) {
			throw new TagException(Failure.TAG_LOST, e);
		} catch (IOException e) {
			throw new TagException(Failure.IO, e);
		} finally {
			closeQuietly(ndef);
		}
	}

	private static void closeQuietly(TagTechnology tech) {
		if (tech == null) return;
		try {
			tech.close();
		} catch (IOException | RuntimeException ignored) {
		}
	}
}
