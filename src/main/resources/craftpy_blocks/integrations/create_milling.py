#: title: Create milling
#: category: Create
#: doc: api.md#recipe
#: module: create_milling
#: about: Native milling recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:milling', id='{{name}}', **{'ingredients': [{'item': 'minecraft:short_grass'}], 'processing_time': 50, 'results': [{'chance': 0.25, 'id': 'minecraft:wheat_seeds'}]})
