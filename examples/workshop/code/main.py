from craftpy import *

motor = Block('motor', name='Workshop Motor', texture='minecraft:block/copper_block').extends(Kinetic('generator', axis='x', speed=32, capacity=32))
shaft = Block('shaft', name='Workshop Shaft', texture='minecraft:block/iron_block').extends(Kinetic('shaft', axis='x'))
processor = Block('processor', name='Workshop Mill', texture='minecraft:block/smooth_stone').extends(Kinetic('processor', axis='x', impact=8))
battery = Block('battery', name='Workshop Battery', texture='minecraft:block/redstone_block').extends(EnergyStorage(100000, 1000, 1000))
tank = Block('tank', name='Workshop Water Tank', texture='minecraft:block/light_blue_stained_glass').extends(FluidTank(16000, ['minecraft:water']))
for result, material in ((motor, 'copper_ingot'), (shaft, 'iron_ingot'), (processor, 'stone'), (battery, 'redstone'), (tank, 'glass')):
    shaped(result, 'MMM', 'MIM', 'MMM', M='minecraft:' + material, I='minecraft:iron_ingot')
recipe('craftpy:kinetic_processing', id='gravel', ingredient={'item':'minecraft:cobblestone'}, result={'id':'minecraft:gravel','count':1}, duration=100, minimum_speed=16)

@command('inspect')
def inspect_machine(ctx, x: int, y: int, z: int):
    if ctx.player:
        try:
            ctx.player.say(str(world.resources((x,y,z), dimension=ctx.player.dimension, side='up')))
            ctx.player.say(str(world.machine((x,y,z), dimension=ctx.player.dimension)))
        except Exception as error:
            ctx.player.say(str(error))
