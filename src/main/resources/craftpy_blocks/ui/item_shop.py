#: title: Shop with item icons
#: category: UI elements
#: doc: api.md#ItemIcon
#: module: item_shop
#: about: Item icons in rows with a buy button each; takes emeralds, gives the item.
from craftpy import *

OFFERS = [("minecraft:bread", 1, 3), ("minecraft:iron_ingot", 2, 1), ("minecraft:diamond", 8, 1)]


@window("{{name}}", title="Shop", size=(300, 220))
class ItemShop(Window):
    def build(self, ctx):
        rows = [Label("Pay with emeralds", style="title")]
        for index, (item, price, count) in enumerate(OFFERS):
            rows.append(HBox(
                ItemIcon(item),
                Label(f"{count} for {price} emerald(s)"),
                Button("Buy", on_click=lambda event, i=index: self.buy(event, i)),
                spacing=8,
            ))
        rows.append(Button("Close", on_click=ctx.close, style="text"))
        return VBox(*rows, spacing=6, padding=4)

    def buy(self, ctx, index):
        item, price, count = OFFERS[index]
        if ctx.player.has("minecraft:emerald", price):
            ctx.player.take("minecraft:emerald", price)
            ctx.player.give(item, count)
            ctx.player.actionbar("Thanks!")
        else:
            ctx.player.actionbar(f"You need {price} emerald(s)")


@command("{{name}}")
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - open the shop."""
    ui.open(ctx.player, "{{name}}")
