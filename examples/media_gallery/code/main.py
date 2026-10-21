from craftpy import *

# What each player sees in the window. The picture and the sounds are plain widgets: this code only keeps a few
# numbers, and the window follows them (@ui.data below).
STATE = {}


def mine(player):
    return STATE.setdefault(player.name, dict(click=0, chime=0, finished=0, mute=False, volume=80))


@ui.data('gallery')
def gallery_data(ctx):
    s = mine(ctx.player)
    return dict(
        click_n=s['click'],              # a new number restarts the sound "click"
        chime_n=s['chime'],              # ... and "chime"
        mute=s['mute'],
        volume=s['volume'] / 100,        # 0 to 1 for the sounds
        volume_slider=s['volume'],       # 0 to 100 for the slider
        status='Clip finished %d times. Effects played: %d.' % (s['finished'], s['click'] + s['chime']))


@action('sfx_click')
def sfx_click(ctx):
    mine(ctx.player)['click'] += 1


@action('sfx_chime')
def sfx_chime(ctx):
    mine(ctx.player)['chime'] += 1


@action('mute_changed')
def mute_changed(ctx):
    mine(ctx.player)['mute'] = bool(ctx.value)


@action('volume_changed')
def volume_changed(ctx):
    mine(ctx.player)['volume'] = max(0, min(100, int(ctx.value)))


@action('clip_done')
def clip_done(ctx):
    # the video reports its end; ctx.values['clip'] holds its position, duration and state
    mine(ctx.player)['finished'] += 1


@command('open')
def open_gallery(ctx):
    if ctx.player:
        ui.open(ctx.player, 'gallery')
