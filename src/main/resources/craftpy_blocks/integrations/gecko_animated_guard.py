#: title: GeckoLib animated guard
#: category: GeckoLib
#: doc: api.md#Mob
#: module: gecko_animated_guard
#: about: Complete geometry and animation companion files; movement and triggering remain native.
#: side: server
#: keywords: 
#: mods: geckolib
#: files: assets/models/{{name}}.geo.json=_files/gecko_animated_guard_0.json;assets/animations/{{name}}.animation.json=_files/gecko_animated_guard_1.json
from craftpy import *

{{name}} = Mob('{{name}}', model='{{name}}.geo.json', animation='{{name}}.animation.json', texture='minecraft:textures/block/iron_block.png', behavior='guard', animations={'idle':'idle','walk':'idle','attack':'attack'})
