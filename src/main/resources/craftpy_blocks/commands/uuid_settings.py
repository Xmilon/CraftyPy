#: title: UUID-scoped preference
#: category: Commands
#: doc: api.md#Storage
#: module: uuid_settings
#: about: Persistent preference remains attached to the player across name changes.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}')
def settings_{{name}}(ctx, enabled: bool):
    storage.set('{{name}}:' + ctx.player.uuid, enabled)
    ctx.player.say('Preference saved:', enabled)
