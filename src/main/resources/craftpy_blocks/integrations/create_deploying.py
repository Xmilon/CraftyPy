#: title: Create deploying
#: category: Create
#: doc: api.md#recipe
#: module: create_deploying
#: about: Native deploying recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:deploying', id='{{name}}', **{'ingredients': [{'item': 'create:shaft'}, {'tag': 'minecraft:planks'}], 'results': [{'id': 'create:cogwheel'}]})
