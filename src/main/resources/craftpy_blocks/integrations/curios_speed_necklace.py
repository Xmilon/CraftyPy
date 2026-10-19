#: title: Curios speed necklace
#: category: Curios
#: doc: api.md#Attributes
#: module: curios_speed_necklace
#: about: Native worn attribute effect with Curios equipment tags.
#: side: server
#: keywords: 
#: mods: curios
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:gold_nugget', stack=1, tags=['curios:necklace']).extends(Attributes('minecraft:generic.movement_speed', 0.01, slot='curios:necklace'))

# Curios SET merges the maximum size; existing larger slot counts are preserved.
raw_json('curios/slots/necklace.json', {'size':1,'operation':'SET'})
raw_json('curios/entities/{{name}}.json', {'entities':['player'],'slots':['necklace']})
