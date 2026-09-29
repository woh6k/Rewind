package com.woh6k.rewind.client;

import com.woh6k.rewind.snapshot.SnapshotManager;

/** Client-only endpoint kept out of common networking classes for dedicated-server safety. */
public final class ClientNetworkHandler {
    private ClientNetworkHandler() { }

    public static void receive(String kind, String value) {
        SnapshotManager.get().receive(kind, value);
    }
}
