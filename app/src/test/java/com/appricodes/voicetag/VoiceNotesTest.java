package com.appricodes.voicetag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceNotesTest {
	
	@Test
	public void keys() {
		assertEquals("t_00000000000000FF", VoiceNotes.keyForAudioId(255));
		assertEquals("t_FFFFFFFFFFFFFFFF", VoiceNotes.keyForAudioId(-1));
		assertEquals("u_04A1FF", VoiceNotes.keyForUid(new byte[] {4, (byte) 0xA1, (byte) 0xFF}));
		assertNull(VoiceNotes.keyForUid(new byte[0]));
	}
	
	@Test
	public void validFileNames() {
		assertTrue(VoiceNotes.isValidFileName(VoiceNotes.keyForAudioId(7) + VoiceNotes.EXTENSION));
		assertTrue(VoiceNotes.isValidFileName("u_04A1FF.m4a"));
		assertFalse(VoiceNotes.isValidFileName("../u_04A1FF.m4a"));
		assertFalse(VoiceNotes.isValidFileName("u_04A1FF.m4a.tmp"));
		assertFalse(VoiceNotes.isValidFileName("t_123.m4a"));
		assertFalse(VoiceNotes.isValidFileName(null));
	}
}
