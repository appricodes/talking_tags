package com.appricodes.voicetag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class TagTextTest {
	
	@Test
	public void parsesUtf8TextRecord() {
		byte[] text = "سلام".getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[3 + text.length];
		payload[0] = 2;
		payload[1] = 'f';
		payload[2] = 'a';
		System.arraycopy(text, 0, payload, 3, text.length);
		TagText.Parsed parsed = TagText.parseTextPayload(payload);
		assertEquals("fa", parsed.language);
		assertEquals("سلام", parsed.text);
	}
	
	@Test
	public void emptyPayload() {
		assertEquals("", TagText.parseTextPayload(new byte[0]).text);
	}
	
	@Test
	public void splitsLegacyAsciiDate() throws Exception {
		TagText.Legacy legacy = TagText.splitLegacy("Rice|2026-10-27 10:05");
		assertEquals("Rice", legacy.text);
		long expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-10-27 10:05").getTime();
		assertEquals(Long.valueOf(expected), legacy.writtenAtMillis);
	}
	
	@Test
	public void splitsLegacyPersianDigits() {
		TagText.Legacy legacy = TagText.splitLegacy("برنج|۲۰۲۶-۱۰-۲۷ ۱۰:۰۵");
		assertEquals("برنج", legacy.text);
		assertEquals(TagText.splitLegacy("x|2026-10-27 10:05").writtenAtMillis, legacy.writtenAtMillis);
	}
	
	@Test
	public void keepsPipesThatAreNotDates() {
		TagText.Legacy legacy = TagText.splitLegacy("a|b");
		assertEquals("a|b", legacy.text);
		assertNull(legacy.writtenAtMillis);
	}
}
