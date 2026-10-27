package com.appricodes.voicetag;

import android.nfc.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** What the app knows about one tag: its capabilities and its decoded content. */
final class TagData {

	byte[] uid;
	/** Tag is already NDEF formatted. */
	boolean ndef;
	/** Blank tag that can be formatted on the first write. */
	boolean formatable;
	/** Text can be written (NDEF writable, or formatable). */
	boolean writable;
	boolean canLock;
	/** Maximum NDEF message size in bytes, or -1 when unknown. */
	int maxSize = -1;

	String text = "";
	String language;
	Long writtenAtMillis;
	Long audioId;
	/** Records written by another app (links, contacts, Wi-Fi...). */
	final List<TagContent> foreign = new ArrayList<>();
	
	boolean hasForeignData() {
		return !foreign.isEmpty();
	}

	boolean isSameTag(Tag tag) {
		return tag != null && Arrays.equals(uid, tag.getId());
	}

	/** True when there is anything on the tag that erasing would remove. */
	boolean hasStoredData() {
		return !text.isEmpty() || writtenAtMillis != null || audioId != null || hasForeignData();
	}
}
