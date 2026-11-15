# Nitrate Companion

A Jellyfin server plugin that adds what the Nitrate apps need and Jellyfin has no API for. The
apps work without it; features that need it say so and stay hidden.

Features:

- **Chat during watch-together** (SyncPlay): text and quick reactions, stamped with the time in the
  movie. Anyone watching in the group can chat, from any of their devices, so a phone can join the
  chat of the TV it's next to without playing the video itself.
- **Shared screenplays**: the owner invites people on the same server, and everyone writes in
  the script at once, seeing each other's cursors. Only the owner and invited people can open it.
- **Single sign-on**: sign in to the Nitrate apps with your OpenID Connect identity provider
  (Authentik, Authelia, Keycloak, Pocket ID...). Only existing Jellyfin users can sign in. Phones
  open the sign-in page in the browser; TVs show a QR code to finish on a phone.
- **Watch partners**: watch groups per show (a pair is a group of two; one show can have several
  groups) whose members all see each other's progress. Any member invites; the invited person
  accepts after seeing who'd learn their name. Episodes watched together count for the group. Accounts can be kept private (by an admin in the
  plugin's settings, or by their owner): never listed or findable by name, and a request to a
  private name looks exactly like one to nobody until its owner accepts.
- **Channels**: TV channels made from your library (shows, movies, collections, playlists,
  libraries; time slots like Saturday-morning cartoons), granted to everyone or chosen people.
  Everyone sees the same guide, but nothing is streamed unless someone watches: tuning in plays
  what's on from its beginning, then the channel in order.

## Install

In Jellyfin: **Dashboard → Plugins → Repositories → +**, and add

    https://github.com/trumblejoe/nitrate/releases/download/companion-repo/manifest.json

Then install **Nitrate Companion** from the catalogue and restart the server. Each Nitrate release
also carries the plugin as `nitrate-companion-<version>.zip` for installing by hand (unzip into the
server's `plugins/Nitrate Companion_<version>` folder).

Jellyfin 12.4 or later (built against Jellyfin's 12.4 libraries, .NET 10).

## Single sign-on setup

In the identity provider, make an OpenID Connect client (confidential with a secret, or public:
PKCE is always used) with the redirect address `https://<your server>/Nitrate/Sso/Callback`. Then
in Jellyfin: **Dashboard → Plugins → Nitrate Companion**, switch on single sign-on and fill in the
issuer address, client id and secret. The settings page shows the exact redirect address; set a
public server address there if Jellyfin sits behind a reverse proxy.

People are matched to Jellyfin users by the user name claim (`preferred_username` unless changed),
then by a verified email that is a Jellyfin user name, then by links an admin writes
(`subject, email or user name = Jellyfin user`, one per line). A required group can keep everyone
else out. The id token's signature (the provider's published keys, or the client secret for HMAC),
issuer, audience, expiry and nonce are checked.

## API

The sign-in calls are open (used before signing in):

| Call | What |
| --- | --- |
| `GET /Nitrate/Sso` | `{Enabled, Name}`: whether sign-in with the provider is offered, and its name. |
| `POST /Nitrate/Sso/Start` | `{DeviceId, DeviceName, App, AppVersion}` → `{Code, Secret, Url, ExpiresInMs}`. Show the code and URL (or open it); keep the secret. Lapses after 10 minutes. |
| `GET /Nitrate/Sso/Login/{code}` | The browser page: shows the device and code to confirm, then goes to the provider. |
| `GET /Nitrate/Sso/Callback` | Where the provider returns. |
| `POST /Nitrate/Sso/Poll` | `{Code, Secret}` → `{State: Waiting|Done|Failed|Expired, Message, AccessToken, UserId, UserName}`. Done gives a Jellyfin token for the device, once. |

All other calls need a signed-in user (the usual Jellyfin `Authorization` header). JSON is PascalCase,
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

Channels (everyone sees the channels granted to them; admins see and change all):

| Call | What |
| --- | --- |
| `GET /Nitrate/Channels` | The caller's channels: `{Id, Name, Number, Sources, Mix, AlignMinutes, Blocks, Everyone, Users}` (`Users` only for admins). |
| `GET /Nitrate/Channels/Guide?fromMs=&toMs=` | Each channel with its programs then: `{Channel, Programs: [{ItemId, Start, End, Title, Subtitle, Kind, Block, Available}]}`. `Available` is false for titles the caller may not watch (parental controls, library access). |
| `GET /Nitrate/Channels/{id}/Tune?count=N` | Tuning in: the program on now and the ones after it, in order, skipping what the caller can't watch. Apps play them as a queue, each from its start. |
| `POST /Nitrate/Channels` · `POST /Nitrate/Channels/{id}?startOver=` · `DELETE /Nitrate/Channels/{id}` | Admins make, change and delete channels. A change keeps what's on now and each show's place (`startOver=true`: every show from its first episode). |

A source is `{Kind: Series|Item|Collection|Playlist|Library, Id, Name, Shuffle}`; `Mix` is `Rotate` (one from each in
turn), `Sequential` or `Shuffle`; `AlignMinutes` starts programs on 5/10/15/30/60-minute marks (0: back to back); a
block is `{Name, Days (0 = Sunday), StartMinute, EndMinute, Sources, Mix}` in the server's time zone. The schedule is
made a few days ahead when someone opens the guide or tunes in, and kept in `data/nitrate-companion/channels` (one
JSON file per channel), so everyone sees the same guide. Nothing is ever streamed by the plugin: it reads titles and
running times from Jellyfin's database only.

Watch partners and privacy:

| Call | What |
| --- | --- |
| `GET /Nitrate/Privacy` · `PUT /Nitrate/Privacy` | `{Private, ByAdmin}`; `{Private}` makes the caller private or not (an admin's choice still holds). |
| `GET /Nitrate/Partners/People` | People the caller can ask: enabled, not private, not themselves. |
| `GET /Nitrate/Partners` | `{Groups, Incoming, Outgoing}`. Incoming invites list `WillSee` (who learns the caller's name by accepting). Outgoing invites always show as waiting until accepted or lapsed (14 days), even when declined or sent to no one. |
| `POST /Nitrate/Partners/Requests` | `{ToUserId or ToName, SeriesId, SeriesName, GroupId?}`: into one of the caller's groups, or a new one for the show. Always answers with the waiting invite. |
| `POST /Nitrate/Partners/Requests/{id}/Accept` · `/Decline` · `DELETE /Nitrate/Partners/Requests/{id}` | The invited person answers; the inviter withdraws. |
| `DELETE /Nitrate/Partners/Groups/{id}` | Leaves a group (a group left with one person ends). |
| `GET /Nitrate/Partners/Progress?seriesId=` | Each other member of the caller's groups on that show: `{GroupId, UserId, UserName, Watched, Last, NextId}`. |
| `POST /Nitrate/Partners/Together` | `{ItemId, GroupId}`: marks the episode watched for the rest of that group (only a member, only an episode of the group's show). |

The script-sharing list (`GET /Nitrate/Users`) and its exact-name lookup leave private accounts out too. Partners are kept in
`data/nitrate-companion/partners.json`.

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
