# The rules of the pack buttons, apart from the game so they can be tested (code/test_rules.py).

DISTILL_MB = 100       # Starbrine one press of Distill uses
DISTILL_FE = 4000      # the energy it gives: a bucket (1000 mB) is 40 000 FE


def fits(room):
    """True when the energy store has room for one press of Distill."""
    return room >= DISTILL_FE


def best_food(slots, missing):
    """The index of the food that fits the hunger best: the biggest meal that does not waste more than a third of it, else
    the smallest one; None when there is no food. slots: what ui.screen_slots returned."""
    meals = [(i, s["food"]) for i, s in enumerate(slots) if s.get("food") and s.get("count", 0) > 0]
    if not meals:
        return None
    fitting = [m for m in meals if m[1] <= missing * 4 // 3]
    if fitting:
        return max(fitting, key=lambda m: m[1])[0]
    return min(meals, key=lambda m: m[1])[0]
