from craftpy import *
from items import ruby_apple

SURGE_EVERY = 10


def start_surge(miner):
    say(f"Ruby Surge! {miner.name} mined ore number {storage['ores_mined']}.")
    miner.give(ruby_apple)
    for p in players.online():
        p.effect("haste", seconds=30, level=2)
    # Fireworks for 5 seconds, then calm down again.
    show = run_every(10, lambda tick: world.particle("minecraft:firework", miner.pos.up(2), count=20))
    after(5, show.cancel)
