from craftpy import *

ruby = Item("ruby", texture="ruby.png")

ruby_sword = Item("ruby_sword", texture="ruby_sword.png",
                  effect=("strength", 2, 8)).extends(   # right click: Strength II for 8 s
    Sword(tier="diamond", damage=8), Durability(1200, repair_with=ruby))

ruby_apple = Item("ruby_apple", texture="ruby_apple.png",
                  food=Food(hunger=6, saturation=1.2, always_eat=True))
