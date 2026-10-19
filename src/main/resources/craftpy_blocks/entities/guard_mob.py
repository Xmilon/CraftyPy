#: title: Native guard behavior
#: category: Entities
#: doc: api.md#Mob
#: module: guard_mob
#: about: Movement and attack timing run natively; Python only declares the profile.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Mob('{{name}}', name='Guard companion', behavior={'profile':'guard', 'target':'hostile'}, health=30, damage=4)
