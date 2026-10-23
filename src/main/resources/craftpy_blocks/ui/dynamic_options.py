#: title: Dynamic choices
#: category: UI elements
#: doc: api.md#Windows
#: module: dynamic_options
#: about: Creates a designer layout with preview values, viewer data and a named action.
#: side: server
#: keywords: designer JSON bindings data state focus
#: files: ui/{{name}}.json=_files/dynamic_options_0.json
from craftpy import *

@ui.data('{{name}}')
def data_{{name}}(ctx):
    saved = storage.get('{{name}}:' + ctx.player.uuid, {})
    value = saved.get('value', 0)
    return dict(value=value, caption=ctx.player.name + ': ' + str(value), options=['One', 'Two', 'Three'],
                show=value % 2 == 0, enabled=value < 10, draft=saved.get('draft', ''), health=ctx.player.health)

@action('{{name}}_apply')
def apply_{{name}}(ctx):
    key = '{{name}}:' + ctx.player.uuid
    old = storage.get(key, {})
    # Display bindings never authorize the mutation: validate again here.
    if old.get('value', 0) >= 10:
        return
    storage.set(key, dict(value=old.get('value', 0) + 1, draft=str(ctx.values.get('draft') or '')[:120]))

@command('{{name}}', level=0)
def open_{{name}}(ctx):
    ui.open(ctx.player, '{{name}}')
