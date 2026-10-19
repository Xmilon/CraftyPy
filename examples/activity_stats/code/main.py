from craftpy import *

def increment(p, category, amount=1):
    if not p or not p.uuid:
        return
    key = 'stats:' + p.uuid
    data = storage.get(key, {})
    data[category] = data.get(category, 0) + amount
    storage.set(key, data)

@on.block_break
def mined(event):
    increment(event.player, 'mined')

@on.item_craft
def crafted(event):
    increment(event.player, 'crafted', event.item.count or 1)

@on.entity_death
def defeated(event):
    if event.killer and event.killer.uuid:
        increment(event.killer, 'defeated')

@command('show')
def show(ctx):
    if ctx.player:
        data = storage.get('stats:' + ctx.player.uuid, {})
        ctx.player.say(', '.join(f'{name}: {data.get(name, 0)}' for name in ('mined', 'crafted', 'defeated')))

@keys.bind('ctrl+shift+s', id='show_stats', name='Show activity stats', category='Activity Stats')
def shortcut(ctx):
    show(ctx)
