#: title: Curios luck belt
#: category: Curios
#: doc: api.md#Attributes
#: module: curios_luck_belt
#: about: Native worn attribute effect with Curios equipment tags.
#: side: server
#: keywords: 
#: mods: curios
from craftpy import *

{{name}} = Item('{{name}}', texture='minecraft:gold_nugget', stack=1, tags=['curios:belt']).extends(Attributes('minecraft:generic.luck', 1, slot='curios:belt'))

# Curios SET merges the maximum size; existing larger slot counts are preserved.
raw_json('curios/slots/belt.json', {'size':1,'operation':'SET'})
raw_json('curios/entities/{{name}}.json', {'entities':['player'],'slots':['belt']})
