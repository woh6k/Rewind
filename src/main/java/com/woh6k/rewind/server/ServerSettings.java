package com.woh6k.rewind.server;

/** Saved beside the world's snapshot slots, never inside a snapshot or a client config. */
public final class ServerSettings {
    public boolean automatic = true;
    public int intervalMinutes = 10;
    public int defaultManual = 1;
    public String defaultRestore = "manual_1";
    public void validate() {
        if (intervalMinutes < 1 || intervalMinutes > 1440 || defaultManual < 1 || defaultManual > 5)
            throw new IllegalArgumentException("Invalid Rewind server settings");
        ServerSnapshots.parseSlot(defaultRestore);
    }
}
