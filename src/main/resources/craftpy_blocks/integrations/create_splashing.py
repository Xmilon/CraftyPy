#: title: Create splashing
#: category: Create
#: doc: api.md#recipe
#: module: create_splashing
#: about: Native splashing recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:splashing', id='{{name}}', **{'ingredients': [{'item': 'create:industrial_iron_window_pane'}], 'results': [{'id': 'create:weathered_iron_window_pane'}]})
