#: title: Greet joining players
#: category: Events
#: doc: api.md#6. Events
#: module: greetings
#: about: A title and a chat line for every player who joins.
from craftpy import *


@on.player_join
def greet_{{name}}(event):
    event.player.title("Welcome!", subtitle="Have fun on this server")
    say(event.player.name, "joined the game")
