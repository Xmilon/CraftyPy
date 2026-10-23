import unittest

import conversion


class PlanTest(unittest.TestCase):
    def test_nothing_to_burn(self):
        self.assertEqual(conversion.plan(0, 0, 1000), (0, 0))

    def test_full_store_burns_nothing(self):
        self.assertEqual(conversion.plan(500, 1000, 1000), (0, 0))

    def test_limited_by_the_step(self):
        self.assertEqual(conversion.plan(5000, 0, 10_000_000), (100, 100 * conversion.FE_PER_MB))

    def test_limited_by_the_liquid(self):
        self.assertEqual(conversion.plan(7, 0, 10_000_000), (7, 7 * conversion.FE_PER_MB))

    def test_limited_by_the_room_left(self):
        # room for 3 whole millibuckets only
        capacity = 3 * conversion.FE_PER_MB + 10
        self.assertEqual(conversion.plan(500, 0, capacity), (3, 3 * conversion.FE_PER_MB))

    def test_never_adds_more_than_the_room(self):
        burn, energy = conversion.plan(500, 100, 400)
        self.assertLessEqual(100 + energy, 400)


if __name__ == "__main__":
    unittest.main()
