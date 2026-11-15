# Nitrate Companion

A Jellyfin server plugin that adds what the Nitrate apps need and Jellyfin has no API for. The
apps work without it; features that need it say so and stay hidden.

Features:

- **Chat during watch-together** (SyncPlay): text and quick reactions, stamped with the time in the
  movie. Anyone watching in the group can chat, from any of their devices, so a phone can join the
  chat of the TV it's next to without playing the video itself.
- **Shared screenplays**: the owner invites people on the same server, and everyone writes in
  the script at once, seeing each other's cursors. Only the owner and invited people can open it.

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

Shared scripts:

| Call | What |
| --- | --- |
| `GET /Nitrate/Users?name=` | People a script can be shared with (`{Id, Name}`), or the one named exactly. The list can be turned off in the plugin's settings (`ListUsersForSharing`). |
| `GET /Nitrate/Scripts` | Scripts the caller owns or was invited to. |
| `POST /Nitrate/Scripts` | `{Text, Members}`: shares a new script, owned by the caller. |
| `GET /Nitrate/Scripts/{id}` | `{Info, Text, Version}`. 404 when it isn't shared with the caller. |
| `PUT /Nitrate/Scripts/{id}/Members` | `{Members}` (the owner only, else 403). |
| `POST /Nitrate/Scripts/{id}/Leave` · `DELETE /Nitrate/Scripts/{id}` | A member stops sharing; the owner deletes it for everyone (each app keeps its own copy). |
| `POST /Nitrate/Scripts/{id}/Edits` | `{EditId, BaseVersion, Ops, Cursor}` → `{Accepted, Version}`. Taken only when `BaseVersion` is the latest: otherwise the app catches up, rebases its change and sends it again (same `EditId` is never applied twice). `Ops` walks the whole text: a positive number keeps, a negative deletes, a string inserts (UTF-16 lengths). |
| `GET /Nitrate/Scripts/{id}/Edits?after=V&seen=S&waitMs=M` | Changes after `V` and others' cursors; waits for something new (long polling). 410 when those changes are no longer kept (the last 2000): load the script again. |
| `POST /Nitrate/Scripts/{id}/Presence` | `{Cursor, Version}`: where the caller's cursor is. |
| `GET /Nitrate/Scripts/{id}/Log?from=V&limit=N` | The full history of changes from version `V` (each with who, when, and for text changes the scene it was in). |
| `GET /Nitrate/Scripts/{id}/At?version=V` | `{Version, Text, Records}`: the script and its story data as they were, rebuilt from the history. |

A change can also (or instead) carry `Records`: story data changes (`{Id, Type, Set: {field: value or null}, Delete}`)
for the apps' scene details, lists, entries, links and comments. These never wait on the version (each field takes
the latest value, stamped with who and when), and a text change is refused only when another *text* change came
after its base version. Every change is appended to a full history file (`<id>.log`) next to the script.

Shared scripts are kept in the server's data folder (`data/nitrate-companion/scripts`, one JSON
file each), so they survive restarts and plugin updates.

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
