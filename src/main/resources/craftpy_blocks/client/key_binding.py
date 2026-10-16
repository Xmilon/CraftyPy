#: title: Key binding (client)
#: category: Client side
#: doc: api.md#Keys
#: module: hello_key
#: side: client
#: about: Ctrl+H shows a toast and writes in chat, on the player's own computer.
from craftpy import *


@keys.bind("Ctrl+H")
def {{name}}(ctx):
    client.toast("Hello", "You pressed Ctrl+H")
    client.chat("Hello from CraftPy")
