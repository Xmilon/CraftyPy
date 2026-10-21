package net.xmilon.craftpy.project;

import java.util.ArrayList;
import java.util.List;

/**
 * A single .cpm file discovered anywhere under {@code pyforge/}. This is a
 * plain data holder shared between the server (which scans the disk) and the
 * client (which renders the Modules page), so it deliberately has no game or
 * client types.
 */
public class ModuleInfo {
    /** Declared module id (from module.json); falls back to the file name. */
    public String id = "";
    public String name = "";
    public String version = "";
    public String sha256 = "";
    public String author = "";
    /** One-line summary from module.json. */
    public String description = "";
    /** Full markdown README bundled in the .cpm, if any (extended description). */
    public String readme = "";
    /** Absolute path of the .cpm file on disk. */
    public String path = "";
    /** Folder (relative to pyforge/) the file lives in, e.g. "modules" or "modules/foo". */
    public String source = "";
    public long sizeBytes = 0L;
    public long modified = 0L;
    /** {@code both}, {@code client} or {@code server} ({@link ModuleSide}). */
    public String side = "both";
    /** What it asks for, the forced warning about it, and whether all of it is approved on this computer. */
    public net.xmilon.craftpy.security.ModulePermissions permissions = new net.xmilon.craftpy.security.ModulePermissions();
    public String permissionWarning = "";
    public boolean permissionsApproved = true;
    /** Whether the user has this module enabled. */
    public boolean enabled = true;
    /** Whether the module is currently loaded into the running game. */
    public boolean loaded = false;
    /** Desired configuration or installed bytes differ from the last successful apply. */
    public boolean pendingReload;
    public String pendingReason = "";
    /** True when another file declares the same id and wins the load. */
    public boolean duplicate = false;
    /** True for the winning file when several share an id (the one that loads). */
    public boolean primary = true;
    /** True when disabling this module still needs a restart to fully unload. */
    public boolean pendingRestart = false;
    public List<String> dependencies = new ArrayList<>();
    /** The project it was decompiled into, or null when it never was. */
    public String decompiledTo;
    /** Decompiled, and its file is not the one it was decompiled from any more (rebuilt or replaced since). */
    public boolean changedSinceDecompile = false;
    /** Approved, but for another version of the file: its permissions are off until approved again. */
    public boolean changedSinceApproval = false;
    /** Hub picture of the module and the apps it declares (cleaned), with what was wrong in the declaration. */
    public String icon = "";
    public List<ModuleApp> apps = new ArrayList<>();
    public List<String> appProblems = new ArrayList<>();
    /** Parse/IO error, when the file could not be read. */
    public String error;
}
