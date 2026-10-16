#: title: Chat command word
#: category: Events
#: doc: api.md#Events
#: module: chat_words
#: about: Answer when a player writes a word in chat (here !time).
from craftpy import *


@on.chat
def chat_{{name}}(event):
    if event.message.strip().lower() == "!time":
        event.player.say("It is", "day" if world.is_day() else "night")
