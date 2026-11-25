using System.Security.Claims;
using System.Text;
using System.Text.Json;
using Jellyfin.Data;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Providers;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Api;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Logic;
using Nitrate.Companion.Metadata;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Metadata editors: who may change what, through the server's services, limited and logged.</summary>
public sealed class MetadataEditorTests : IDisposable
{
    private static readonly Guid Films = Guid.NewGuid();
    private static readonly Guid Shows = Guid.NewGuid();

    private readonly CompanionDb _db = new(":memory:");
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-25T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly MetadataEditorStore _store;
    private readonly MetadataLimiter _limits;
    private readonly FakeServer _server = new();
    private readonly Dictionary<Guid, User> _users = new();
    private readonly User _admin;
    private readonly User _editor;
    private readonly User _viewer;
    private readonly Movie _heat;
    private readonly Movie _hidden;
    private readonly Movie _elsewhere;

    public MetadataEditorTests()
    {
        _store = new MetadataEditorStore(_db, _time);
        _limits = new MetadataLimiter(_time);
        _admin = Person("admin", admin: true);
        _editor = Person("ed");
        _viewer = Person("vi");
        _heat = _server.Add("Heat", Films);
        _hidden = _server.Add("Hidden", Films);
        _server.Hidden.Add((_editor.Id, _hidden.Id));
        _elsewhere = _server.Add("Show", Shows);
        _store.Put(new EditorScope(_editor.Id, false, new[] { Films }));
    }

    public void Dispose() => _db.Dispose();

    /// <summary>The server's side, in memory: items, their libraries and who can't see them; records what was saved.</summary>
    public sealed class FakeServer : IMetadataServer
    {
        public Dictionary<Guid, BaseItem> Items { get; } = new();

        public Dictionary<Guid, Guid> Library { get; } = new();

        public HashSet<(Guid User, Guid Item)> Hidden { get; } = new();

        public Dictionary<Guid, IReadOnlyList<PersonInfo>> Cast { get; } = new();

        public List<string> Calls { get; } = new();

        public HashSet<Guid> Running { get; } = new();

        public Movie Add(string name, Guid library)
        {
            var movie = new Movie { Id = Guid.NewGuid(), Name = name, Path = "/media/" + name + ".mkv", DateCreated = new DateTime(2020, 1, 1, 0, 0, 0, DateTimeKind.Utc) };
            Items[movie.Id] = movie;
            Library[movie.Id] = library;
            return movie;
        }

        public BaseItem? Find(Guid id, User? user) =>
            Items.TryGetValue(id, out var item) && (user is null || !Hidden.Contains((user.Id, id))) ? item : null;

        public IReadOnlyList<Guid> LibrariesOf(BaseItem item) => new[] { Library[item.Id] };

        public IReadOnlyList<PersonInfo> People(BaseItem item) => Cast.TryGetValue(item.Id, out var p) ? p : [];

        public Task SaveDetailsAsync(BaseItem item, IReadOnlyList<PersonInfo>? people, bool lockChanged)
        {
            if (people is not null)
            {
                Cast[item.Id] = people;
            }

            Calls.Add("save " + item.Name);
            return Task.CompletedTask;
        }

        public IEnumerable<ExternalIdInfo> ExternalIds(BaseItem item) => new[] { new ExternalIdInfo("TheMovieDb", "Tmdb", ExternalIdMediaType.Movie) };

        public Task ApplyAsync(BaseItem item, RemoteSearchResult result, bool replaceAllImages)
        {
            Calls.Add($"apply {result.Name} {replaceAllImages}");
            return Task.CompletedTask;
        }

        public void QueueRefresh(BaseItem item, RefreshRequest request)
        {
            Running.Add(item.Id);
            Calls.Add($"refresh {item.Name} {request.MetadataRefreshMode}");
        }

        public bool Refreshing(Guid itemId) => Running.Contains(itemId);

        public Task SaveImageAsync(BaseItem item, Stream data, string mimeType, ImageType type)
        {
            Calls.Add($"image {type} {mimeType} {data.Length}");
            return Task.CompletedTask;
        }

        public Task DownloadImageAsync(BaseItem item, Uri url, ImageType type)
        {
            Calls.Add($"download {type} {url}");
            return Task.CompletedTask;
        }

        public Task DeleteImageAsync(BaseItem item, ImageType type, int index)
        {
            Calls.Add($"delete {type} {index}");
            return Task.CompletedTask;
        }

        public Task MoveImageAsync(BaseItem item, ImageType type, int index, int newIndex)
        {
            Calls.Add($"move {type} {index} {newIndex}");
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task AnEditorSavesInTheirLibrariesAndTheOldValuesAreLogged()
    {
        _server.Cast[_heat.Id] = new[] { new PersonInfo { Name = "Al Pacino", Role = "Hanna", Type = PersonKind.Actor } };
        var result = await As(_editor).SaveDetails(_heat.Id.ToString("N"), Json("""
            {"Name":"Heat (1995)","Overview":"A heist.","Genres":["Crime","Drama"],"ProductionYear":1995,
             "People":[{"Name":"Robert De Niro","Role":"McCauley","Type":"Actor"}],"Studios":[{"Name":"Forward Pass","Id":"x"}],
             "ProviderIds":{"Tmdb":"949"},"LockedFields":["Name"],"LockData":true}
            """));
        Assert.IsType<NoContentResult>(result);
        Assert.Equal("Heat (1995)", _heat.Name);
        Assert.Equal("A heist.", _heat.Overview);
        Assert.Equal(new[] { "Crime", "Drama" }, _heat.Genres);
        Assert.Equal(1995, _heat.ProductionYear);
        Assert.Equal(new[] { "Forward Pass" }, _heat.Studios);
        Assert.Equal("949", _heat.ProviderIds["Tmdb"]);
        Assert.Equal(new[] { MetadataField.Name }, _heat.LockedFields);
        Assert.True(_heat.IsLocked);
        Assert.Equal("Robert De Niro", Assert.Single(_server.Cast[_heat.Id]).Name);
        Assert.Contains("save Heat (1995)", _server.Calls);

        var line = Assert.Single(_store.Edits(50, null));
        Assert.Equal("details", line.Kind);
        Assert.Equal(_editor.Id, line.UserId);
        Assert.Equal(_heat.Id, line.ItemId);
        Assert.Contains("Name", line.Summary, StringComparison.Ordinal);
        Assert.Contains("People", line.Summary, StringComparison.Ordinal);
        var old = JsonDocument.Parse(line.Old!).RootElement;
        Assert.Equal("Heat", old.GetProperty("Name").GetString());
        Assert.Equal(JsonValueKind.Null, old.GetProperty("Overview").ValueKind);
        Assert.Equal("Al Pacino", old.GetProperty("People")[0].GetProperty("Name").GetString());
        Assert.False(old.TryGetProperty("CommunityRating", out _));
    }

    [Fact]
    public async Task OutsideTheScopeOrNotAnEditorIsRefusedAndUnseenTitlesAreMissing()
    {
        var outside = await As(_editor).SaveDetails(_elsewhere.Id.ToString(), Json("""{"Name":"X"}"""));
        AssertMessage(outside, 403, MetadataEditorController.CantEdit);
        Assert.Equal("Show", _elsewhere.Name);

        // Jellyfin's own access comes first: a title the editor can't see is missing, even in scope.
        Assert.IsType<NotFoundResult>(await As(_editor).SaveDetails(_hidden.Id.ToString(), Json("""{"Name":"X"}""")));
        Assert.IsType<NotFoundResult>(As(_editor).Access(_hidden.Id.ToString()).Result);
        Assert.IsType<NotFoundResult>(await As(_editor).SaveDetails(Guid.NewGuid().ToString(), Json("""{"Name":"X"}""")));

        AssertMessage(await As(_viewer).SaveDetails(_heat.Id.ToString(), Json("""{"Name":"X"}""")), 403, MetadataEditorController.CantEdit);
        AssertMessage(await As(_viewer).UploadImage(_heat.Id.ToString(), ImageType.Primary, default), 403, MetadataEditorController.CantEdit);
        AssertMessage(As(_viewer).Refresh(_heat.Id.ToString()), 403, MetadataEditorController.CantEdit);
        Assert.Equal("Heat", _heat.Name);
        Assert.Empty(_store.Edits(50, null));

        // Admins pass anywhere, without being editors; an editor of every library too.
        Assert.IsType<NoContentResult>(await As(_admin).SaveDetails(_elsewhere.Id.ToString(), Json("""{"Name":"Show 2"}""")));
        Assert.Equal("Show 2", _elsewhere.Name);
        _store.Put(new EditorScope(_editor.Id, true, []));
        Assert.IsType<NoContentResult>(await As(_editor).SaveDetails(_elsewhere.Id.ToString(), Json("""{"Name":"Show 3"}""")));

        // A scope change counts at once.
        _store.Remove(_editor.Id);
        AssertMessage(await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"Name":"X"}""")), 403, MetadataEditorController.CantEdit);
    }

    [Fact]
    public async Task PathsReadOnlyAndUnknownFieldsAreIgnored()
    {
        var created = _heat.DateCreated;
        var id = _heat.Id;
        var result = await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""
            {"Name":"Heat","Path":"/etc/passwd","Id":"00000000000000000000000000000001","DateCreated":"2001-01-01T00:00:00Z",
             "MediaStreams":[{"Codec":"h264"}],"UserData":{"Played":true},"Trickplay":{},"Whatever":1,"overview":"lower case works"}
            """));
        Assert.IsType<NoContentResult>(result);
        Assert.Equal("/media/Heat.mkv", _heat.Path);
        Assert.Equal(id, _heat.Id);
        Assert.Equal(created, _heat.DateCreated);
        Assert.Equal("lower case works", _heat.Overview);
        var line = Assert.Single(_store.Edits(50, null));
        Assert.Equal("Changed Overview", line.Summary);

        // Nothing editable changed: nothing saved or logged.
        _server.Calls.Clear();
        Assert.IsType<NoContentResult>(await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"Path":"/x","Name":"Heat"}""")));
        Assert.Empty(_server.Calls);
        Assert.Single(_store.Edits(50, null));

        // A field of the wrong kind is refused, with nothing saved.
        var bad = await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"ProductionYear":{"a":1}}"""));
        Assert.Equal(400, Assert.IsType<ObjectResult>(bad).StatusCode);
    }

    [Fact]
    public void FieldsReadAndWriteAsJellyfinsItemJson()
    {
        var movie = new Movie { Name = "A", Tagline = "Old" };
        var changes = MetadataFields.Apply(movie, Json("""
            {"Taglines":["New","Second"],"PremiereDate":"1995-12-15T00:00:00.0000000Z","CommunityRating":7.9,"OfficialRating":"","AirsBeforeSeasonNumber":2,"IndexNumber":null}
            """), []);
        Assert.Equal("New", movie.Tagline);
        Assert.Equal(new DateTime(1995, 12, 15, 0, 0, 0, DateTimeKind.Utc), movie.PremiereDate);
        Assert.Equal(7.9f, movie.CommunityRating);
        Assert.Null(movie.OfficialRating);
        // Not an episode: the "airs before" numbers don't apply; an unchanged null isn't a change.
        Assert.Equal(new[] { "Taglines", "PremiereDate", "CommunityRating" }, changes.Changed);
        Assert.Equal("Old", changes.Old["Taglines"]![0]!.GetValue<string>());
    }

    [Fact]
    public async Task ImagesMustBeJpegPngOrWebpAndAtMostTwentyMegabytes()
    {
        var png = new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13 };
        AssertMessage(await Upload("image/gif", Convert.ToBase64String(png)), 415, null);
        AssertMessage(await Upload("image/webp", Convert.ToBase64String(png)), 415, null);
        AssertMessage(await Upload("image/png", "not base64!"), 400, null);
        AssertMessage(await Upload("image/png", Convert.ToBase64String(new byte[MetadataEditorController.MaxImageBytes + 3])), 413, null);
        Assert.IsType<NoContentResult>(await Upload("image/png; charset=utf-8", Convert.ToBase64String(png)));
        Assert.Contains($"image Primary image/png {png.Length}", _server.Calls);
        Assert.Equal("Primary uploaded", Assert.Single(_store.Edits(50, null)).Summary);
        Assert.Equal("image", Assert.Single(_store.Edits(50, null)).Kind);

        Assert.IsType<NoContentResult>(await As(_editor).DeleteImage(_heat.Id.ToString(), ImageType.Backdrop, 1));
        Assert.IsType<NoContentResult>(await As(_editor).MoveImage(_heat.Id.ToString(), ImageType.Backdrop, 2, 0));
        Assert.Contains("delete Backdrop 1", _server.Calls);
        Assert.Contains("move Backdrop 2 0", _server.Calls);
    }

    [Theory]
    [InlineData("https://image.tmdb.org/t/p/original/a.jpg", true)]
    [InlineData("http://example.com/a.png", true)]
    [InlineData("file:///etc/passwd", false)]
    [InlineData("/config/metadata/a.jpg", false)]
    [InlineData("http://localhost:8096/Items/x/Images/Primary", false)]
    [InlineData("http://127.0.0.1/a.jpg", false)]
    [InlineData("http://192.168.1.5/a.jpg", false)]
    [InlineData("http://[::1]/a.jpg", false)]
    [InlineData("ftp://example.com/a.jpg", false)]
    public void RemoteImagesComeOnlyFromTheWeb(string url, bool allowed) =>
        Assert.Equal(allowed, MetadataEditorController.WebAddress(url, out _));

    [Fact]
    public async Task RemoteImagesFromServerPathsAreRefused()
    {
        AssertMessage(await As(_editor).DownloadImage(_heat.Id.ToString(), ImageType.Primary, "/config/a.jpg"), 400, null);
        Assert.IsType<NoContentResult>(await As(_editor).DownloadImage(_heat.Id.ToString(), ImageType.Primary, "https://image.tmdb.org/a.jpg"));
        Assert.Single(_server.Calls, c => c.StartsWith("download", StringComparison.Ordinal));
    }

    [Fact]
    public async Task EditorsMakeThirtyChangesAMinuteAndAdminsAny()
    {
        for (var i = 0; i < MetadataLimiter.ChangesPerMinute; i++)
        {
            Assert.IsType<NoContentResult>(await As(_editor).SaveDetails(_heat.Id.ToString(), Json($$"""{"Overview":"{{i}}"}""")));
            _time.Advance(TimeSpan.FromSeconds(1));
        }

        AssertMessage(await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"Overview":"over"}""")), 429, MetadataEditorController.TooMany);
        AssertMessage(await As(_editor).DeleteImage(_heat.Id.ToString(), ImageType.Primary, null), 429, MetadataEditorController.TooMany);
        Assert.Equal("29", _heat.Overview);

        // A sliding window: the first change leaves it after a minute.
        _time.Advance(TimeSpan.FromSeconds(30));
        Assert.IsType<NoContentResult>(await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"Overview":"again"}""")));
        AssertMessage(await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"Overview":"over"}""")), 429, MetadataEditorController.TooMany);

        for (var i = 0; i < 40; i++)
        {
            Assert.IsType<NoContentResult>(await As(_admin).SaveDetails(_heat.Id.ToString(), Json($$"""{"Overview":"admin {{i}}"}""")));
        }
    }

    [Fact]
    public async Task EditorsRunFourRefreshesAtOnce()
    {
        var films = Enumerable.Range(0, 6).Select(i => _server.Add("Film " + i, Films)).ToList();
        for (var i = 0; i < MetadataLimiter.RefreshesAtOnce; i++)
        {
            Assert.IsType<NoContentResult>(As(_editor).Refresh(films[i].Id.ToString(), MetadataRefreshMode.FullRefresh));
        }

        AssertMessage(As(_editor).Refresh(films[4].Id.ToString()), 429, MetadataEditorController.TooMany);
        // A match being applied counts as a refresh too.
        AssertMessage(await As(_editor).Apply(films[4].Id.ToString(), new RemoteSearchResult { Name = "Film" }), 429, MetadataEditorController.TooMany);

        // Once the server has finished one, another can start.
        _server.Running.Remove(films[0].Id);
        _time.Advance(TimeSpan.FromSeconds(11));
        Assert.IsType<NoContentResult>(As(_editor).Refresh(films[4].Id.ToString()));
        AssertMessage(As(_editor).Refresh(films[5].Id.ToString()), 429, MetadataEditorController.TooMany);
        Assert.Equal(5, _store.Edits(50, null).Count(e => e.Kind == "refresh"));
        Assert.Contains("refresh Film 0 FullRefresh", _server.Calls);
        Assert.IsType<NoContentResult>(As(_admin).Refresh(films[5].Id.ToString()));
    }

    [Fact]
    public async Task MatchesAreAppliedAndLogged()
    {
        var result = await As(_editor).Apply(_heat.Id.ToString(), new RemoteSearchResult { Name = "Heat", ProviderIds = new() { ["Tmdb"] = "949" } }, replaceAllImages: false);
        Assert.IsType<NoContentResult>(result);
        Assert.Contains("apply Heat False", _server.Calls);
        var line = Assert.Single(_store.Edits(50, null));
        Assert.Equal("match", line.Kind);
        Assert.Equal("Matched Heat (Tmdb 949)", line.Summary);
        Assert.False(As(_admin).Edits().Value!.Single().CanUndo);

        // The refresh slot was handed back.
        for (var i = 0; i < MetadataLimiter.RefreshesAtOnce; i++)
        {
            Assert.IsType<NoContentResult>(await As(_editor).Apply(_heat.Id.ToString(), new RemoteSearchResult { Name = "Heat" }));
        }
    }

    [Fact]
    public async Task AdminsUndoDetailsFromTheLog()
    {
        _heat.Genres = ["Crime"];
        await As(_editor).SaveDetails(_heat.Id.ToString(), Json("""{"Name":"Hot","Genres":["Comedy"],"Overview":"Same"}"""));
        await As(_editor).Refresh(_heat.Id.ToString()).ToTask();
        var edits = As(_admin).Edits().Value!;
        Assert.Equal(new[] { "refresh", "details" }, edits.Select(e => e.Kind));
        Assert.Equal("ed", edits[0].UserName);
        Assert.False(edits[0].CanUndo);
        Assert.True(edits[1].CanUndo);

        // Not for editors.
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await As(_editor).Undo(edits[1].Id)).StatusCode);
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(As(_editor).Edits().Result).StatusCode);

        Assert.IsType<NoContentResult>(await As(_admin).Undo(edits[1].Id));
        Assert.Equal("Heat", _heat.Name);
        Assert.Equal(new[] { "Crime" }, _heat.Genres);
        Assert.Null(_heat.Overview);

        var after = As(_admin).Edits().Value!;
        Assert.Equal(3, after.Count);
        Assert.Equal("details", after[0].Kind);
        Assert.Equal("admin", after[0].UserName);
        Assert.StartsWith($"Undid change {edits[1].Id}", after[0].Summary, StringComparison.Ordinal);
        Assert.True(after[0].CanUndo);
        Assert.False(after.Single(e => e.Id == edits[1].Id).CanUndo);
        Assert.Equal(400, Assert.IsType<ObjectResult>(await As(_admin).Undo(edits[1].Id)).StatusCode);

        // Paging: older than a line.
        var older = As(_admin).Edits(limit: 1, before: after[0].Id).Value!;
        Assert.Equal(after[1].Id, Assert.Single(older).Id);
    }

    [Fact]
    public void LogLinesAreKeptAYear()
    {
        _store.Log(_editor.Id, _heat.Id, "Heat", "image", "Primary uploaded", null);
        _time.Advance(TimeSpan.FromDays(366));
        _store.Log(_editor.Id, _heat.Id, "Heat", "image", "Logo uploaded", null);
        Assert.Equal("Logo uploaded", Assert.Single(_store.Edits(50, null)).Summary);
    }

    [Fact]
    public void MeAndAccessSayWhatTheCallerMayDo()
    {
        var me = As(_editor).Me().Value!;
        Assert.True(me.IsEditor);
        Assert.False(me.AllLibraries);
        Assert.Equal(new[] { Films }, me.LibraryIds);
        Assert.Equal(new EditorMe(false, false, []), As(_viewer).Me().Value! with { LibraryIds = [] });
        var admin = As(_admin).Me().Value!;
        Assert.True(admin.IsEditor && admin.AllLibraries);
        Assert.Empty(admin.LibraryIds);

        Assert.True(As(_editor).Access(_heat.Id.ToString("N")).Value!.CanEdit);
        Assert.False(As(_editor).Access(_elsewhere.Id.ToString()).Value!.CanEdit);
        Assert.False(As(_viewer).Access(_heat.Id.ToString()).Value!.CanEdit);
        Assert.True(As(_admin).Access(_elsewhere.Id.ToString()).Value!.CanEdit);
        Assert.IsType<UnauthorizedResult>(new MetadataEditorController(Users(), _store, _limits, _server) { ControllerContext = Context(null) }.Me().Result);
    }

    [Fact]
    public async Task AdminsChooseEditorsAndTheirLibraries()
    {
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(As(_editor).SetEditor(_viewer.Id.ToString(), new EditorPost(true, true, null))).StatusCode);
        Assert.IsType<NoContentResult>(As(_admin).SetEditor(_viewer.Id.ToString("N"), new EditorPost(true, false, new[] { Shows.ToString("N"), Films.ToString() })));
        var viewer = As(_admin).Editors().Value!.Single(e => e.UserId == _viewer.Id);
        Assert.Equal("vi", viewer.UserName);
        Assert.Equal(new[] { Shows, Films }, viewer.LibraryIds);
        Assert.IsType<NoContentResult>(await As(_viewer).SaveDetails(_elsewhere.Id.ToString(), Json("""{"Overview":"ok"}""")));
        Assert.IsType<NoContentResult>(As(_admin).SetEditor(_viewer.Id.ToString(), new EditorPost(false, false, null)));
        Assert.DoesNotContain(As(_admin).Editors().Value!, e => e.UserId == _viewer.Id);
        Assert.IsType<NotFoundResult>(As(_admin).SetEditor(Guid.NewGuid().ToString(), new EditorPost(true, true, null)));
    }

    [Fact]
    public void TheDashboardListsEditorsAndTicksMakeThemEditorsOfEveryLibrary()
    {
        var book = new TimelineNoteBook(_db, _time);
        var keeping = new ChatKeeping(_db, book, new NoteChatBook(_db, book, _time));
        var settings = new SettingsController(Users(), keeping, _store) { ControllerContext = Context(_admin) };
        var section = settings.Get().Value!.Sections.Single(s => s.Title == "Metadata editors");
        var field = Assert.Single(section.Fields);
        Assert.Equal("users", field.Type);
        Assert.Equal(SettingsController.MetadataEditors, field.Key);
        Assert.Equal(new[] { _editor.Id.ToString("D") }, field.Value.EnumerateArray().Select(v => v.GetString()));

        // Ticking the viewer makes them an editor of every library; the editor ticked already keeps their libraries.
        settings.Save(new LogicSettingsPost(new Dictionary<string, JsonElement>
        {
            [SettingsController.MetadataEditors] = JsonSerializer.SerializeToElement(new[] { _editor.Id.ToString("D"), _viewer.Id.ToString("D") }),
        }));
        Assert.Equal(new EditorScope(_viewer.Id, true, []), _store.Editor(_viewer.Id)! with { LibraryIds = [] });
        Assert.Equal(new[] { Films }, _store.Editor(_editor.Id)!.LibraryIds);

        // Unticked: no longer an editor.
        settings.Save(new LogicSettingsPost(new Dictionary<string, JsonElement>
        {
            [SettingsController.MetadataEditors] = JsonSerializer.SerializeToElement(new[] { _viewer.Id.ToString("D") }),
        }));
        Assert.Null(_store.Editor(_editor.Id));
    }

    [Theory]
    [InlineData("GET", "/Nitrate/Metadata/Me")]
    [InlineData("GET", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Access")]
    [InlineData("POST", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff")]
    [InlineData("GET", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/ExternalIdInfos")]
    [InlineData("POST", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Apply")]
    [InlineData("POST", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Refresh")]
    [InlineData("POST", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/RemoteImages/Download")]
    [InlineData("POST", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Images/Backdrop/2/Index")]
    [InlineData("DELETE", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Images/Primary")]
    [InlineData("DELETE", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Images/Backdrop/1")]
    [InlineData("POST", "/Nitrate/Metadata/Items/6f9619ff8b86d011b42d00cf4fc964ff/Images/Primary")]
    [InlineData("GET", "/Nitrate/Metadata/Editors")]
    [InlineData("POST", "/Nitrate/Metadata/Editors/6f9619ff8b86d011b42d00cf4fc964ff")]
    [InlineData("GET", "/Nitrate/Metadata/Edits")]
    [InlineData("POST", "/Nitrate/Metadata/Edits/12/Undo")]
    public void EveryCallIsRoutedAndNeedsSigningIn(string method, string path)
    {
        var match = new ApiRouter(LogicServices.Controllers, typeof(CompanionLogic).Assembly).Match(method, path);
        Assert.Equal(RouteMatchKind.Found, match.Kind);
        Assert.False(match.AllowAnonymous);
    }

    private static JsonElement Json(string json) => JsonDocument.Parse(json).RootElement.Clone();

    private static void AssertMessage(IActionResult result, int status, string? message)
    {
        var o = Assert.IsType<ObjectResult>(result);
        Assert.Equal(status, o.StatusCode);
        if (message is not null)
        {
            Assert.Equal(message, Assert.IsType<JsonElement>(o.Value).GetProperty("message").GetString());
        }
    }

    private static void AssertMessage<T>(ActionResult<T> result, int status, string? message) => AssertMessage(result.Result!, status, message);

    private static ControllerContext Context(User? user) => new()
    {
        HttpContext = new DefaultHttpContext
        {
            User = new ClaimsPrincipal(new ClaimsIdentity(user is null ? [] : new[] { new Claim("Jellyfin-UserId", user.Id.ToString()) }, "test")),
        },
    };

    private async Task<ActionResult> Upload(string contentType, string body)
    {
        var controller = As(_editor);
        controller.Request.ContentType = contentType;
        controller.Request.Body = new MemoryStream(Encoding.ASCII.GetBytes(body));
        return await controller.UploadImage(_heat.Id.ToString(), ImageType.Primary, default);
    }

    private MetadataEditorController As(User user) => new(Users(), _store, _limits, _server) { ControllerContext = Context(user) };

    private IUserManager Users() => SmartCollectionTriggerTests.Fake.Of<IUserManager>(f =>
        f.Answers["GetUserById"] = args => _users.TryGetValue((Guid)args![0]!, out var u) ? u : null);

    private User Person(string name, bool admin = false)
    {
        var user = new User(name, "Default", "Default") { Id = Guid.NewGuid() };
        user.AddDefaultPermissions();
        user.SetPermission(PermissionKind.IsAdministrator, admin);
        _users[user.Id] = user;
        return user;
    }
}

internal static class ResultExtensions
{
    public static Task<ActionResult> ToTask(this ActionResult result) => Task.FromResult(result);
}
