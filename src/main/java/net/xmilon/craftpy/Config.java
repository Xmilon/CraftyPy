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

    public static final ModConfigSpec.EnumValue<net.xmilon.craftpy.bridge.JavaAccess.Mode> JAVA_BRIDGE_MODE = BUILDER
            .comment("What Python may reach through the Java bridge (java.cls / java.new / java.on): OFF, RESTRICTED"
                    + " (Minecraft, NeoForge, Mojang libraries and the packages below) or FULL (any class that is not"
                    + " on the built-in deny list: processes, files, network, reflection, class loading).")
            .defineEnum("javaBridge.mode", net.xmilon.craftpy.bridge.JavaAccess.Mode.RESTRICTED);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> JAVA_BRIDGE_PACKAGES = BUILDER
            .comment("Extra package prefixes the Java bridge may use in RESTRICTED mode, e.g. \"com.example.mymod.\""
                    + " to script another mod.")
            .defineListAllowEmpty("javaBridge.extraPackages", java.util.List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<String> PYTHON_PATH = BUILDER
            .comment("Path to a Python executable. Leave empty to use a Python 3.13+ on PATH or one placed in pyforge/runtime/.")
            .define("pythonPath", "");

    public static final ModConfigSpec.BooleanValue ENABLE_CLIENT_PYTHON = BUILDER
            .comment("Lets client-side modules and projects (HUD, keys, windows) run Python on this computer.")
            .define("enableClientPython", true);

    public static final ModConfigSpec.IntValue PYTHON_MEMORY_MB = BUILDER
            .comment("Memory the Python worker may use (MB). Code that needs more is stopped instead of slowing the game.")
            .defineInRange("pythonMemoryMb", 1024, 256, 8192);

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
            .comment("Deprecated and ignored: set the number of block slots in config/craftpy-slots.properties (restart needed).")
            .defineInRange("slotPool.blocks", 256, 0, 4096);

    public static final ModConfigSpec.IntValue ITEM_SLOTS = BUILDER
            .comment("Deprecated and ignored: set the number of item slots in config/craftpy-slots.properties (restart needed).")
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

    /** The value, or its default when no config file is loaded (the standalone IDE, unit tests). */
    public static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }
}
