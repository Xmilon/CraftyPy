#: title: Create pressing
#: category: Create
#: doc: api.md#recipe
#: module: create_pressing
#: about: Native pressing recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:pressing', id='{{name}}', **{'ingredients': [{'item': 'create:pulp'}], 'results': [{'id': 'create:cardboard'}]})
