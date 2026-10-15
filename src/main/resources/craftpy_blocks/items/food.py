#: title: Food
#: category: Items
#: doc: api.md#Food
#: module: magic_apple
#: about: A food item that can always be eaten and gives an effect when eaten.
from craftpy import *

{{name}} = Item("{{name}}", name="Magic Apple", texture="minecraft:golden_apple",
                food=Food(hunger=6, saturation=1.2, always_eat=True))


@on.item_use(item={{name}})
def ate_{{name}}(event):
    event.player.effect("regeneration", seconds=8, level=1)
