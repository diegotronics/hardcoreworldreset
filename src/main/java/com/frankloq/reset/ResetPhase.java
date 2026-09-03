package com.frankloq.reset;

// Tracks which phase of the world reset we are currently in.
public enum ResetPhase {
    IDLE,
    UNLOADING,    // entities gone, every chunk ticket released
    DRAINING,     // waiting for the chunk engine to unload and the io workers to drop their queues
    DELETING,
    REGENERATING,
    DONE
}
