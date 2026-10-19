from craftpy import *

def tasks(p):
    return storage.get('tasks:' + p.uuid, [])

@command('open')
def open_journal(ctx):
    if ctx.player:
        ui.open(ctx.player, 'journal')

@ui.data('journal')
def data(ctx):
    entries = tasks(ctx.player)
    return dict(tasks=[('Done: ' if t['done'] else 'To do: ') + t['text'] for t in entries],
                summary=f'{sum(t["done"] for t in entries)}/{len(entries)} finished', reminders=storage.get('reminders:' + ctx.player.uuid, False))

@action('add_task')
def add(ctx):
    entries = tasks(ctx.player)
    text = str(ctx.values.get('task') or '').strip()[:100]
    if text and len(entries) < 32:
        entries.append(dict(text=text, done=False))
        storage.set('tasks:' + ctx.player.uuid, entries)

@action('finish_task')
def finish(ctx):
    entries = tasks(ctx.player)
    index = ctx.values.get('selected', -1)
    if isinstance(index, int) and 0 <= index < len(entries):
        entries[index]['done'] = not entries[index]['done']
        storage.set('tasks:' + ctx.player.uuid, entries)

@command('reminders')
def reminders(ctx, enabled: bool):
    if ctx.player:
        storage.set('reminders:' + ctx.player.uuid, enabled)

@every(seconds=300)
def remind():
    for p in players.all():
        left = sum(not t['done'] for t in tasks(p))
        if left and storage.get('reminders:' + p.uuid, False):
            p.say(f'Journal reminder: {left} unfinished tasks.')

@keys.bind('ctrl+shift+j', id='open_journal', name='Open Task Journal', category='Task Journal')
def shortcut(ctx):
    open_journal(ctx)

@action('journal_reminders')
def save_reminders(ctx):
    reminders(ctx, ctx.values.get('reminders') is True)
