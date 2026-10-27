package com.appricodes.voicetag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class TagContentTest {
	
	private static void tlv(ByteArrayOutputStream out, int type, byte[] value) {
		out.write(type >> 8);
		out.write(type);
		out.write(value.length >> 8);
		out.write(value.length);
		out.write(value, 0, value.length);
	}
	
	@Test
	public void parsesWifiCredential() {
		ByteArrayOutputStream credential = new ByteArrayOutputStream();
		tlv(credential, 0x1026, new byte[] {1});
		tlv(credential, 0x1045, "Home".getBytes(StandardCharsets.UTF_8));
		tlv(credential, 0x1003, new byte[] {0, 0x20});
		tlv(credential, 0x1027, "secret123".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream token = new ByteArrayOutputStream();
		tlv(token, 0x104A, new byte[] {0x10});
		tlv(token, 0x100E, credential.toByteArray());
		
		TagContent wifi = TagContent.parseWifi(token.toByteArray());
		assertEquals(TagContent.Kind.WIFI, wifi.kind);
		assertEquals("Home", wifi.value);
		assertEquals("secret123", wifi.wifiKey);
		assertEquals(TagContent.WIFI_AUTH_WPA2_PERSONAL, wifi.wifiAuth);
	}
	
	@Test
	public void ignoresTruncatedWifi() {
		TagContent wifi = TagContent.parseWifi(new byte[] {0x10, 0x45, 0, 9, 'a'});
		assertEquals("", wifi.value);
		assertNull(wifi.wifiKey);
	}
	
	@Test
	public void parsesVcard() {
		TagContent c = TagContent.parseVcard("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nTEL;TYPE=CELL:+49 123\r\n"
				+ "EMAIL:jane@example.com\r\nEND:VCARD");
		assertEquals(TagContent.Kind.CONTACT, c.kind);
		assertEquals("Jane Doe", c.value);
		assertEquals("+49 123", c.contactPhone);
		assertEquals("jane@example.com", c.contactEmail);
	}
	
	@Test
	public void vcardPrefersFormattedName() {
		assertEquals("Dr. Jane Doe", TagContent.parseVcard("FN:Dr. Jane Doe\nN:Doe;Jane\n").value);
	}
}
