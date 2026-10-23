#: title: Operator-validated command
#: category: Commands
#: doc: api.md#Commands
#: module: operator_action
#: about: Native server identity supplies operator status; rejects ordinary callers.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}', level=2)
def operator_{{name}}(ctx):
    if ctx.player and ctx.player.operator:
        world.weather('clear', dimension=ctx.player.dimension)
