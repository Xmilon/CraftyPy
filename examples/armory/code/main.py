from craftpy import *
import random
import time

import rules
from display import GEO_DISPLAY

# Armory: weapons with a character of their own.
#
#   Greatswords   Claymore, Obsidian Zweihander, Frostbrand: big 3D models (assets/models/item/*.json, boxes drawn in
#                 Blockbench's "Java Block/Item" format) held at 1.35 to 1.6 times the size of a normal sword.
#   Daggers       Bone Stiletto, Venom Kris, Shadowfang (throw it: hold right click, let go).
#   Spears        Hunter's Spear (thrown), Glaive: a spear with an axe blade, more reach.
#   Legends       Damocles' Sword, Scipio's Spear, Gungnir, Excalibur, Tyrfing: GeckoLib models with animations (a hanging
#                 sword that sways, a plume in the wind, a spinning spearhead, a pulsing gem, a trembling blade), glow masks
#                 and an animated flat icon; each does something of its own (see the extensions below, rules in rules.py).
#   Bows          Warbow, Twinfang (two arrows) and Artemis' Bow (needs no arrows): Bow(...) with three drawn pictures each.
#
# Every sound is made for the module (assets/sounds, named in assets/sounds.json). Without GeckoLib the legends fall back to
# their animated flat icons.


def hear(player, name, volume=1.0, pitch=1.0):
    player.play_sound("armory:" + name, volume, pitch)


def effect_of(entity, spec):
    """spec = (effect, seconds, level) put on the struck entity; living ones only, others are skipped quietly."""
    if spec is None or entity is None:
        return
    try:
        entity.effect(spec[0], spec[1], spec[2])
    except RuntimeError:
        pass


@extension
class Strike(Extension):
    """What an ordinary weapon does when it lands: a sound and, optionally, an effect on the struck mob."""

    def __init__(self, sound="clang", effect=None, pitch=1.0):
        self.sound, self.effect, self.pitch = sound, effect, pitch

    def on_hit(self, event):
        hear(event.player, self.sound, 0.9, self.pitch)
        effect_of(event.entity, self.effect)


@extension
class Dread(Extension):
    """Damocles' Sword hangs by a thread. Now and then the thread gives way: the blow lands three times as hard and the
    wielder pays for it (never more than leaves half a heart). Holding it is uneasy: a heartbeat now and then, and four
    half hearts less health while it is in the hand."""

    def on_hold_tick(self, event):
        if random.random() < 0.06:
            hear(event.player, "dread", 0.6)

    def on_hit(self, event):
        hear(event.player, "clang", 0.8, 0.8)
        if rules.thread_snaps(random.random()):
            event.damage = rules.snap_blow(event.damage)
            event.player.set_health(rules.backlash(event.player.health))
            event.player.actionbar("The thread snaps!")
            hear(event.player, "snap", 1.2)


@extension
class Triumph(Extension):
    """Scipio's Spear: the struck mob glows, and three quick hits in a row earn a triumph: a horn, Speed and Resistance."""
    history = {}

    def on_hit(self, event):
        hear(event.player, "thunk", 0.9)
        effect_of(event.entity, ("glowing", 6, 1))
        history, earned = rules.triumph(Triumph.history.get(event.player.uuid, []), time.time())
        Triumph.history[event.player.uuid] = history
        if earned:
            hear(event.player, "horn", 1.0)
            event.player.effect("speed", 6, 2)
            event.player.effect("resistance", 6, 1)
            event.player.actionbar("Triumph!")


@extension
class Stormcall(Extension):
    """Gungnir: every blow crackles and slows what it strikes, and one in five calls an extra bolt of 6 damage."""

    def on_hit(self, event):
        hear(event.player, "crackle", 0.9)
        effect_of(event.entity, ("slowness", 2, 2))
        if random.random() < 0.2:
            event.entity.damage(6)


@extension
class Holy(Extension):
    """Excalibur: twice the damage against the undead, and every hit heals its wielder a heart."""

    def on_hit(self, event):
        event.damage = rules.holy_blow(event.damage, event.entity.native_type)
        event.player.heal(rules.HOLY_HEAL)
        hear(event.player, "holy", 0.8)


@extension
class Bloodthirst(Extension):
    """Tyrfing: a third of every blow comes back as health. It hums while held."""

    def on_hold_tick(self, event):
        if random.random() < 0.05:
            hear(event.player, "cursed", 0.5)

    def on_hit(self, event):
        event.player.heal(rules.blood_heal(event.damage))
        hear(event.player, "stab", 0.9, 0.8)


def tip(*lines):
    return Tooltip(*lines)


# ---- greatswords: big 3D models -------------------------------------------------------------------------------------

claymore = Item("claymore", name="Claymore", model="claymore", texture="claymore.png").extends(
    Weapon(damage=9, attack_speed=1.0, knockback=0.5), Durability(uses=900), Strike("swing_heavy"),
    tip("A two-handed iron blade: slow and heavy."))
shaped(claymore, " I ", " I ", "ISI", I="iron_block", S="stick")

zweihander = Item("zweihander", name="Obsidian Zweihander", model="zweihander", texture="zweihander.png").extends(
    Weapon(damage=12, attack_speed=0.8, knockback=1.0), Durability(uses=1100),
    Attributes("minecraft:generic.movement_speed", -0.12, "multiply_base"),
    Strike("swing_heavy", effect=("slowness", 2, 2), pitch=0.8),
    tip("Glass-hard and far too heavy to run in.", "Slows what it strikes."))
shaped(zweihander, " O ", " O ", "OBO", O="obsidian", B="blaze_rod")

frostbrand = Item("frostbrand", name="Frostbrand", model="frostbrand", texture="frostbrand.png").extends(
    Weapon(damage=8, attack_speed=1.4, knockback=0.2), Durability(uses=800),
    Strike("frost", effect=("slowness", 3, 2)),
    tip("Cold enough to slow whatever it cuts."))
shaped(frostbrand, " P ", " P ", "PSP", P="packed_ice", S="stick")

# ---- daggers ------------------------------------------------------------------------------------------------------------

bone_stiletto = Item("bone_stiletto", name="Bone Stiletto", model="bone_stiletto", texture="bone_stiletto.png").extends(
    Weapon(damage=3.5, attack_speed=3.0), Durability(uses=300), Strike("stab", effect=("wither", 2, 1), pitch=1.2),
    tip("Very fast. Leaves a wound that will not close."))
shaped(bone_stiletto, " B ", " B ", " S ", B="bone", S="stick")

venom_kris = Item("venom_kris", name="Venom Kris", model="venom_kris", texture="venom_kris.png").extends(
    Weapon(damage=4, attack_speed=2.6), Durability(uses=350), Strike("hiss", effect=("poison", 4, 2)),
    tip("A wavy blade bathed in spider venom."))
shaped(venom_kris, " F ", " F ", " S ", F="fermented_spider_eye", S="stick")

shadowfang = Item("shadowfang", name="Shadowfang", model="shadowfang", texture="shadowfang.png").extends(
    Weapon(damage=4.5, attack_speed=2.4), Durability(uses=300),
    Throwable(speed=2.8, damage=8, charge_seconds=0.3, throw_sound="armory:throw", hit_sound="armory:stab"),
    Strike("stab", pitch=1.1),
    tip("Hold right click and let go: it flies, and can be picked up again."))
shaped(shadowfang, " A ", " A ", " S ", A="amethyst_shard", S="stick")

# ---- spears ---------------------------------------------------------------------------------------------------------------

REACH = "minecraft:player.entity_interaction_range"

hunters_spear = Item("hunters_spear", name="Hunter's Spear", model="hunters_spear", texture="hunters_spear.png").extends(
    Weapon(damage=5, attack_speed=1.2), Durability(uses=450), Attributes(REACH, 1.0),
    Throwable(speed=2.5, damage=9, charge_seconds=0.5, pierce=1, throw_sound="armory:throw", hit_sound="armory:thunk"),
    Strike("thunk"),
    tip("One block of extra reach. Throw it: it goes through one mob."))
shaped(hunters_spear, " I ", " S ", " S ", I="iron_ingot", S="stick")

glaive = Item("glaive", name="Glaive", model="glaive", texture="glaive.png").extends(
    Weapon(damage=9, attack_speed=1.0, knockback=0.3), Durability(uses=700), Attributes(REACH, 1.5),
    Strike("swing_heavy", pitch=1.1),
    tip("An axe blade on a long pole: a block and a half of reach."))
shaped(glaive, " II", " SI", "S  ", I="iron_ingot", S="stick")

# ---- legends: GeckoLib models, animated icons, abilities --------------------------------------------------------------

damocles = Item("damocles", name="Damocles' Sword", texture="damocles.png", geo="damocles", display=GEO_DISPLAY).extends(
    Weapon(damage=14, attack_speed=1.1), Durability(uses=1500),
    Attributes("minecraft:generic.max_health", -4, slot="mainhand"), Dread(),
    tip("Held by a single thread.", "Sometimes it falls: three times the blow, and you pay for it.", "Costs 2 hearts while held."))
shaped(damocles, " N ", " D ", "GSG", N="nether_star", D="diamond_block", G="gold_ingot", S="string")

scipio = Item("scipio", name="Scipio's Spear", texture="scipio.png", geo="scipio", display=GEO_DISPLAY).extends(
    Weapon(damage=7, attack_speed=1.2), Durability(uses=1200), Attributes(REACH, 1.0), Triumph(),
    Throwable(speed=3.0, damage=16, charge_seconds=0.6, pierce=3, throw_sound="armory:throw", hit_sound="armory:thunk"),
    tip("Strikes mark enemies. Three quick hits earn a triumph.", "Thrown, it goes through three mobs."))
shaped(scipio, " G ", " B ", " S ", G="gold_block", B="blaze_rod", S="stick")

gungnir = Item("gungnir", name="Gungnir", texture="gungnir.png", geo="gungnir", display=GEO_DISPLAY).extends(
    Weapon(damage=8, attack_speed=1.3), Durability(uses=1400), Attributes(REACH, 1.5), Stormcall(),
    Throwable(speed=3.4, damage=18, charge_seconds=0.5, pierce=5, gravity=False, throw_sound="armory:throw", hit_sound="armory:crackle"),
    tip("Never falls short: thrown, it flies straight and through five mobs.", "Its blows crackle and slow."))
shaped(gungnir, " L ", " N ", " S ", L="lightning_rod", N="nether_star", S="blaze_rod")

excalibur = Item("excalibur", name="Excalibur", texture="excalibur.png", geo="excalibur", display=GEO_DISPLAY).extends(
    Weapon(damage=10, attack_speed=1.6), Durability(uses=1800), Holy(),
    tip("Twice the damage against the undead.", "Every hit heals you a heart."))
shaped(excalibur, " D ", " D ", "GNG", D="diamond_block", G="gold_block", N="nether_star")

tyrfing = Item("tyrfing", name="Tyrfing", texture="tyrfing.png", geo="tyrfing", display=GEO_DISPLAY).extends(
    Weapon(damage=9, attack_speed=1.5), Durability(uses=1300), Bloodthirst(),
    tip("A third of every blow comes back as health.", "It hums while you hold it."))
shaped(tyrfing, " R ", " N ", "OSO", R="redstone_block", N="netherite_ingot", O="obsidian", S="blaze_rod")

# ---- bows ----------------------------------------------------------------------------------------------------------------

warbow = Item("warbow", name="Warbow", texture="warbow.png").extends(
    Bow(draw_seconds=1.3, power=3.6, damage=9, spread=0.6, draw_sound="armory:bow_draw", full_sound="armory:bow_ready",
        shoot_sound="armory:bow_shoot"),
    Durability(uses=700), tip("Slow to draw and hard to hold: long range, heavy arrows."))
shaped(warbow, " SI", "S I", " SI", S="stick", I="string")

twinfang = Item("twinfang", name="Twinfang", texture="twinfang.png").extends(
    Bow(draw_seconds=0.9, power=3.0, damage=5.5, arrows=2, spread=1.5, draw_sound="armory:bow_draw", full_sound="armory:bow_ready",
        shoot_sound="armory:bow_shoot_light"),
    Durability(uses=500), tip("Two arrows at once, one fang each."))
shaped(twinfang, " BS", "B S", " BS", B="bone", S="string")

artemis = Item("artemis", name="Artemis' Bow", texture="artemis.png").extends(
    Bow(draw_seconds=0.7, power=3.4, damage=8, infinite=True, draw_sound="armory:bow_draw", full_sound="armory:silver",
        shoot_sound="armory:bow_shoot_light"),
    Durability(uses=1600), tip("Silver light. It needs no arrows."))
shaped(artemis, " NS", "N S", " NS", N="nether_star", S="string")


@command("kit", level=2)
def kit(ctx, target: Player):
    """/armory:kit <player> - operators: every weapon of the module and arrows to try the bows."""
    for weapon in (claymore, zweihander, frostbrand, bone_stiletto, venom_kris, shadowfang, hunters_spear, glaive,
                   damocles, scipio, gungnir, excalibur, tyrfing, warbow, twinfang, artemis):
        target.give(weapon)
    target.give("arrow", 64)
