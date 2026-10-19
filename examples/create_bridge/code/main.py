from craftpy import *

AVAILABLE = 'rotation.create' in server.features().get('capabilities', [])
if AVAILABLE:
    motor = Block('motor', name='Bridge Motor', texture='minecraft:block/copper_block').extends(Kinetic('generator', axis='x', speed=64, capacity=32))
    processor = Block('mill', name='Bridge Mill', texture='minecraft:block/iron_block').extends(Kinetic('processor', axis='x', impact=8))
    native_shaft = NativeBlock('create_shaft', base='create:shaft')
    native_press = NativeBlock('create_press', base='create:mechanical_press')
    recipe('craftpy:kinetic_processing', id='sand', ingredient={'item':'minecraft:gravel'}, result={'id':'minecraft:sand'}, duration=100, minimum_speed=32)
    recipe('create:pressing', id='packed_copper', ingredients=[{'item':'minecraft:copper_ingot'}], results=[{'id':'create:copper_sheet','count':1}])
    shaped(motor, 'CCC', 'CSC', 'CCC', C='minecraft:copper_ingot', S='create:shaft')
    shaped(processor, 'III', 'ISI', 'III', I='minecraft:iron_ingot', S='create:shaft')

def main(ctx):
    ctx.log('Create Bridge ready: connect machines along X.' if AVAILABLE else 'Create Bridge unavailable: supported Create adapter is absent.')
