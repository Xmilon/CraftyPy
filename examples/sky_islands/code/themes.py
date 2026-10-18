# What an archipelago is made of. Add a theme by copying one: every key is used by builder.py.
THEMES = {
    "verdant": {
        "top": "minecraft:grass_block", "soil": "minecraft:dirt", "rock": "minecraft:stone",
        "ore": "minecraft:mossy_cobblestone", "log": "minecraft:oak_log", "leaves": "minecraft:azalea_leaves",
        "flowers": ["minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower", "minecraft:short_grass"],
        "crystal": "minecraft:amethyst_block", "glow": "minecraft:sea_lantern", "rail": "minecraft:oak_fence",
        "deck": "minecraft:oak_slab", "ruin": "minecraft:mossy_stone_bricks", "liquid": "minecraft:water",
        "particle": "minecraft:happy_villager", "sky": "minecraft:end_rod",
    },
    "frost": {
        "top": "minecraft:snow_block", "soil": "minecraft:packed_ice", "rock": "minecraft:blue_ice",
        "ore": "minecraft:calcite", "log": "minecraft:spruce_log", "leaves": "minecraft:spruce_leaves",
        "flowers": ["minecraft:snow", "minecraft:snow", "minecraft:sweet_berry_bush"],
        "crystal": "minecraft:light_blue_stained_glass", "glow": "minecraft:sea_lantern", "rail": "minecraft:spruce_fence",
        "deck": "minecraft:spruce_slab", "ruin": "minecraft:stone_bricks", "liquid": "minecraft:water",
        "particle": "minecraft:snowflake", "sky": "minecraft:end_rod",
    },
    "blossom": {
        "top": "minecraft:moss_block", "soil": "minecraft:rooted_dirt", "rock": "minecraft:tuff",
        "ore": "minecraft:pink_terracotta", "log": "minecraft:cherry_log", "leaves": "minecraft:cherry_leaves",
        "flowers": ["minecraft:pink_petals", "minecraft:pink_tulip", "minecraft:allium", "minecraft:lily_of_the_valley"],
        "crystal": "minecraft:pink_stained_glass", "glow": "minecraft:pearlescent_froglight", "rail": "minecraft:cherry_fence",
        "deck": "minecraft:cherry_slab", "ruin": "minecraft:quartz_bricks", "liquid": "minecraft:water",
        "particle": "minecraft:cherry_leaves", "sky": "minecraft:end_rod",
    },
    "ember": {
        "top": "minecraft:crimson_nylium", "soil": "minecraft:netherrack", "rock": "minecraft:blackstone",
        "ore": "minecraft:gilded_blackstone", "log": "minecraft:crimson_stem", "leaves": "minecraft:nether_wart_block",
        "flowers": ["minecraft:crimson_fungus", "minecraft:crimson_roots", "minecraft:weeping_vines_plant"],
        "crystal": "minecraft:red_stained_glass", "glow": "minecraft:shroomlight", "rail": "minecraft:crimson_fence",
        "deck": "minecraft:crimson_slab", "ruin": "minecraft:polished_blackstone_bricks", "liquid": None,
        "particle": "minecraft:crimson_spore", "sky": "minecraft:flame",
    },
    "void": {
        "top": "minecraft:end_stone", "soil": "minecraft:end_stone", "rock": "minecraft:obsidian",
        "ore": "minecraft:purpur_block", "log": "minecraft:purpur_pillar", "leaves": "minecraft:chorus_flower",
        "flowers": ["minecraft:chorus_plant", "minecraft:end_rod"],
        "crystal": "minecraft:purple_stained_glass", "glow": "minecraft:end_rod", "rail": "minecraft:purpur_slab",
        "deck": "minecraft:purpur_slab", "ruin": "minecraft:end_stone_bricks", "liquid": None,
        "particle": "minecraft:portal", "sky": "minecraft:reverse_portal",
    },
}

NAMES = list(THEMES)
