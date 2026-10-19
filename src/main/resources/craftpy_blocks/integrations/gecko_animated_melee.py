#: title: GeckoLib animated melee
#: category: GeckoLib
#: doc: api.md#Mob
#: module: gecko_animated_melee
#: about: Complete geometry and animation companion files; movement and triggering remain native.
#: side: server
#: keywords: 
#: mods: geckolib
#: files: assets/models/{{name}}.geo.json=_files/gecko_animated_melee_0.json;assets/animations/{{name}}.animation.json=_files/gecko_animated_melee_1.json
from craftpy import *

{{name}} = Mob('{{name}}', model='{{name}}.geo.json', animation='{{name}}.animation.json', texture='minecraft:textures/block/iron_block.png', behavior='melee', animations={'idle':'idle','walk':'idle','attack':'attack'})
