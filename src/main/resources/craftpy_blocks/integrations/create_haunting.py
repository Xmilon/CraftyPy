#: title: Create haunting
#: category: Create
#: doc: api.md#recipe
#: module: create_haunting
#: about: Native haunting recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:haunting', id='{{name}}', **{'ingredients': [{'item': 'minecraft:stone'}], 'results': [{'id': 'minecraft:infested_stone'}]})
