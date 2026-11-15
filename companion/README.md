# Nitrate Companion

A Jellyfin server plugin that adds what the Nitrate apps need and Jellyfin has no API for. The
apps work without it; features that need it say so and stay hidden.

Features:

- **Chat during watch-together** (SyncPlay): text and quick reactions, stamped with the time in the
  movie. Anyone watching in the group can chat, from any of their devices, so a phone can join the
  chat of the TV it's next to without playing the video itself.

## Install

In Jellyfin: **Dashboard → Plugins → Repositories → +**, and add

    https://github.com/trumblejoe/nitrate/releases/download/companion-repo/manifest.json

Then install **Nitrate Companion** from the catalogue and restart the server. Each Nitrate release
also carries the plugin as `nitrate-companion-<version>.zip` for installing by hand (unzip into the
server's `plugins/Nitrate Companion_<version>` folder).

Jellyfin 12.4 or later (built against Jellyfin's 12.4 libraries, .NET 10).

## API

All calls need a signed-in user (the usual Jellyfin `Authorization` header). JSON is PascalCase,
like Jellyfin's own.

| Call | What |
| --- | --- |
| `GET /Nitrate/Companion` | `{Version, Features}`; apps use it to tell the plugin is there. |
| `GET /Nitrate/Chat/{groupId}/Messages?after=N&waitMs=M` | Messages after `N` (-1: the recent ones), waiting up to `M` ms (at most 30 s) for one: long polling. `{Messages, Latest}`. |
| `POST /Nitrate/Chat/{groupId}/Messages` | `{Text, Kind: Text|Reaction, PositionMs}`; 400 for an empty, too long (500) or not-one-emoji reaction, 429 when sending too fast. |

Only people watching in the SyncPlay group (on any of their devices) may read or write its chat
(otherwise 403). Chat lives in the server's memory: a day after its last message, or when the
server restarts, it's gone.

## Develop

    dotnet test companion/Nitrate.Companion.Tests
    dotnet publish companion/Nitrate.Companion -c Release -o out

The plugin id (`Plugin.PluginId`, also in `tools/companion-manifest.py`) is where Jellyfin keeps
its settings: never change it.

## Licence

GPL-3.0-or-later (see `LICENSE`), because it builds on Jellyfin's own GPL libraries. This covers
only this folder; the Nitrate apps talk to it over HTTP.
