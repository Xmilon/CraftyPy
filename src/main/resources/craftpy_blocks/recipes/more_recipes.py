#: title: Stonecutter, campfire and smithing
#: category: Recipes
#: doc: api.md#Recipes
#: module: extra_recipes
#: about: The other recipe types: stonecutter, campfire cooking and a smithing upgrade.
from craftpy import *

stonecut("minecraft:stone", "minecraft:stone_bricks", count=1)
recipe_campfire("minecraft:cooked_cod", "minecraft:cod", cook_time=300)
recipe_smithing("minecraft:netherite_axe", "minecraft:netherite_upgrade_smithing_template",
                "minecraft:diamond_axe", "minecraft:netherite_ingot")
