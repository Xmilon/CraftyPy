# The plan of an archipelago: where the islands float, how big they are, what grows on them and which bridges join
# them. Plain Python, no game calls, so test_islands.py can check it (every island reachable, nothing overlapping).
import math
import random

SIZES = {"small": 5, "medium": 8, "large": 12}   # number of islands
SPREAD = {"small": 30, "medium": 42, "large": 56}  # how far from the centre they can float

# what can stand on an island besides its shard (the hub always has the spire)
FEATURES = ("grove", "falls", "ruin", "garden", "crystals")


class Island:
    def __init__(self, index, x, y, z, radius, feature):
        self.index = index
        self.x, self.y, self.z = x, y, z        # centre of the top surface, relative to the archipelago
        self.radius = radius
        self.feature = feature

    def gap(self, other):
        """Air between the rims of two islands, measured flat."""
        return math.hypot(self.x - other.x, self.z - other.z) - self.radius - other.radius


class Plan:
    def __init__(self, seed, islands, bridges):
        self.seed = seed
        self.islands = islands
        self.bridges = bridges   # (index, index) pairs, a tree: every island reachable, no loops

    @property
    def hub(self):
        return self.islands[0]

    def neighbours(self, index):
        return [b if a == index else a for a, b in self.bridges if index in (a, b)]

    def reachable(self):
        seen, todo = {0}, [0]
        while todo:
            for other in self.neighbours(todo.pop()):
                if other not in seen:
                    seen.add(other)
                    todo.append(other)
        return seen

    def extent(self):
        """The box the archipelago fills, relative to its centre (x0, y0, z0, x1, y1, z1)."""
        xs = [i.x - i.radius for i in self.islands] + [i.x + i.radius for i in self.islands]
        zs = [i.z - i.radius for i in self.islands] + [i.z + i.radius for i in self.islands]
        ys = [i.y - i.radius - 14 for i in self.islands] + [i.y + 30 for i in self.islands]
        return min(xs) - 2, min(ys), min(zs) - 2, max(xs) + 2, max(ys), max(zs) + 2


def generate(size="medium", seed=None):
    """A new archipelago. The same size and seed give the same plan."""
    if size not in SIZES:
        raise ValueError("size must be one of: " + ", ".join(SIZES))
    seed = random.randrange(1, 1 << 30) if seed is None else int(seed)
    rng = random.Random(seed)
    count, spread = SIZES[size], SPREAD[size]
    islands = [Island(0, 0, 0, 0, 11, "spire")]
    tries = 0
    while len(islands) < count and tries < 4000:
        tries += 1
        radius = rng.randint(5, 9)
        angle = rng.uniform(0, 2 * math.pi)
        distance = rng.uniform(18, spread)
        candidate = Island(len(islands), round(math.cos(angle) * distance), rng.randint(-10, 12),
                           round(math.sin(angle) * distance), radius, rng.choice(FEATURES))
        # room for a bridge between any two: 4 blocks of air at least, at most 28 to the nearest one
        gaps = [candidate.gap(other) for other in islands]
        if min(gaps) >= 4 and min(gaps) <= 28:
            islands.append(candidate)
    return Plan(seed, islands, _bridges(islands))


def _bridges(islands):
    """The shortest set of bridges that joins every island (Prim's tree on the gaps)."""
    joined, bridges = {0}, []
    while len(joined) < len(islands):
        best = None
        for a in joined:
            for b in range(len(islands)):
                if b not in joined:
                    gap = islands[a].gap(islands[b])
                    if best is None or gap < best[0]:
                        best = (gap, a, b)
        _, a, b = best
        bridges.append((a, b))
        joined.add(b)
    return bridges


def column_depth(island, dx, dz, rng):
    """How far the rock hangs below the grass at (dx, dz) of an island: deep in the middle, a ragged point below,
    thin at the rim. None outside the island."""
    distance = math.hypot(dx, dz)
    if distance > island.radius + 0.4:
        return None
    edge = 1 - distance / (island.radius + 0.5)
    return max(1, int(edge ** 0.8 * island.radius * 1.6 + rng.random() * 2.2))


def bridge_path(a, b):
    """The blocks (x, y, z) of a bridge deck from rim to rim, sagging a little in the middle like a rope bridge."""
    ax, az, bx, bz = a.x, a.z, b.x, b.z
    length = math.hypot(bx - ax, bz - az) or 1
    ux, uz = (bx - ax) / length, (bz - az) / length
    start, end = a.radius - 1, length - b.radius + 1
    steps = max(1, int(end - start))
    path = []
    for i in range(steps + 1):
        t = i / steps
        along = start + (end - start) * t
        y = round(a.y + (b.y - a.y) * t - math.sin(math.pi * t) * min(3, steps / 10))
        cell = (round(ax + ux * along), y, round(az + uz * along))
        if not path or path[-1] != cell:
            path.append(cell)
    return path
