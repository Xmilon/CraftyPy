#: title: Personal reminder command
#: category: Timers
#: doc: api.md#Scheduler
#: module: personal_reminder
#: about: Schedules a bounded reminder and resolves the player again before sending.
#: side: server
#: keywords: 
from craftpy import *

@command('{{name}}', level=0)
def reminder_{{name}}(ctx, text: str):
    name, message = ctx.player.name, text[:100]
    def deliver():
        p = Player.named(name)
        if p:
            p.say('Reminder:', message)
    scheduler.after(60, deliver)
