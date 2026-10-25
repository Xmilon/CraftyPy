# The rules of the legendary weapons, apart from the game so they can be tested (code/test_rules.py).

UNDEAD = frozenset({
    "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "minecraft:zombified_piglin",
    "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:bogged", "minecraft:wither",
    "minecraft:phantom", "minecraft:zoglin", "minecraft:skeleton_horse", "minecraft:zombie_horse",
})

SNAP_CHANCE = 1 / 6        # Damocles: how often the thread gives way on a hit
SNAP_MULTIPLIER = 3.0      # ... and the blow it lands
SNAP_BACKLASH = 6.0        # ... and what the wielder pays (half hearts)
HOLY_MULTIPLIER = 2.0      # Excalibur against the undead
HOLY_HEAL = 2.0            # ... and what it heals the wielder on every hit
BLOOD_RATIO = 0.3          # Tyrfing: the part of every blow that comes back as health
TRIUMPH_HITS = 3           # Scipio: this many hits ...
TRIUMPH_WINDOW = 4.0       # ... within this many seconds earn a triumph


def thread_snaps(roll):
    """Damocles' thread gives way when the roll (0..1) is under the chance."""
    return roll < SNAP_CHANCE


def snap_blow(damage):
    return damage * SNAP_MULTIPLIER


def backlash(health):
    """What is left of the wielder's health after the thread snaps: never the last half heart."""
    return max(1.0, health - SNAP_BACKLASH)


def holy_blow(damage, native_type):
    return damage * HOLY_MULTIPLIER if native_type in UNDEAD else damage


def blood_heal(damage):
    return damage * BLOOD_RATIO


def triumph(history, now):
    """The times of a wielder's recent hits and whether this hit makes a triumph: (new history, earned). A triumph clears it."""
    recent = [t for t in history if now - t <= TRIUMPH_WINDOW] + [now]
    if len(recent) >= TRIUMPH_HITS:
        return [], True
    return recent, False
