#: title: Create crushing
#: category: Create
#: doc: api.md#recipe
#: module: create_crushing
#: about: Native crushing recipe; requires the supported Create adapter.
#: side: server
#: keywords: 
#: mods: create
#: requires: rotation.create
from craftpy import *

recipe('create:crushing', id='{{name}}', ingredients=[{'item':'minecraft:raw_gold'}], results=[{'id':'create:crushed_raw_gold'}], processing_time=250)
