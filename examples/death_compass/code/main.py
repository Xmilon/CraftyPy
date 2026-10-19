from craftpy import *

@on.player_death
def remember(event):
    p = event.player
    storage.set('death:' + p.uuid, dict(x=p.x, y=p.y, z=p.z, dimension=p.dimension))

@command('where')
def where(ctx):
    p = ctx.player
    if not p:
        return
    spot = storage.get('death:' + p.uuid)
    if not spot:
        p.say('No death recorded in this world.')
        return
    dx, dz = spot['x'] - p.x, spot['z'] - p.z
    direction = ('East' if dx > 0 else 'West') + '/' + ('South' if dz > 0 else 'North')
    p.say(f"Last death: {spot['x']:.0f}, {spot['y']:.0f}, {spot['z']:.0f} in {spot['dimension']}")
    if spot['dimension'] == p.dimension:
        p.say(f'{direction}, {(dx*dx+dz*dz)**0.5:.0f} horizontal blocks away.')

@keys.bind('ctrl+shift+d', id='show_death', name='Show last death', category='Death Compass')
def shortcut(ctx):
    where(ctx)
