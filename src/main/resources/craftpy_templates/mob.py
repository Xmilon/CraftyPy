# {{id}} - a "mob" encounter: spawn a named guard, get a reward when it dies.
# Press Run, then in game: /{{id}}:guard <your name>
from craftpy import *

GUARD_NAME = "Ruby Guard"
token = Item("guard_token", name="Guard Token", texture="minecraft:gold_nugget", glow=True)


@command("guard")
def spawn_guard(ctx, target: Player):
    """/{{id}}:guard <player> - spawns a zombie guard next to the player."""
    guard = world.spawn("zombie", target.pos.up(1))
    guard.set_name(GUARD_NAME)
    target.say("A", GUARD_NAME, "appears! Defeat it for a reward.")


@on.entity_death
def guard_died(event):
    if event.killer and event.entity.name == GUARD_NAME:
        event.killer.give(token)
        event.killer.title("Guard defeated!", subtitle="You earned a Guard Token")
