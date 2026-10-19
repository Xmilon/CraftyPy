from craftpy import *

@command('status')
def status(ctx):
    if ctx.player:
        info = server.info()
        ctx.player.say(f"Players: {info.get('players', 0)}/{info.get('max_players', 0)}, average tick: {info.get('tick_ms', 0):.1f} ms")

@on.player_join
def greet(event):
    event.player.say('Welcome! /server_toolkit:status shows server information.')

@command('announce')
def announce(ctx, message: str):
    if ctx.player and ctx.player.operator:
        server.broadcast('[Announcement] ' + message[:180])

@command('day')
def day(ctx):
    if ctx.player and ctx.player.operator:
        world.set_time('day', dimension=ctx.player.dimension)

@command('clear')
def clear(ctx):
    if ctx.player and ctx.player.operator:
        world.weather('clear', dimension=ctx.player.dimension)

@keys.bind('ctrl+shift+i', id='show_status', name='Show server status', category='Server Toolkit')
def shortcut(ctx):
    status(ctx)
