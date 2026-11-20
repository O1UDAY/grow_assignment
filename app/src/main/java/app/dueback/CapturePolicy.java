package app.dueback;

final class CapturePolicy {
    private CapturePolicy() {}

    static boolean accept(boolean supported, boolean selected, boolean groupSummary) {
        return supported && selected && !groupSummary;
    }

    static String status(boolean granted, int selected, boolean connected) {
        if (!granted) return "Access off";
        if (selected == 0) return "Choose at least one app";
        return connected ? "Listening for new notifications" : "Waiting for Android to connect";
    }
}
