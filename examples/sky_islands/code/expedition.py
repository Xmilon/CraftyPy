# One player's archipelago: building it, the hunt for its Sky Crystals, the record, and taking it down again.
import random

from craftpy import *
import builder
import islands
from themes import THEMES

EXPEDITIONS = {}   # player name -> Expedition


def fmt(ticks):
    if ticks is None:
        return "-"
    seconds = ticks // 20
    return "%d:%02d" % (seconds // 60, seconds % 60)


class Expedition:
    def __init__(self, player, size, theme, build, origin):
        self.player = player
        self.size = size
        self.theme = theme
        self.build = build
        self.origin = origin          # (x, y, z, dimension) to go back to
        self.state = "building"       # building, exploring, done, gone
        self.found = set()
        self.ticks = 0
        self.task = None

    @property
    def total(self):
        return len(self.build.shards)

    def hub(self):
        hub = self.build.plan.hub
        return self.build.x + hub.x + 0.5, self.build.y + hub.y + 1, self.build.z + hub.z + 6.5

    def bring_back(self, p, words):
        x, y, z = self.hub()
        p.teleport(x, y, z, dimension=self.build.dimension)
        p.effect("slow_falling", seconds=20, particles=False)
        p.actionbar(words)

    def leave(self, p):
        x, y, z, dimension = self.origin
        p.teleport(x, y, z, dimension=dimension)
        if self.state == "exploring":
            self.state = "done"
        p.say("§7Back on the ground. Your islands stay in the sky until you take them down.")


def start(p, size, theme, seed, crystal):
    """Plans an archipelago above p, grows it a few pieces per tick, then lifts p onto the hub."""
    old = EXPEDITIONS.get(p.name)
    if old is not None and old.state == "building":
        p.say("§cYour islands are still rising.")
        return None
    if old is not None and old.state in ("exploring", "done"):
        p.say("§cTake your old islands down first (Take them down, or /sky_islands:collapse).")
        return None
    plan = islands.generate(size, seed)
    x, y, z = int(p.x // 1), int(p.y // 1), int(p.z // 1)
    build = builder.Build(plan, THEMES[theme], x, min(y + builder.SKY, 280), z, p.dimension, crystal)
    exp = Expedition(p.name, size, theme, build, (p.x, p.y, p.z, p.dimension))
    EXPEDITIONS[p.name] = exp
    steps = build.steps()
    total = len(steps)
    p.title("§bThe sky stirs", subtitle="§7%d %s islands, seed %d" % (len(plan.islands), theme, plan.seed), seconds=2)

    def work():
        for _ in range(2):
            if not steps:
                return arrive()
            steps.pop(0)()
        done = total - len(steps)
        if done % 5 == 0:
            filled = done * 20 // total
            p.actionbar("§bRaising  " + "█" * filled + "§8" + "░" * (20 - filled) + "§b  %d%%" % (done * 100 // total))
            p.play_sound("minecraft:block.amethyst_block.chime", volume=0.5, pitch=0.5 + done / total)
        exp.task = after(0.05, work)

    def arrive():
        exp.state = "exploring"
        exp.bring_back(p, "§bFind the %d Sky Crystals" % exp.total)
        p.play_sound("minecraft:entity.player.levelup", volume=0.6, pitch=0.8)
        p.title("§bSky Islands", subtitle="§7break the %d glowing crystals" % exp.total, seconds=3)
        storage["raised"] = storage.get("raised", 0) + 1
        ui.open(p, "sky_status")

    exp.task = after(0.05, work)
    return exp


def collapse(p):
    exp = EXPEDITIONS.get(p.name)
    if exp is None or exp.state == "building":
        p.say("§7Nothing to take down." if exp is None else "§cWait until it has finished rising.")
        return
    if exp.state == "exploring":
        exp.leave(p)
    steps = exp.build.collapse_steps()
    exp.state = "gone"

    def work():
        for _ in range(3):
            if not steps:
                EXPEDITIONS.pop(p.name, None)
                p.actionbar("§7The islands drift apart and are gone")
                return
            steps.pop(0)()
        after(0.05, work)

    work()


def crystal_broken(p, pos):
    """A Sky Crystal was broken: count it for the expedition it belongs to."""
    spot = (int(pos.x), int(pos.y), int(pos.z))
    for exp in EXPEDITIONS.values():
        if spot in exp.build.shards and spot not in exp.found:
            exp.found.add(spot)
            dim = exp.build.dimension
            world.particle("minecraft:end_rod", spot[0] + 0.5, spot[1] + 0.5, spot[2] + 0.5, count=40, dimension=dim)
            p.play_sound("minecraft:block.amethyst_cluster.break", pitch=1.2)
            if len(exp.found) < exp.total:
                p.actionbar("§bSky Crystal %d / %d" % (len(exp.found), exp.total))
            else:
                finish(exp, p)
            ui.refresh(p, "sky_status")
            return


def finish(exp, p):
    exp.state = "done"
    key = "best:" + exp.size
    best = storage.get(key)
    record = best is None or exp.ticks < best
    if record:
        storage[key] = exp.ticks
    storage["completed"] = storage.get("completed", 0) + 1
    p.title("§6All crystals!", subtitle="§7%s%s" % (fmt(exp.ticks), "  §enew record" if record else ""), seconds=4)
    p.add_xp(levels=exp.total)
    p.play_sound("minecraft:ui.toast.challenge_complete")
    x, y, z = exp.hub()
    rng = random.Random()
    for _ in range(12):
        world.particle("minecraft:firework", x + rng.uniform(-6, 6), y + rng.uniform(4, 14), z + rng.uniform(-6, 6),
                       count=30, dimension=exp.build.dimension)


def every_second():
    """Time, a soft net under the islands, and the glow of the crystals still to find."""
    for exp in list(EXPEDITIONS.values()):
        if exp.state != "exploring":
            continue
        exp.ticks += 20
        for spot in exp.build.shards:
            if spot not in exp.found:
                world.particle(exp.build.theme["sky"], spot[0] + 0.5, spot[1] + 1.2, spot[2] + 0.5, count=3,
                               dimension=exp.build.dimension)
        p = Player.named(exp.player)
        if p is not None and p.dimension == exp.build.dimension and p.y < exp.build.y - 45:
            exp.bring_back(p, "§bThe wind carries you back up")
