package net.xmilon.craftpy;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_SCRIPTING = BUILDER
            .comment("Master switch for Python scripting. When false, projects cannot be loaded and the IDE is read-only.")
            .define("enableScripting", true);

    public static final ModConfigSpec.IntValue PERMISSION_LEVEL = BUILDER
            .comment("Required permission level to open the IDE and load/unload projects (0-4). 2 = op.",
                    "In singleplayer the world owner is always allowed.")
            .defineInRange("permissionLevel", 2, 0, 4);

    public static final ModConfigSpec.BooleanValue DEBUG_DEFAULT = BUILDER
            .comment("Start with debug mode enabled.")
            .define("debugDefault", false);

    public static final ModConfigSpec.BooleanValue HOT_RELOAD_ON_SAVE = BUILDER
            .comment("Automatically rebuild and reload the active project when its files change.")
            .define("hotReloadOnSave", false);

    public static final ModConfigSpec.ConfigValue<String> PYTHON_PATH = BUILDER
            .comment("Path to a Python executable. Leave empty to use the bundled/auto-downloaded runtime.")
            .define("pythonPath", "");

    public static final ModConfigSpec.BooleanValue AUTO_DOWNLOAD_PYTHON = BUILDER
            .comment("Download a standalone CPython runtime into pyforge/runtime/ when no system Python is found.")
            .define("autoDownloadPython", true);

    public static final ModConfigSpec.IntValue EVENT_TIMEOUT_MS = BUILDER
            .comment("Budget in milliseconds for a Python event callback before it is cancelled.")
            .defineInRange("eventTimeoutMs", 50, 1, 5000);

    public static final ModConfigSpec.IntValue MAX_PACKET_SIZE_KB = BUILDER
            .comment("Maximum size in KiB for project file packets sent to/from a dedicated server.")
            .defineInRange("maxPacketSizeKb", 32768, 1, 1048576);

    public static final ModConfigSpec.IntValue MAX_ASSET_SIZE_KB = BUILDER
            .comment("Maximum size in KiB for an imported asset file.")
            .defineInRange("maxAssetSizeKb", 8192, 1, 1048576);

    public static final ModConfigSpec.IntValue BLOCK_SLOTS = BUILDER
            .comment("Number of generic block slots pre-registered at startup.")
            .defineInRange("slotPool.blocks", 256, 0, 4096);

    public static final ModConfigSpec.IntValue ITEM_SLOTS = BUILDER
            .comment("Number of generic item slots pre-registered at startup.")
            .defineInRange("slotPool.items", 256, 0, 4096);

    public static final ModConfigSpec.IntValue ENTITY_SLOTS = BUILDER
            .comment("Number of generic entity slots pre-registered at startup.")
            .defineInRange("slotPool.entities", 64, 0, 1024);

    public static final ModConfigSpec.IntValue EFFECT_SLOTS = BUILDER
            .comment("Number of generic mob effect slots pre-registered at startup.")
            .defineInRange("slotPool.effects", 32, 0, 512);

    public static final ModConfigSpec.IntValue SOUND_SLOTS = BUILDER
            .comment("Number of generic sound event slots pre-registered at startup.")
            .defineInRange("slotPool.sounds", 32, 0, 512);

    public static final ModConfigSpec.IntValue BLOCK_ENTITY_SLOTS = BUILDER
            .comment("Number of generic block entity slots pre-registered at startup.")
            .defineInRange("slotPool.blockEntities", 16, 0, 256);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
