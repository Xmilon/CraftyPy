#: title: Item tags
#: category: Items
#: doc: api.md#Tags
#: module: gems
#: about: Puts items in a tag of this project and in a common tag other mods read (c:gems).
from craftpy import *

{{name}}_ruby = Item("{{name}}_ruby", name="Ruby", texture="minecraft:emerald", tags=["{{name}}", "c:gems"])
{{name}}_opal = Item("{{name}}_opal", name="Opal", texture="minecraft:quartz", tags=["{{name}}", "c:gems"])
tag("{{name}}_all", ["{{id}}:{{name}}_ruby", "{{id}}:{{name}}_opal"])
