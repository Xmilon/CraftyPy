from craftpy import *
from surge import start_surge


@command("surge")
def surge_command(ctx, target: Player):
    """/ruby_rush:surge <player> - start a surge right away."""
    start_surge(target)
