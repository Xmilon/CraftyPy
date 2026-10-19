#: title: Native follow behavior
#: category: Entities
#: doc: api.md#Mob
#: module: follow_mob
#: about: Movement and attack timing run natively; Python only declares the profile.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Mob('{{name}}', name='Follow companion', behavior={'profile':'follow', 'target':'hostile'}, health=30, damage=4)
