from craftpy import *

if 'curios' in server.mods():
    luck = Item('luck_ring', name='Prospector Ring', texture='minecraft:gold_nugget', stack=1, tags=['curios:ring']).extends(Attributes('minecraft:generic.luck', 1, slot='curios:ring'), Tooltip('Wear in a Curios ring slot'))
    health = Item('health_ring', name='Vitality Ring', texture='minecraft:iron_nugget', stack=1, tags=['curios:ring']).extends(Attributes('minecraft:generic.max_health', 2, slot='curios:ring'), Tooltip('Wear in a Curios ring slot'))
    shaped(luck, ' G ', 'GEG', ' G ', G='minecraft:gold_ingot', E='minecraft:emerald')
    shaped(health, ' I ', 'IDI', ' I ', I='minecraft:iron_ingot', D='minecraft:diamond')

def main(ctx):
    ctx.log('Curios Charms ready.' if 'curios' in server.mods() else 'Curios Charms unavailable: install Curios to use its equipment.')
