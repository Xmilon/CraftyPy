from craftpy import *
from items import ruby, ruby_sword, ruby_apple
from blocks import ruby_ore, ruby_block

shaped(ruby_block, "RRR", "RRR", "RRR", R=ruby)
shapeless(ruby, ruby_block, count=9)
shaped(ruby_sword, "R", "R", "S", R=ruby, S="stick")
shaped(ruby_apple, " R ", "RAR", " R ", R=ruby, A="apple")
smelt(ruby_ore, ruby, xp=1.0)

tag("ruby_gems", [ruby, "diamond"])                    # item tag ruby_rush:ruby_gems
