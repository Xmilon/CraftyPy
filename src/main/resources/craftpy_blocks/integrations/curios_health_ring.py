#: title: Curios health ring
#: category: Curios
#: doc: api.md#Attributes
#: module: curios_health_ring
#: about: Native worn attribute effect with Curios equipment tags.
#: side: server
#: keywords: 
#: mods: curios
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:gold_nugget', stack=1, tags=['curios:ring']).extends(Attributes('minecraft:generic.max_health', 2, slot='curios:ring'))

# Curios SET merges the maximum size; existing larger slot counts are preserved.
raw_json('curios/slots/ring.json', {'size':1,'operation':'SET'})
raw_json('curios/entities/{{name}}.json', {'entities':['player'],'slots':['ring']})
