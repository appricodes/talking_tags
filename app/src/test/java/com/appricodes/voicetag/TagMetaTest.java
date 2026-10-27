package com.appricodes.voicetag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TagMetaTest {
	
	@Test
	public void roundTripWithDateAndAudio() {
		long minute = 1_793_000_000_000L / 60_000L * 60_000L;
		TagMeta decoded = TagMeta.decode(new TagMeta(minute + 59_999L, 0x8123456789ABCDEFL).encode());
		assertEquals(Long.valueOf(minute), decoded.writtenAtMillis);
		assertEquals(Long.valueOf(0x8123456789ABCDEFL), decoded.audioId);
	}
	
	@Test
	public void sizes() {
		assertEquals(2, new TagMeta(null, null).encode().length);
		assertEquals(6, new TagMeta(0L, null).encode().length);
		assertEquals(14, new TagMeta(0L, 1L).encode().length);
	}
	
	@Test
	public void audioOnly() {
		TagMeta decoded = TagMeta.decode(new TagMeta(null, 42L).encode());
		assertNull(decoded.writtenAtMillis);
		assertEquals(Long.valueOf(42L), decoded.audioId);
	}
	
	@Test
	public void rejectsUnknownOrTruncated() {
		assertNull(TagMeta.decode(new byte[] {2, 0}));
		assertNull(TagMeta.decode(new byte[] {1}));
		assertNull(TagMeta.decode(new byte[] {1, 3, 0, 0}));
	}
}
