package net.xmilon.craftpy.network;

public enum ProjectAction {
    PING,
    LIST,
    CREATE,
    DELETE,
    RENAME,
    DUPLICATE,
    LOAD,
    UNLOAD,
    LIST_FILES,
    READ_FILE,
    WRITE_FILE,
    VALIDATE,
    SET_ACTIVE,
    CONSOLE,
    OPEN_FOLDER,
    UPLOAD_ASSET,
    EVAL,
    CREATE_ENTRY,
    DELETE_ENTRY,
    RENAME_ENTRY,
    FIND_DEFINITION,
    READ_ASSET,
    INTERRUPT,
    OPEN_IDE,
    BUILD,
    MODULES_LIST,
    MODULE_README,
    MODULE_SET_ENABLED,
    MODULE_SET_ALL,
    MODULE_RELOAD,
    MODULE_DELETE,
    /** The module debuilder: a .cpm (by path) back into a project; the module is disabled. */
    MODULE_UNPACK,
    /** Approves (or revokes) what a module asks for: {"id": ..., "approve": true}. */
    MODULE_APPROVE,
    /** Editor tools: symbols, usage, rename... (JSON request, JSON reply). */
    TOOL,
    /** An in-game window sent to the player (server to client). */
    WINDOW,
    /** A click or input in an in-game window (client to server). */
    WINDOW_EVENT,
    /** Whether the quick IDE key works for this player (message "true"/"false"): unlocked by crafting a CraftPy Computer. */
    IDE_KEY
}
