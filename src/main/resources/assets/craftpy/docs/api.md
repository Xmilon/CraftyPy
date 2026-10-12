# CraftPy Python API Reference

Welcome to the CraftPy API. Use this reference to build your Minecraft modules.

## Declarative API

### @block(id, name, texture, ...)
Declares a new block in the game.
- **id**: Unique identifier within your project.
- **name**: Display name in-game.
- **texture**: Reference to a texture (e.g., `tex("block/dirt")`).
- **light**: Light level (0-15).

**Example:**
```python
@block("ruby_block", name="Ruby Block", texture=tex("ruby_block"), light=5)
class RubyBlock:
    pass
```

### @item(id, name, texture, ...)
Declares a new item.
- **max_stack**: Maximum stack size (1-64).
- **glow**: Whether the item has an enchantment glint.

**Example:**
```python
@item("ruby", name="Ruby", texture=tex("item/ruby"))
class Ruby:
    pass
```

## Event System

### @on(event_name)
Hooks into game events.
- **block_break**: Fired when a block is broken.
- **block_interact**: Fired when a block is right-clicked.
- **player_join**: Fired when a player joins the world.

**Example:**
```python
@on("block_break")
def on_break(ctx):
    ctx.player.send_message(f"You broke {ctx.block.name}!")
```

## Tips & Tricks
- Use `Ctrl+S` to save your files frequently.
- Use `Validate` (Ctrl+Enter) to check for Python errors before running.
- Assets should be placed in the `assets/` folder of your project.

## Limits
- Currently, custom Java code is not supported.
- You are limited to 256 block slots and 256 item slots per project.
- High-frequency events may impact performance.

## Possibilities
- Create complex crafting chains using `@recipe_shaped`.
- Custom loot tables with `@loot_table`.
- Interactive windows using the `ui` module (Experimental).
