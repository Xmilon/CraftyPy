#: title: Form: text field and submit
#: category: UI elements
#: doc: api.md#TextInput
#: module: name_form
#: about: A text field with a hint and a button that reads what was typed (ctx.values).
# Inputs get an id; callbacks read every input of the window from ctx.values.
from craftpy import *


@window("{{name}}", title="Your name", size=(280, 150))
class NameForm(Window):
    def build(self, ctx):
        return VBox(
            Label("What should we call you?"),
            TextInput("", id="nickname", hint="Nickname"),
            HBox(
                Button("Save", on_click=self.save),
                Button("Cancel", on_click=ctx.close, style="text"),
            ),
            spacing=8, padding=4,
        )

    def save(self, ctx):
        name = (ctx.values.get("nickname") or "").strip() or ctx.player.name
        storage.set("nickname:" + ctx.player.name, name)
        ctx.player.say("Saved your nickname:", name)
        ctx.close()


@command("{{name}}", level=0)
def open_{{name}}(ctx):
    """/{{id}}:{{name}} - open the form."""
    ui.open(ctx.player, "{{name}}")
