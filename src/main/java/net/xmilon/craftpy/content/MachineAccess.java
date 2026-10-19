package net.xmilon.craftpy.content;

import com.google.gson.JsonObject;

/** Common boundary, safe to load on a server without Create. */
public interface MachineAccess {
    MachineState machineState();
    float machineSpeed();
    float theoreticalSpeed();
    String rotationState();
    void syncMachine();
    default JsonObject snapshot() { return machineState().snapshot(); }
}
