#: title: Create cutting
#: category: Create
#: doc: api.md#recipe
#: module: create_cutting
#: about: Native cutting recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:cutting', id='{{name}}', **{'ingredients': [{'item': 'create:andesite_alloy'}], 'processing_time': 200, 'results': [{'count': 6, 'id': 'create:shaft'}]})
