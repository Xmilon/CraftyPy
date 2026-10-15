# 3. Recipes, tags, names and loot.
from craftpy import *
from items import gem, gem_apple, gem_sword, gem_wand
from blocks import gem_ore, gem_block

shaped(gem_block, "GGG", "GGG", "GGG", G=gem)          # letters are mapped with keywords
shapeless(gem, gem_block, count=9)                      # order does not matter
shaped(gem_sword, "G", "G", "S", G=gem, S="stick")      # bare names ("stick") are vanilla items
shaped(gem_wand, "  G", " S ", "S  ", G=gem_block, S="stick")
shapeless(gem_apple, gem, "apple")
smelt(gem_ore, gem, xp=1.0)                             # furnace; using="blast_furnace" is faster
stonecut(gem_block, gem, count=4)

tag("gems", [gem, "diamond", "emerald"])                # item tag  welcome:gems
lang("itemGroup.welcome", "Welcome")                    # extra translation keys

# A loot table with several entries; weight decides how often an entry is picked.
loot_table("treasure", [
    loot_entry("diamond", count=1, weight=1),
    loot_entry("gold_ingot", count=3, weight=4),
    loot_entry("bread", count=5, weight=8),
])
