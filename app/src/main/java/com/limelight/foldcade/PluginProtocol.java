package com.limelight.foldcade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Protocol 1 constants and Android-independent validation. */
public final class PluginProtocol {
    public static final String PLAY = "app.foldcade.plugin.action.PLAY";
    public static final String EXTRA = "app.foldcade.plugin.extra.";
    public static final String GAMESTREAM = "app.foldcade.gamestream.extra.";
    static final String SESSION = "com.limelight.foldcade.SESSION";
    private static final Set<String> PATHS = Set.of("serverinfo", "applist", "launch", "resume", "cancel");

    private PluginProtocol() { }

    public static final class BrokerException extends IOException {
        public final String code;

        public BrokerException(String code) {
            super(userMessage(code));
            this.code = code;
        }
    }

    public static String userMessage(String code) {
        switch (code) {
            case "not_granted":
                return "In Foldcade, open Settings › Plugins › Foldcade Moonlight and turn on Share paired computers.";
            case "unknown_host": return "This PC is no longer paired with Foldcade. Pair it again in Foldcade › Settings › Library.";
            case "unreachable": return "Your PC could not be reached. Check that it is awake and connected, then try again.";
            case "host_error": return "Your PC could not complete the streaming request. Try again from Foldcade.";
            case "not_launched": return "Start this game from Foldcade.";
            case "bad_request": return "The streaming request was not accepted. Update Foldcade and Foldcade Moonlight, then try again.";
            default: return "Foldcade Moonlight could not reach Foldcade. Open Foldcade and try again.";
        }
    }

    static void requireSuccess(Object ok, Object error) throws BrokerException {
        if (Boolean.TRUE.equals(ok)) return;
        if (!Boolean.FALSE.equals(ok) || !(error instanceof String)) throw new BrokerException("internal");
        // Preserve the host's protocol error code without displaying its log-only message.
        throw new BrokerException((String) error);
    }

    static void validateQuery(String path, String query) throws BrokerException {
        if (path == null || !PATHS.contains(path) || (query != null && (query.length() > 4096 ||
                query.startsWith("?") || query.indexOf('#') >= 0 || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0))) {
            throw new BrokerException("bad_request");
        }
    }

    static String responseBody(Object status, Object body) throws BrokerException {
        if (!(status instanceof Integer) || (Integer) status < 200 || (Integer) status >= 300 ||
                !(body instanceof String) || ((String) body).getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new BrokerException("host_error");
        }
        return (String) body;
    }

    /** Shown for any play intent this version cannot read: a newer or older Foldcade, or a bad sender. */
    public static final String UNREADABLE = "Foldcade sent a request this version cannot read. Update Foldcade and Foldcade Moonlight, then try again.";

    public static final class PlayRequest {
        public final String authority, title, hostUuid, hostName, appName, launchToken;
        public final int topDisplay, bottomDisplay, appId;

        public PlayRequest(Map<String, Object> extras) {
            if (integer(extras, EXTRA + "PROTOCOL") != 1) throw new IllegalArgumentException(UNREADABLE);
            authority = string(extras, EXTRA + "BROKER_AUTHORITY");
            title = string(extras, EXTRA + "TITLE");
            launchToken = string(extras, EXTRA + "LAUNCH_TOKEN");
            hostUuid = string(extras, GAMESTREAM + "HOST_UUID");
            hostName = string(extras, GAMESTREAM + "HOST_NAME");
            appName = string(extras, GAMESTREAM + "APP_NAME");
            topDisplay = integer(extras, EXTRA + "TOP_DISPLAY");
            bottomDisplay = integer(extras, EXTRA + "BOTTOM_DISPLAY");
            String id = string(extras, GAMESTREAM + "APP_ID");
            if (!id.matches("[0-9]+")) throw new IllegalArgumentException(UNREADABLE);
            try { appId = Integer.parseInt(id); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(UNREADABLE); }
            if (appId <= 0 || topDisplay < 0 || bottomDisplay < -1 || bottomDisplay == topDisplay) {
                throw new IllegalArgumentException(UNREADABLE);
            }
        }

        private static String string(Map<String, Object> extras, String key) {
            Object value = extras.get(key);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IllegalArgumentException(UNREADABLE);
            }
            return (String) value;
        }

        private static int integer(Map<String, Object> extras, String key) {
            Object value = extras.get(key);
            if (!(value instanceof Integer)) throw new IllegalArgumentException(UNREADABLE);
            return (Integer) value;
        }
    }
}
