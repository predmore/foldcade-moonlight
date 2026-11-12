package com.limelight.foldcade;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PluginProtocolTest {
    private Map<String, Object> valid() {
        Map<String, Object> values = new HashMap<>();
        values.put(PluginProtocol.EXTRA + "PROTOCOL", 1);
        values.put(PluginProtocol.EXTRA + "BROKER_AUTHORITY", "app.foldcade.debug.plugins");
        values.put(PluginProtocol.EXTRA + "TITLE", "Game");
        values.put(PluginProtocol.EXTRA + "LAUNCH_TOKEN", "token");
        values.put(PluginProtocol.EXTRA + "TOP_DISPLAY", 0);
        values.put(PluginProtocol.EXTRA + "BOTTOM_DISPLAY", 4);
        values.put(PluginProtocol.GAMESTREAM + "HOST_UUID", "host-uuid");
        values.put(PluginProtocol.GAMESTREAM + "HOST_NAME", "Computer");
        values.put(PluginProtocol.GAMESTREAM + "APP_ID", "42");
        values.put(PluginProtocol.GAMESTREAM + "APP_NAME", "Game");
        return values;
    }

    @Test public void acceptsProtocolOneAndSingleScreen() {
        Map<String, Object> values = valid();
        assertEquals(42, new PluginProtocol.PlayRequest(values).appId);
        values.put(PluginProtocol.EXTRA + "BOTTOM_DISPLAY", -1);
        assertEquals(-1, new PluginProtocol.PlayRequest(values).bottomDisplay);
    }

    @Test public void rejectsMissingAndWrongTypedExtras() {
        for (String key : valid().keySet()) {
            Map<String, Object> values = valid();
            values.remove(key);
            assertThrows(IllegalArgumentException.class, () -> new PluginProtocol.PlayRequest(values));
        }
        Map<String, Object> values = valid();
        values.put(PluginProtocol.EXTRA + "PROTOCOL", "1");
        assertThrows(IllegalArgumentException.class, () -> new PluginProtocol.PlayRequest(values));
    }

    @Test public void rejectsInvalidAppIdsAndDisplaySelections() {
        for (String id : new String[]{"0", "-1", "+1", "2147483648", "1&cancel=1", " 1"}) {
            Map<String, Object> values = valid();
            values.put(PluginProtocol.GAMESTREAM + "APP_ID", id);
            assertThrows(IllegalArgumentException.class, () -> new PluginProtocol.PlayRequest(values));
        }
        Map<String, Object> values = valid();
        values.put(PluginProtocol.EXTRA + "BOTTOM_DISPLAY", 0);
        assertThrows(IllegalArgumentException.class, () -> new PluginProtocol.PlayRequest(values));
    }

    @Test public void keepsCompleteLaunchQueryAndRejectsForbiddenRequests() throws Exception {
        String query = "appid=42&mode=1920x1080x60&rikey=ABCDEF&rikeyid=-42&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&extra=a%26b";
        PluginProtocol.validateQuery("launch", query);
        PluginProtocol.validateQuery("resume", query);
        PluginProtocol.validateQuery("serverinfo", null);
        PluginProtocol.validateQuery("launch", "x".repeat(4096));
        for (String path : new String[]{"pair", "unpair", "appasset", "../cancel"}) {
            assertThrows(PluginProtocol.BrokerException.class, () -> PluginProtocol.validateQuery(path, null));
        }
        for (String bad : new String[]{"?x=1", "a=1#x", "a=1\nx", "a=1\rx", "x".repeat(4097)}) {
            assertThrows(PluginProtocol.BrokerException.class, () -> PluginProtocol.validateQuery("launch", bad));
        }
    }

    @Test public void parsesRepliesAndPreservesProtocolErrorCodes() throws Exception {
        PluginProtocol.requireSuccess(true, null);
        for (String code : new String[]{"not_granted", "unknown_host", "bad_request", "unreachable", "host_error", "internal"}) {
            PluginProtocol.BrokerException error = assertThrows(PluginProtocol.BrokerException.class,
                    () -> PluginProtocol.requireSuccess(false, code));
            assertEquals(code, error.code);
        }
        assertEquals("internal", assertThrows(PluginProtocol.BrokerException.class,
                () -> PluginProtocol.requireSuccess("true", null)).code);
        assertTrue(PluginProtocol.userMessage("not_granted").contains("Settings › Plugins"));
        assertEquals("<root/>", PluginProtocol.responseBody(200, "<root/>"));
        assertThrows(PluginProtocol.BrokerException.class, () -> PluginProtocol.responseBody(500, "<root/>"));
        assertThrows(PluginProtocol.BrokerException.class, () -> PluginProtocol.responseBody(200, null));
        assertThrows(PluginProtocol.BrokerException.class, () -> PluginProtocol.responseBody(200, "é".repeat(524289)));
    }
}
