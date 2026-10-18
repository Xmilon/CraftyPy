# Ctrl+Shift+F10 runs these. They check the plan, so a change to islands.py cannot strand a crystal out of reach.
import random

import islands


def test_every_island_can_be_reached_by_bridge():
    for seed in range(30):
        for size in islands.SIZES:
            plan = islands.generate(size, seed)
            assert plan.reachable() == set(range(len(plan.islands))), (size, seed)
            assert len(plan.bridges) == len(plan.islands) - 1


def test_islands_do_not_overlap_and_most_are_placed():
    for seed in range(30):
        for size, count in islands.SIZES.items():
            plan = islands.generate(size, seed)
            assert len(plan.islands) >= count - 1, (size, seed, len(plan.islands))
            for a in plan.islands:
                for b in plan.islands:
                    if a is not b:
                        assert a.gap(b) >= 4, (size, seed, a.index, b.index)


def test_same_seed_same_archipelago():
    a, b = islands.generate("large", 99), islands.generate("large", 99)
    assert [(i.x, i.y, i.z, i.radius, i.feature) for i in a.islands] == [(i.x, i.y, i.z, i.radius, i.feature) for i in b.islands]
    assert a.bridges == b.bridges


def test_bridges_run_from_rim_to_rim():
    plan = islands.generate("medium", 7)
    for a, b in plan.bridges:
        path = islands.bridge_path(plan.islands[a], plan.islands[b])
        assert len(path) >= 2
        steps = [abs(p[0] - q[0]) + abs(p[2] - q[2]) for p, q in zip(path, path[1:])]
        assert max(steps) <= 2, steps   # no holes in the deck


def test_islands_hang_deepest_in_the_middle():
    island = islands.Island(1, 0, 0, 0, 8, "grove")
    rng = random.Random(1)
    assert islands.column_depth(island, 0, 0, rng) > islands.column_depth(island, 7, 0, rng)
    assert islands.column_depth(island, 20, 0, rng) is None
