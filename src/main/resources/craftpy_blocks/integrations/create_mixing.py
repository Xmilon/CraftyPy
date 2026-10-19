#: title: Create mixing
#: category: Create
#: doc: api.md#recipe
#: module: create_mixing
#: about: Native mixing recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:mixing', id='{{name}}', **{'ingredients': [{'item': 'minecraft:andesite'}, {'tag': 'c:nuggets/iron'}], 'results': [{'id': 'create:andesite_alloy'}]})
