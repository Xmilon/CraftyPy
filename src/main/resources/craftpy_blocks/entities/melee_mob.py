#: title: Native melee behavior
#: category: Entities
#: doc: api.md#Mob
#: module: melee_mob
#: about: Movement and attack timing run natively; Python only declares the profile.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Mob('{{name}}', name='Melee companion', behavior={'profile':'melee', 'target':'hostile'}, health=30, damage=4)
