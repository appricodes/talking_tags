package com.appricodes.voicetag;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Compact metadata stored in the app's own NDEF external record ("appricodes.com:vt").
 * Layout: version (1 byte), flags (1 byte), [minutes since epoch (4 bytes)], [audio id (8 bytes)].
 */
final class TagMeta {

	static final int VERSION = 1;
	private static final int FLAG_DATE = 1;
	private static final int FLAG_AUDIO = 1 << 1;

	/** Time the tag was written, rounded down to the minute; null when absent. */
	final Long writtenAtMillis;
	/** Random id that names this tag's voice note file; null when absent. */
	final Long audioId;

	TagMeta(Long writtenAtMillis, Long audioId) {
		this.writtenAtMillis = writtenAtMillis;
		this.audioId = audioId;
	}

	byte[] encode() {
		int flags = 0;
		int size = 2;
		if (writtenAtMillis != null) {
			flags |= FLAG_DATE;
			size += 4;
		}
		if (audioId != null) {
			flags |= FLAG_AUDIO;
			size += 8;
		}
		ByteBuffer buf = ByteBuffer.allocate(size);
		buf.put((byte) VERSION);
		buf.put((byte) flags);
		if (writtenAtMillis != null) buf.putInt((int) (writtenAtMillis / 60_000L));
		if (audioId != null) buf.putLong(audioId);
		return buf.array();
	}

	/** Returns null when the payload is not a metadata block this version understands. */
	static TagMeta decode(byte[] payload) {
		if (payload == null || payload.length < 2 || payload[0] != VERSION) return null;
		int flags = payload[1];
		ByteBuffer buf = ByteBuffer.wrap(payload, 2, payload.length - 2);
		try {
			Long date = null;
			Long audio = null;
			if ((flags & FLAG_DATE) != 0) date = (buf.getInt() & 0xFFFFFFFFL) * 60_000L;
			if ((flags & FLAG_AUDIO) != 0) audio = buf.getLong();
			return new TagMeta(date, audio);
		} catch (BufferUnderflowException e) {
			return null;
		}
	}
}
