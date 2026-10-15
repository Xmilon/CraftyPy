from craftpy import *
from items import ruby

ruby_ore = Block("ruby_ore", texture="ruby_ore.png", hardness=3, resistance=3, sound="stone",
                 drops=(ruby, 2)).extends(             # mining it drops 2 rubies
    MineableBy("pickaxe", tier="iron"))                # an iron pickaxe or better

ruby_block = Block("ruby_block", texture="ruby_block.png", hardness=5, sound="metal", light=4).extends(
    MineableBy("pickaxe"), Redstone(power=7))          # a block of rubies powers redstone next to it
