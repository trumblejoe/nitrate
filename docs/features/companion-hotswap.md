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
  Jellyfin services it needs, so no logic type ever enters the server's container or caches), the
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
`brought.v1`, `sso.v1`, `querytimes.v1`, `because.v1`, `sittings.v1`, `together.v1`). Only plain
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

## Status

`/Nitrate/Updates` keeps its fields and adds `Shell`, `Logic`, `LogicSource`, `LogicLoadedAt`,
`LastSwapAt`, `LastSwap`, `WaitingKind` (`restart` for a plugin update waiting for a restart, else
null) and `LogicNeedsPlugin` (a logic version waiting for a newer plugin). The dashboard page shows
the shell and logic versions and the last swap.

## Failure and rollback

- No usable logic at all: `/Nitrate/*` answers 503 (except `/Nitrate/Updates`), tasks and segments
  do nothing, and the updater keeps looking.
- A new package that fails to load or start: the old logic carries on (or is started again), the
  version is remembered as bad, and the status says what happened.
- A restart loads the last good package straight away.
