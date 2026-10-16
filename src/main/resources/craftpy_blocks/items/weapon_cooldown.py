#: title: Weapon with a cooldown
#: category: Items
#: doc: api.md#Items
#: module: storm_blade
#: about: A heavy weapon: damage, attack speed, knockback, and a right-click power with a cooldown.
from craftpy import *

{{name}} = Item("{{name}}", name="Storm Blade", texture="minecraft:netherite_sword", stack=1).extends(
    Weapon(damage=9, attack_speed=1.0, knockback=0.6), Durability(900), Cooldown(5), Enchantable(18))


@on.item_use(item={{name}})
def {{name}}_power(event):
    world.lightning(event.player.pos)
    event.player.actionbar("The storm answers")
