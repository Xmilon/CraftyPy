#: title: Safe dimension-aware waypoint
#: category: Commands
#: doc: api.md#Storage
#: module: dimension_waypoint
#: about: Save a position and return only when the loaded destination is safe.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}_save')
def save_{{name}}(ctx):
    p = ctx.player
    storage.set('{{name}}:' + p.uuid, dict(x=p.x, y=p.y, z=p.z, dimension=p.dimension))
@command('{{name}}_return')
def return_{{name}}(ctx):
    spot = storage.get('{{name}}:' + ctx.player.uuid)
    if spot:
        ctx.player.teleport(spot['x'], spot['y'], spot['z'], dimension=spot['dimension'], safe=True)
