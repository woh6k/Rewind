package com.woh6k.rewind.state;

/** Exactly one disk or world-lifecycle operation may be active at a time. */
public enum RewindState {
    IDLE,
    SAVING_WORLD,
    COPYING_SNAPSHOT,
    PREPARING_RESTORE,
    CLOSING_WORLD,
    RESTORING,
    LOADING_WORLD,
    ERROR
}
