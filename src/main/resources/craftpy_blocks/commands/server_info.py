#: title: Server status command
#: category: Commands
#: doc: api.md#Commands
#: module: server_info
#: about: Read player counts and native average server tick duration.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}')
def status_{{name}}(ctx):
    ctx.player.say(str(server.info()))
