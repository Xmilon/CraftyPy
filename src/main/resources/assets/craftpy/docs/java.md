# Java bridge

The CraftPy API wraps the common things. For everything else - any Minecraft or NeoForge class, and classes of other
mods - the **Java bridge** lets Python use the real Java API by name, with no wrapper and no recompiling. It is the
translation layer between Python and the JVM: Python holds *handles* to live Java objects, and the game reads
fields, calls methods, builds objects and delivers events for them.

```python
from craftpy import *

Blocks = java.cls("net.minecraft.world.level.block.Blocks")     # a class
print(Blocks.STONE)                                              # static field
BlockPos = java.cls("net.minecraft.core.BlockPos")
pos = BlockPos(10, 64, 10)                                       # calling a class builds an object

@command("probe")
def probe(ctx):
    level = ctx.player.java.level()                               # player.java is the real ServerPlayer
    state = level.getBlockState(pos)
    ctx.player.say("block:", state.getBlock().getDescriptionId())
```

The bridge reaches anything the game can do, so a project must declare it in `project.json`:
`"permissions": {"java": true}`. A module that declares it shows a FULL TRUST warning and works only after
**Approve access** on the Modules page. CraftPy's own internals (permissions, files, paths, config) are never reachable.

Names are the **Mojang-mapped** names the game itself uses (`getBlockState`, `setCanceled`, `isClientSide`).
`java.members(Blocks)` lists the public fields and methods of a class or object, `java.mods()` the loaded mods.
To reach live game objects: `player.java` / `entity.java` (from an event or `players.get`), `java.level("minecraft:the_nether")`,
`java.server()` and `java.entity(uuid)`.

## Using objects

| In Python | What happens in Java |
| --- | --- |
| `obj.field` | reads a public field (`obj.field = 3` writes it, unless final) |
| `obj.method(a, b)` | calls the public method that fits the arguments best (`add(int)` over `add(double)` for `2`) |
| `Cls.STATIC`, `Cls.staticMethod()` | static field / method |
| `Cls(a, b)` or `java.new("a.b.Cls", a, b)` | constructor |
| `for x in obj` / `java.items(obj)` | iterate a List, Set, array, Stream, Optional; a Map yields `[key, value]` pairs |
| `str(obj)` | Java's `toString()` |
| `obj == other` | the same Java object |

Arguments are converted to what the Java parameter wants:

* numbers: `2` fits `int`, `long`, `double`...; `2.5` does **not** fit a whole-number parameter (no silent rounding)
* a string becomes an enum constant (`"green"` for `Color.GREEN`, any case), a `UUID`, a `ResourceLocation`, a
  `Component`, or a registry object by id where Java wants a `Block`, `Item`, `EntityType`, `Fluid`, `SoundEvent`,
  `MobEffect`, `Attribute`, `ParticleType`, `Potion` or `BlockEntityType` (`"minecraft:stone"`, `"stone"`)
* `[x, y, z]` becomes a `BlockPos`, `Vec3` or `Vec3i`; `[x, z]` a `ChunkPos` or `Vec2`
* lists become lists (or arrays where Java wants an array), dicts become maps
* a **Python function** becomes a `Runnable`, `Consumer`, `Function`, `Predicate`, `Supplier` or any other one-method
  interface: `server.execute(lambda: print("on the server thread"))`
* `None` is `null`

Results come back as Python numbers, booleans and strings, or as handles for everything else. Handles are released
when Python drops them.

## Events: any NeoForge event

```python
@java.on("net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent")
def hurt(event):
    if event.getAmount() > 10:
        event.setCanceled(True)                                   # works: Python runs before the event goes on
```

`priority` is `highest`, `high`, `normal` (default), `low` or `lowest`; `receive_cancelled=True` also receives events
other handlers already cancelled. Nested event classes use `$` or a dot: `...LivingDamageEvent.Post`.
The handler has one second. It costs nothing when nobody subscribed to that event.

## Other mods ("modded classes")

Add the mod's package to the server config `javaBridge.extraPackages` (for example `com.example.mymod.`), then use its
classes exactly like Minecraft's:

```python
Machine = java.cls("com.example.mymod.block.MachineBlockEntity")
```

`java.mods()` shows what is loaded. Mods that register their own events, capabilities or registries are reachable the
same way; objects they return are handles you can call methods on.

## Safety

* The bridge runs on the server only, with the permissions of whoever can load projects (operators).
* `javaBridge.mode` in the config: `OFF`, `RESTRICTED` (default: Minecraft, NeoForge, Mojang libraries, CraftPy,
  plus `extraPackages`) or `FULL` (every class not on the deny list).
* Always denied, in every mode: processes, `System`, threads, reflection, class loading, files, network, the mod
  loader, `java.util.concurrent`, and the methods `getClass`, `wait`, `notify`, `forName`, `getClassLoader`.
  A script can drive the game; it cannot leave the game.
* Errors from Java come back as a `JavaError` with the Java exception's message; a failed call never crashes the game.

## Limits

* Only **public** members of **public** classes. No mixins, no new registry entries after startup, no subclassing
  Java classes from Python (pass a function where an interface is wanted).
* Calls are synchronous and run on the server thread: keep them short. Handlers must finish in one second.
* Obfuscated or intermediary names are not available - use the Mojang names.
