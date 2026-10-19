from craftpy import *

def key(p):
    return 'points:' + p.uuid

@command('save')
def save(ctx, label: str):
    p = ctx.player
    if not p:
        return
    points = storage.get(key(p), {})
    name = label.strip()[:32]
    if not name or (name not in points and len(points) >= 20):
        p.say('Choose a name; at most 20 waypoints are allowed.')
        return
    points[name] = dict(x=p.x, y=p.y, z=p.z, dimension=p.dimension)
    storage.set(key(p), points)
    p.say('Saved', name)

@command('go')
def go(ctx, label: str):
    p = ctx.player
    if not p:
        return
    point = storage.get(key(p), {}).get(label)
    if not point:
        p.say('Unknown waypoint.')
        return
    now = server.info().get('game_time', 0)
    cooldown = 'teleport:' + p.uuid
    if 0 <= now - storage.get(cooldown, -10000) < 200:
        p.say('Wait ten seconds between teleports.')
        return
    try:
        p.teleport(point['x'], point['y'], point['z'], dimension=point['dimension'], safe=True)
        storage.set(cooldown, now)
    except Exception as error:
        p.say(str(error))

@command('list')
def points(ctx):
    if ctx.player:
        ctx.player.say(', '.join(storage.get(key(ctx.player), {})) or 'No waypoints saved.')

@command('remove')
def remove(ctx, label: str):
    if ctx.player:
        points = storage.get(key(ctx.player), {})
        points.pop(label, None)
        storage.set(key(ctx.player), points)

@keys.bind('ctrl+shift+w', id='open_waypoints', name='Open Waypoints', category='Waypoints')
def open_points(ctx):
    ui.open(ctx.player, 'waypoints')

@ui.data('waypoints')
def waypoint_data(ctx):
    return dict(points=list(storage.get(key(ctx.player), {})))

@action('waypoint_save')
def waypoint_save(ctx):
    save(ctx, str(ctx.values.get('label') or ''))

def selected(ctx):
    labels=list(storage.get(key(ctx.player), {}))
    index=ctx.values.get('point', -1)
    return labels[index] if isinstance(index,int) and 0 <= index < len(labels) else None

@action('waypoint_go')
def waypoint_go(ctx):
    label=selected(ctx)
    if label is not None:
        go(ctx,label)

@action('waypoint_remove')
def waypoint_remove(ctx):
    label=selected(ctx)
    if label is not None:
        remove(ctx,label)
