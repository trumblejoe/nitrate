# Nitrate Companion: shell and hot-swapped logic

How the Companion takes rule and feature updates without restarting Jellyfin. Read
`docs/features/companion.md` for what the plugin does; this note is only about how it is split and
updated.

Jellyfin loads a plugin's assemblies once at start and can't unload them, and it finds
controllers, hosted services, scheduled tasks, segment providers and startup filters only then. So
the plugin is two assemblies:

- **Shell**: `companion/Nitrate.Companion` → `Nitrate.Companion.dll`, the only assembly Jellyfin
  loads (meta.json `assemblies`). Same plugin id (a storage key: never change it), name, `Plugin`
  class, `PluginConfiguration` (with `SmartRule`/`CatalogSettings`, data only), dashboard page and
  embedded test clips. Small and rarely changed; a shell change is an ordinary plugin update that
  needs a restart.
- **Logic**: `companion/Nitrate.Companion.Logic` → `Nitrate.Companion.Logic.dll` plus its private
  libraries (SharpCompress), shipped as a *logic package* and loaded by the shell into a collectible
  `AssemblyLoadContext`. Everything else: the API (the controllers, unchanged, run by the logic's
  own router), hubs, storage, scanners, rules. Namespaces stayed `Nitrate.Companion.*`.

## The contract (`Nitrate.Companion.Hosting`, in the shell)

- `ShellContract.Version` (now 1) and `Oldest`: the contract numbers this shell accepts. A logic
  package says which it was built for (`logic.json` `contract`, and `[assembly: CompanionLogic(typeof(Factory), contract)]`).
  Bump `Version` whenever anything the logic uses from the shell changes (the interfaces below, the
  configuration class, `Plugin`), and raise `Oldest` when an old logic would break on the new shell.
- `ICompanionLogicFactory.Create(LogicContext)` → `ICompanionLogic`. `LogicContext` carries the
  server's service provider (the logic builds its own `ServiceCollection` and forwards the
  Jellyfin services it needs, so no logic type ever enters the server's container or caches;
  `LogicServices.ForwardServerServices` registers each as the server's instance, never through a
  factory: a container disposes what its factories made, so disposing the old logic on a swap
  once disposed Jellyfin's SessionManager, SyncPlay and the rest (sign-in, live connections and
  every session request failed until a restart; `ForwardedServicesTests`), the
  logger factory, the package version, the handed-over state and a `Stopping` token.
- `ICompanionLogic`: `StartAsync`/`StopAsync`/`DisposeAsync`; `ExportState()`; `Match(method, path)`
  → `RouteMatch` (found, its auth: anonymous or the `[Authorize]` policies; not found; wrong method)
  and `InvokeAsync(match, ControllerContext)` → `IActionResult`; `RequestStarting`/`RequestFinished`
  (query timing); `Task(key)` for the scheduled tasks; `Segments` for the segment provider.

## What stays in the shell (registered with Jellyfin at start, forwarding to the current logic)

- `Hosting/DispatchController`: `[Route("Nitrate")]` + `{**path}`, every method, `[ApiController]`,
  `[Produces(application/json)]`, `[AllowAnonymous]`. It asks the logic which route matches and
  enforces that route's auth itself, exactly as the authorization middleware would (policy
  evaluator, then Jellyfin's result handler: 401/403), then runs the logic's action and returns its
  `IActionResult` to MVC, so status codes, ProblemDetails, content negotiation, files, ranges and
  long polls behave as before. `Nitrate/Updates` stays a shell controller (a literal route wins
  over the catch-all), so updates work even without a logic.
- `Hosting/LogicTasks`: one `IScheduledTask` per nightly task with the **same full type name** as
  before (`Nitrate.Companion.Credits.CreditScanner`, …): Jellyfin keys a task's triggers and
  history by the type's full name, so schedules and history stay. The logic's own task classes are
  renamed (`CreditScan`, …). A run cut off by a swap carries on in the new logic.
- `Hosting/LogicSegments`: the `IMediaSegmentProvider`, always named "Nitrate Companion".
- `Hosting/LogicTiming`: the startup filter and middleware; it times every request and tells the
  current logic (which keeps only tagged ones, as before). Never holds the logic across `next`.
- `Hosting/LogicHost`: the hosted service that loads, starts, swaps and stops the logic.
- `Updates/`: `SelfUpdater` (plugin updates as before, plus logic updates), `LogicUpdater`,
  `UpdatesController`, `UpdatePolicy`.

## Loading

`LogicLoadContext` (collectible) loads the package's own assemblies from memory (no files locked
or left behind). Everything else resolves as the shell sees it: the shell assembly itself, and
Jellyfin's, Microsoft.* (ASP.NET, DI, logging), `Microsoft.Data.Sqlite` and SQLitePCLRaw from the
server, so types match. Names that must come from the server are never taken from a package.

At start the shell picks the newest usable of: the last good downloaded package
(`<data>/nitrate-companion/logic/<version>.zip` + `.sig`, checked again), and the built-in one in
the plugin's folder (`logic/nitrate-companion-logic.zip`, put there by the release; trusted like the
shell DLL beside it). If that one fails to start, the other is tried.

## Swap sequence (`LogicHost.SwapAsync`)

1. Load the new package into a new context and find its factory; any failure leaves the old
   logic running and marks that version bad (not tried again until a newer one).
2. Hold new requests (they wait, they aren't refused), cancel the old logic's `Stopping` token: its
   long polls (chat, script edits) answer at once as if their wait ran out, and clients poll again;
   running scheduled tasks are cancelled and continue in the new logic.
3. Wait for the old logic's in-flight requests and calls to finish (at most 20 s), then
   `StopAsync` (hosted loops end, event handlers are removed), `ExportState()`, `DisposeAsync()`
   (databases closed, pending script writes flushed).
4. Create the new logic with that state and start it. If it throws, it is disposed and unloaded,
   and a fresh instance of the old logic (its context is still loaded) is created from the same
   state and started: rollback.
5. Let the held requests through, unload the old context and watch it go (a weak reference
   checked after collections; logged if it lingers). The good package is kept on disk (and the one
   before it); older ones are deleted.

## State handover

`ExportState()` returns `key → JSON bytes`, each key versioned (`chat.v1`, `invites.v1`,
`brought.v1`, `sso.v1`, `querytimes.v1`, `because.v1`, `sittings.v1`, `together.v1`, `groupsessions.v1`). Only plain
JSON crosses, never a type. A new logic reads the keys it knows and ignores the rest; a format
change takes a new key (`chat.v2`), and the new logic may read the old key too. Everything else is
in `companion.db` and the other files already, or rebuilt (formats, presence, rate limits).

## Packages, signing and delivery

- Logic package `nitrate-companion-logic-<v>.zip`: `logic.json` (`version`, `contract`, `entry`,
  `assemblies`) and the DLLs. Made by `tools/companion-manifest.py logic`.
- Signature: ECDSA P-256 over the zip's SHA-256 (`openssl dgst -sha256 -sign`, DER), checked by
  the shell with the public key compiled into it (`AssemblyMetadata("NitrateLogicPublicKey")`, from
  the build property `LogicPublicKey`). A downloaded package is loaded only with a good signature,
  the right SHA-256, the version it was listed as and higher than the running one, and a contract the
  shell supports.
- The release signs with the private key in the GitHub Actions secret
  `COMPANION_LOGIC_SIGNING_KEY` (PEM) and derives the public key from it for the shell build.
  Without the secret nothing is signed or listed: hot updates are off and the plugin runs its
  built-in logic.
- The `companion-repo` release keeps `logic.json` (newest first: version, contract, url, sha256,
  signature), beside the plugin repository's `manifest.json`. The plugin manifest gets a new
  version only when the shell changed; the logic list whenever the logic changed.
- The shell's updater (same cadence and controls as before: 10 minutes after start, then every 6
  hours, not with auto-update off, and at once with "Check for updates now") installs plugin
  updates through Jellyfin as before and swaps logic updates in at once. A logic that needs a newer
  contract is left for the plugin update (restart), and `/Nitrate/Updates` says so.

## One-time setup (repository owner)

Hot updates start with the first release after the signing key exists:

    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out companion-logic-key.pem
    gh secret set COMPANION_LOGIC_SIGNING_KEY < companion-logic-key.pem

Keep a copy of the PEM somewhere safe and offline, never in the repository. Servers trust the key
compiled into the plugin they run: with a new key (lost or rotated), the next release is a plugin
update (restart), and logic signed with the new key reaches a server only once it runs that
plugin. Until the secret is set, releases warn, nothing is signed or listed, and servers run the
logic that came with their plugin.

## Status

`/Nitrate/Updates` keeps its fields and adds `Shell`, `Logic`, `LogicSource`, `LogicLoadedAt`,
`LastSwapAt`, `LastSwap`, `WaitingKind` (`restart` for a plugin update waiting for a restart, else
null) and `LogicNeedsPlugin` (a logic version waiting for a newer plugin). The dashboard page shows
the shell and logic versions and the last swap.

## Keeping old logic unloadable

An old logic unloads only when nothing outside its context still points into it. What the logic
must keep to:

- Its own container and its own JSON options (`ApiRouter` copies the server's MVC JSON settings
  with a fresh resolver; logic types are turned into a `JsonElement` before MVC sees them,
  `ApiRouter.Shield`, which also writes an empty reply declared as a logic type (a "none tonight"
  `PartyView?`) as plain null: MVC writing `null` as the declared type kept that type in the server's
  JSON options for good, and with it the old logic, which then stayed in memory after every swap).
  Never hand a logic type to the server's serializers, caches or containers.
- Borrow the server's services as its instances, never through factories (a container disposes
  what its factories made: disposing the old logic once disposed Jellyfin's SessionManager;
  `ForwardedServicesTests`).
- Ask the server for a task by its key (`SmartCollectionWatcher.RunNow`), never by a logic type:
  the server only knows the shell's wrapper types.
- `StopAsync` removes every event handler on the server's managers and ends every loop and timer;
  `DisposeAsync` closes the databases.
- No static events, `AppDomain` handlers or SQLite functions pointing at logic code.
- The load context never keeps its own assemblies (the runtime does; a second reference ties the
  context to them for good), and System.Text.Json's process-wide member accessor cache drops stale
  entries only when it makes new ones, so the unload watcher (`JsonCaches.Nudge`) makes some.

`LogicHostTests` and `RealLogicTests` check that the old context really goes after requests have
been through it.

## Smaller differences from before the split

- The API no longer appears in Jellyfin's generated OpenAPI document (the catch-all is hidden from it).
- A wrong method on a path the shell owns (`GET /Nitrate/Updates/Check`) is a 404 rather than 405.
- Callers that come before the logic has started wait at most a minute (`LogicHost.StartWait`).

## Failure and rollback

- No usable logic at all: `/Nitrate/*` answers 503 (except `/Nitrate/Updates`), tasks and segments
  do nothing, and the updater keeps looking.
- A new package that fails to load or start: the old logic carries on (or is started again), the
  version is remembered as bad, and the status says what happened.
- A restart loads the last good package straight away.
