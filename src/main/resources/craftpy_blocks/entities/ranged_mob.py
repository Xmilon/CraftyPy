#: title: Native ranged behavior
#: category: Entities
#: doc: api.md#Mob
#: module: ranged_mob
#: about: Movement and attack timing run natively; Python only declares the profile.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Mob('{{name}}', name='Ranged companion', behavior={'profile':'ranged', 'target':'hostile'}, health=30, damage=4)
