# Plan (islands.py) -> blocks. Every piece of work is a small function; main.py runs a few per tick so the islands
# grow in front of you without the server stalling. Each step also remembers the box it touched, so collapse() can
# take everything down again and leave the sky as it was.
import math
import random

from craftpy import *
import islands as plan_mod

SKY = 70            # how far above the player the archipelago floats
FILL_LIMIT = 30000  # the game refuses bigger fills: larger boxes are split


class Build:
    def __init__(self, plan, theme, x, y, z, dimension, crystal):
        self.plan = plan
        self.theme = theme
        self.crystal = crystal   # the Sky Crystal block of main.py
        self.x, self.y, self.z = x, y, z
        self.dimension = dimension
        self.boxes = []          # what to clear on collapse, in the order it was built
        self.shards = []         # (x, y, z) of every Sky Crystal, absolute
        self.rng = random.Random(plan.seed)

    # -- helpers ---------------------------------------------------------------------------------------------------

    def at(self, island):
        return self.x + island.x, self.y + island.y, self.z + island.z

    def fill(self, a, b, block):
        world.fill(a, b, block, dimension=self.dimension)

    def put(self, x, y, z, block, replace=True):
        world.set_block(x, y, z, block, dimension=self.dimension, replace=replace)

    def touched(self, a, b):
        self.boxes.append((tuple(min(p, q) for p, q in zip(a, b)), tuple(max(p, q) for p, q in zip(a, b))))

    # -- the work -----------------------------------------------------------------------------------------------------

    def steps(self):
        """Every piece of work, hub first, then the islands in bridge order, then the bridges."""
        out = []
        order = sorted(self.plan.islands, key=lambda i: math.hypot(i.x, i.z))
        for island in order:
            out.extend(self.island_steps(island))
        for a, b in self.plan.bridges:
            out.append(lambda a=a, b=b: self.bridge(self.plan.islands[a], self.plan.islands[b]))
        return out

    def island_steps(self, island):
        cx, cy, cz = self.at(island)
        r = island.radius
        rows = []
        for dx in range(-r, r + 1):
            rows.append(lambda dx=dx: self.row(island, dx))
        self_box = ((cx - r - 1, cy - int(r * 1.6) - 4, cz - r - 1), (cx + r + 1, cy + 26, cz + r + 1))
        self.touched(*self_box)
        feature = {"spire": self.spire, "grove": self.grove, "falls": self.falls, "ruin": self.ruin,
                   "garden": self.garden, "crystals": self.crystals}[island.feature]
        tail = [lambda: feature(island), lambda: self.flowers(island)]
        if island.index != 0:
            tail.append(lambda: self.shard(island))
        return rows + tail

    def row(self, island, dx):
        """One slice of an island: for every column the rock that hangs below, two of soil, the surface on top."""
        cx, cy, cz = self.at(island)
        t = self.theme
        for dz in range(-island.radius, island.radius + 1):
            depth = plan_mod.column_depth(island, dx, dz, self.rng)
            if depth is None:
                continue
            x, z = cx + dx, cz + dz
            if depth > 3:
                self.fill((x, cy - depth, z), (x, cy - 3, z), t["ore"] if self.rng.random() < 0.08 else t["rock"])
            self.fill((x, cy - min(depth, 2), z), (x, cy - 1, z), t["soil"])
            self.put(x, cy, z, t["top"])
        world.particle(t["particle"], cx + dx, cy + 1, cz, count=4, dimension=self.dimension)

    def flowers(self, island):
        cx, cy, cz = self.at(island)
        for _ in range(island.radius * 2):
            dx, dz = self.rng.randint(-island.radius + 1, island.radius - 1), self.rng.randint(-island.radius + 1, island.radius - 1)
            if math.hypot(dx, dz) < island.radius - 1:
                self.put(cx + dx, cy + 1, cz + dz, self.rng.choice(self.theme["flowers"]), replace=False)

    def tree(self, x, y, z, height):
        t = self.theme
        self.fill((x - 2, y + height - 2, z - 2), (x + 2, y + height, z + 2), t["leaves"])
        self.fill((x - 1, y + height + 1, z - 1), (x + 1, y + height + 1, z + 1), t["leaves"])
        self.fill((x, y + 1, z), (x, y + height, z), t["log"])

    def grove(self, island):
        cx, cy, cz = self.at(island)
        for _ in range(self.rng.randint(2, 4)):
            a = self.rng.uniform(0, 2 * math.pi)
            d = self.rng.uniform(1, island.radius - 3)
            self.tree(round(cx + math.cos(a) * d), cy, round(cz + math.sin(a) * d), self.rng.randint(4, 7))

    def spire(self, island):
        """The hub: a tapering tower crowned with crystal and light, with a beam that the sky can see."""
        cx, cy, cz = self.at(island)
        t = self.theme
        height = 22
        for level in range(height):
            radius = max(0, 3 - level // 6)
            block = t["ore"] if level % 5 == 4 else t["ruin"]
            self.fill((cx - radius, cy + 1 + level, cz - radius), (cx + radius, cy + 1 + level, cz + radius), block)
        self.fill((cx - 1, cy + height + 1, cz - 1), (cx + 1, cy + height + 2, cz + 1), t["crystal"])
        self.put(cx, cy + height + 3, cz, t["glow"])
        for ox, oz in ((4, 0), (-4, 0), (0, 4), (0, -4)):
            self.fill((cx + ox, cy + 1, cz + oz), (cx + ox, cy + 3, cz + oz), t["ruin"])
            self.put(cx + ox, cy + 4, cz + oz, t["glow"])
        world.particle(t["sky"], cx, cy + height + 4, cz, count=60, dimension=self.dimension)

    def falls(self, island):
        """A spring at the rim pouring into a little basin island below (so no water reaches the ground)."""
        cx, cy, cz = self.at(island)
        t = self.theme
        a = self.rng.uniform(0, 2 * math.pi)
        rx, rz = round(cx + math.cos(a) * (island.radius - 1)), round(cz + math.sin(a) * (island.radius - 1))
        ox, oz = round(math.cos(a) * 2), round(math.sin(a) * 2)
        bx, by, bz = rx + ox, cy - int(island.radius * 1.6) - 10, rz + oz
        self.fill((bx - 3, by - 2, bz - 3), (bx + 3, by, bz + 3), t["rock"])
        self.fill((bx - 2, by, bz - 2), (bx + 2, by, bz + 2), t["soil"])
        self.touched((bx - 3, by - 2, bz - 3), (bx + 3, cy, bz + 3))
        if t["liquid"]:
            self.fill((bx - 1, by, bz - 1), (bx + 1, by, bz + 1), t["liquid"])
            self.put(rx, cy, rz, t["liquid"])
        else:  # a theme without water: a glowing column of light instead
            self.fill((rx + ox // 2, by + 1, rz + oz // 2), (rx + ox // 2, cy - 1, rz + oz // 2), t["glow"])
        self.put(rx, cy + 1, rz, t["glow"], replace=False)

    def ruin(self, island):
        """A broken arch: two pillars, half a lintel, a block or two fallen on the grass."""
        cx, cy, cz = self.at(island)
        t = self.theme
        self.fill((cx - 3, cy + 1, cz), (cx - 3, cy + 5, cz), t["ruin"])
        self.fill((cx + 3, cy + 1, cz), (cx + 3, cy + 3, cz), t["ruin"])
        self.fill((cx - 3, cy + 6, cz), (cx, cy + 6, cz), t["ruin"])
        self.put(cx + 2, cy + 1, cz + 2, t["ruin"])
        self.put(cx - 1, cy + 7, cz, t["glow"])

    def garden(self, island):
        cx, cy, cz = self.at(island)
        t = self.theme
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                if abs(dx) + abs(dz) <= 4:
                    self.put(cx + dx, cy + 1, cz + dz, self.rng.choice(t["flowers"]), replace=False)
        self.fill((cx, cy + 1, cz), (cx, cy + 2, cz), t["rail"])
        self.put(cx, cy + 3, cz, "minecraft:lantern")

    def crystals(self, island):
        """Shards of crystal breaking out of the grass at angles."""
        cx, cy, cz = self.at(island)
        t = self.theme
        for _ in range(self.rng.randint(4, 7)):
            a = self.rng.uniform(0, 2 * math.pi)
            d = self.rng.uniform(0, island.radius - 2)
            x, z = round(cx + math.cos(a) * d), round(cz + math.sin(a) * d)
            height = self.rng.randint(2, 6)
            lean = self.rng.choice([(1, 0), (-1, 0), (0, 1), (0, -1)])
            for h in range(height):
                self.put(x + lean[0] * (h // 3), cy + 1 + h, z + lean[1] * (h // 3), t["crystal"])
            self.put(x, cy, z, t["glow"])

    def shard(self, island):
        """The Sky Crystal of this island, on a little pedestal of light: break it to collect its shard."""
        cx, cy, cz = self.at(island)
        a = self.rng.uniform(0, 2 * math.pi)
        d = self.rng.uniform(1, island.radius - 2)
        x, z = round(cx + math.cos(a) * d), round(cz + math.sin(a) * d)
        self.put(x, cy + 1, z, self.theme["glow"])
        self.put(x, cy + 2, z, self.crystal)
        self.shards.append((x, cy + 2, z))

    def bridge(self, a, b):
        """A slab deck with fence rails and lanterns, from rim to rim."""
        t = self.theme
        path = [(self.x + x, self.y + y, self.z + z) for x, y, z in plan_mod.bridge_path(a, b)]
        if len(path) < 2:
            return
        length = math.hypot(b.x - a.x, b.z - a.z) or 1
        sx, sz = round(-(b.z - a.z) / length), round((b.x - a.x) / length)  # one step sideways
        for n, (x, y, z) in enumerate(path):
            self.put(x, y, z, t["deck"])
            self.put(x + sx, y, z + sz, t["deck"])
            self.put(x - sx, y + 1, z - sz, t["rail"])
            self.put(x + 2 * sx, y + 1, z + 2 * sz, t["rail"])
            if n % 6 == 3:
                self.put(x - sx, y + 2, z - sz, "minecraft:lantern")
                self.put(x + 2 * sx, y + 2, z + 2 * sz, "minecraft:lantern")
        xs = [p[0] for p in path]
        ys = [p[1] for p in path]
        zs = [p[2] for p in path]
        self.touched((min(xs) - 3, min(ys), min(zs) - 3), (max(xs) + 3, max(ys) + 3, max(zs) + 3))

    # -- taking it down -----------------------------------------------------------------------------------------------

    def collapse_steps(self):
        """Air over every box that was built, the newest first (water sources go before the basins they fill)."""
        steps = []
        for lo, hi in reversed(self.boxes):
            for part in split(lo, hi):
                steps.append(lambda part=part: self.fill(part[0], part[1], "minecraft:air"))
        return steps


def split(lo, hi):
    """A box cut into slabs the game agrees to fill."""
    width = (hi[0] - lo[0] + 1) * (hi[2] - lo[2] + 1)
    per = max(1, FILL_LIMIT // max(1, width))
    out = []
    y = lo[1]
    while y <= hi[1]:
        top = min(hi[1], y + per - 1)
        out.append(((lo[0], y, lo[2]), (hi[0], top, hi[2])))
        y = top + 1
    return out
