using System.Text.Json;
using MediaBrowser.Controller.Session;
using MediaBrowser.Model.Session;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Partners;

/// <summary>A connected app session, as the party announcements need it.</summary>
public record LiveApp(string SessionId, Guid UserId, string? Client);

/// <summary>What one member's apps are sent about a group's party (the text of a "Nitrate.Party" message).</summary>
public record PartyMessage(string Kind, Guid GroupId, string SeriesName, IReadOnlyList<string> Others, string? ByName, bool ByMe, int Minutes, PartyView? Party);

/// <summary>
/// Tells the members' open Nitrate apps about their groups' watch parties at once, over Jellyfin's
/// own session connection (a DisplayMessage with the header <see cref="Header"/>, which the apps
/// never show as a message): the reminder 15 minutes before, postponements, requests to agree to,
/// tonight being off, and at start time whether to start without someone. Nothing goes through
/// any outside service; phones with the app closed set their own reminders. Checks the clock
/// every half minute.
/// </summary>
public class PartyPush : IHostedService, IDisposable
{
    /// <summary>The message header the apps listen for.</summary>
    public const string Header = "Nitrate.Party";

    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = null };
    private readonly ISessionManager _sessions;
    private readonly PartnerHub _partners;
    private readonly Func<Guid, string?> _names;
    private Timer? _timer;

    /// <summary>Initializes a new instance of the <see cref="PartyPush"/> class.</summary>
    public PartyPush(ISessionManager sessions, PartnerHub partners, MediaBrowser.Controller.Library.IUserManager users)
    {
        _sessions = sessions;
        _partners = partners;
        _names = id => users.GetUserById(id)?.Username;
    }

    /// <summary>Whether a session is one of Nitrate's apps (TV and iPhone "Nitrate", phones "Nitrate Mobile").</summary>
    public static bool IsNitrate(string? client) => client is not null && (client == "Nitrate" || client.StartsWith("Nitrate ", StringComparison.Ordinal));

    /// <summary>The sessions an announcement for a group with [members] goes to: only those members' Nitrate apps.</summary>
    public static IReadOnlyList<LiveApp> Targets(IReadOnlyCollection<Guid> members, IEnumerable<LiveApp> sessions) =>
        sessions.Where(s => members.Contains(s.UserId) && IsNitrate(s.Client)).ToList();

    /// <summary>The message for [member] about [a] (null when they're no longer in the group, or it has no party).</summary>
    public static PartyMessage? MessageFor(PartyAnnouncement a, Guid member, PartnerHub partners, Func<Guid, string?> names)
    {
        PartyView? party;
        (string Title, IReadOnlyList<string> Others)? group;
        try
        {
            party = partners.Party(member, a.GroupId);
            group = partners.Names(member, a.GroupId);
        }
        catch (PartnerException)
        {
            return null;
        }

        if (party is null || group is not { } g)
        {
            return null;
        }

        return new PartyMessage(a.Kind, a.GroupId, g.Title, g.Others, a.By is { } by ? names(by) : null, a.By == member, a.Minutes, party);
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _partners.PartyAnnounced += OnAnnounced;
        _timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(20), TimeSpan.FromSeconds(30));
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _partners.PartyAnnounced -= OnAnnounced;
        _timer?.Change(Timeout.Infinite, Timeout.Infinite);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public void Dispose()
    {
        _timer?.Dispose();
        GC.SuppressFinalize(this);
    }

    private void Tick()
    {
        try
        {
            // Raises PartyAnnounced for whatever's due.
            _partners.DueParties();
        }
        catch (Exception)
        {
            // Try again next time.
        }
    }

    private void OnAnnounced(PartyAnnouncement a) => _ = Task.Run(() => SendAsync(a));

    private async Task SendAsync(PartyAnnouncement a)
    {
        var members = _partners.MembersOf(a.GroupId);
        var live = _sessions.Sessions.Where(s => s.IsActive).Select(s => new LiveApp(s.Id, s.UserId, s.Client)).ToList();
        foreach (var byMember in Targets(members, live).GroupBy(s => s.UserId))
        {
            if (MessageFor(a, byMember.Key, _partners, _names) is not { } message)
            {
                continue;
            }

            var text = JsonSerializer.Serialize(message, Json);
            foreach (var s in byMember)
            {
                try
                {
                    await _sessions.SendMessageCommand(null, s.SessionId, new MessageCommand { Header = Header, Text = text }, CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception)
                {
                    // The app went away meanwhile.
                }
            }
        }
    }
}
