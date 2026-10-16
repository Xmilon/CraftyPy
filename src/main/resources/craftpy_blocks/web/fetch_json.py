#: title: Read a web API (http)
#: category: Web
#: doc: api.md#Web
#: module: weather_report
#: about: /{{id}}:{{name}} asks a weather web API and tells you the temperature. Declare the site first.
# The site must be declared in project.json: "permissions": {"net": ["api.open-meteo.com"]}
# and allowed in config/craftpy-access.toml (it is in the safe list).
from craftpy import *


@command("{{name}}")
def {{name}}(ctx):
    """/{{id}}:{{name}} - the temperature in Rome right now."""
    name = ctx.player.name

    def done(response):
        if response.ok:
            say(name, "- Rome:", response.json()["current_weather"]["temperature"], "C")
        else:
            say(name, "- the weather service did not answer:", response.status)

    http.get("https://api.open-meteo.com/v1/forecast?latitude=41.9&longitude=12.5&current_weather=true", done)
