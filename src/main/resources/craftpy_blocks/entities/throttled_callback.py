#: title: Throttled entity callback
#: category: Entities
#: doc: api.md#Mob
#: module: throttled_callback
#: about: Filter by entity ID and update state at most every 100 ticks, without per-frame scripting.
#: side: server
#: keywords: 
from craftpy import *

{{name}} = Mob('{{name}}', model='pig')
@on.entity_tick(entity='{{id}}:{{name}}', throttle_ticks=100)
def update_{{name}}(event):
    state = event.entity.state(default={})
    event.entity.set_state('updates', state.get('updates', 0) + 1)
