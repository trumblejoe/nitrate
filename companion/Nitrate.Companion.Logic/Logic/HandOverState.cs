using Nitrate.Companion.Chat;
using Nitrate.Companion.Recommendations;
using Nitrate.Companion.Sso;
using Nitrate.Companion.Together;

// What one logic hands the next in memory, as plain JSON under versioned keys (HandOver.Keys).
// Change a shape only together with a new key (chat.v2), and keep reading the old key for a while.
namespace Nitrate.Companion.Chat
{
    /// <summary>The chat rooms (<c>chat.v1</c>).</summary>
    /// <param name="Rooms">Every room.</param>
    public record ChatState(IReadOnlyList<ChatRoomState> Rooms);

    /// <summary>One room as handed over.</summary>
    /// <param name="Id">The room (SyncPlay group).</param>
    /// <param name="Messages">Its kept messages and who each is for.</param>
    /// <param name="Latest">The last message number.</param>
    /// <param name="Brought">How often notes were brought in.</param>
    /// <param name="LastUsed">When it was last used.</param>
    public record ChatRoomState(string Id, IReadOnlyList<ChatEntryState> Messages, long Latest, long Brought, DateTimeOffset LastUsed);

    /// <summary>One message as handed over.</summary>
    /// <param name="Message">The message.</param>
    /// <param name="Audience">Who may read it (null: everyone in the room).</param>
    public record ChatEntryState(ChatMessage Message, IReadOnlyList<Guid>? Audience);
}

namespace Nitrate.Companion.Insights
{
    /// <summary>A session's brought notes (<c>brought.v1</c>).</summary>
    /// <param name="SessionId">The SyncPlay session.</param>
    /// <param name="GroupId">The watch group whose notes were brought.</param>
    /// <param name="By">Who brought them.</param>
    /// <param name="At">When.</param>
    public record BroughtState(Guid SessionId, Guid GroupId, Guid By, DateTimeOffset At);

    /// <summary>An earlier chat playing along in a live session (<c>sessionchats.v1</c>).</summary>
    /// <param name="SessionId">The session.</param>
    /// <param name="ItemId">The title it plays on.</param>
    /// <param name="ChatId">The chat.</param>
    /// <param name="By">Who picked it.</param>
    /// <param name="At">When.</param>
    public record AlongState(Guid SessionId, Guid ItemId, Guid ChatId, Guid By, DateTimeOffset At);

    /// <summary>An open sitting (<c>sittings.v1</c>).</summary>
    /// <param name="Key">The play session and item.</param>
    /// <param name="Started">When it started.</param>
    /// <param name="Last">The last report.</param>
    /// <param name="Paused">Whether it was paused then.</param>
    /// <param name="WatchedMs">Watched so far.</param>
    public record SittingState(string Key, DateTimeOffset Started, DateTimeOffset Last, bool Paused, long WatchedMs);
}

namespace Nitrate.Companion.Partners
{
    /// <summary>Who was seen together in a live session (<c>together.v1</c>).</summary>
    /// <param name="SessionId">The server session.</param>
    /// <param name="ItemId">What it played.</param>
    /// <param name="Present">Who was there.</param>
    /// <param name="At">When last seen.</param>
    /// <param name="Group">The SyncPlay group it was seen in (null from a logic that didn't say: not taken over).</param>
    public record TogetherSeenState(string SessionId, Guid ItemId, IReadOnlyList<Guid> Present, DateTimeOffset At, string? Group = null);

    /// <summary>A live session started for groups (<c>groupsessions.v1</c>).</summary>
    /// <param name="Key">The session key (<see cref="PartnerHub.SessionKey"/>).</param>
    /// <param name="Groups">The groups it was started for.</param>
    /// <param name="At">When the app last said so.</param>
    public record GroupSessionState(string Key, IReadOnlyList<Guid> Groups, DateTimeOffset At);
}

namespace Nitrate.Companion.QueryTimes
{
    /// <summary>The request times kept (<c>querytimes.v1</c>).</summary>
    /// <param name="Active">Whether the timing is known to run.</param>
    /// <param name="Users">Each person's times.</param>
    public record QueryTimesState(bool Active, IReadOnlyList<QueryTimesUserState> Users);

    /// <summary>One person's times.</summary>
    /// <param name="UserId">The person.</param>
    /// <param name="Times">Their times, oldest first.</param>
    public record QueryTimesUserState(Guid UserId, IReadOnlyList<QueryTimeState> Times);

    /// <summary>One time.</summary>
    /// <param name="Tag">The app's tag.</param>
    /// <param name="Ms">How long it took.</param>
    /// <param name="At">When.</param>
    /// <param name="Shape">The request's shape.</param>
    public record QueryTimeState(string Tag, long Ms, DateTimeOffset At, string Shape);
}

namespace Nitrate.Companion.Sso
{
    /// <summary>A sign-in under way (<c>sso.v1</c>).</summary>
    public record PendingSignInState(
        string Code,
        string Secret,
        SsoDevice Device,
        DateTimeOffset Expires,
        string? State,
        string? Nonce,
        string? Verifier,
        string? RedirectUri,
        Guid? UserId,
        string? UserName,
        string? Error);
}

namespace Nitrate.Companion.Logic
{
    /// <summary>The state keys, each with its format's version.</summary>
    public static class HandOver
    {
        /// <summary>Chat rooms (<see cref="ChatHub"/>).</summary>
        public const string Chat = "chat.v1";

        /// <summary>Watch-together invites (<see cref="InviteHub"/>).</summary>
        public const string Invites = "invites.v1";

        /// <summary>Brought notes (<see cref="Insights.GroupNotes"/>).</summary>
        public const string Brought = "brought.v1";

        /// <summary>Earlier chats playing along in live sessions (<see cref="Insights.GroupNotes"/>).</summary>
        public const string SessionChats = "sessionchats.v1";

        /// <summary>Sign-ins under way (<see cref="SsoService"/>).</summary>
        public const string Sso = "sso.v1";

        /// <summary>Request times (<see cref="QueryTimes.QueryTimeStore"/>).</summary>
        public const string QueryTimes = "querytimes.v1";

        /// <summary>Because you watched rows (<see cref="BecauseStore"/>).</summary>
        public const string Because = "because.v1";

        /// <summary>Open sittings (<see cref="Insights.SittingRecorder"/>).</summary>
        public const string Sittings = "sittings.v1";

        /// <summary>Live sessions seen together (<see cref="Partners.TogetherRecorder"/>).</summary>
        public const string Together = "together.v1";

        /// <summary>Live sessions started for groups (<see cref="Partners.PartnerHub.SessionFor"/>).</summary>
        public const string GroupSessions = "groupsessions.v1";

        /// <summary>Group Watch Sessions (<see cref="Partners.WatchSessions"/>).</summary>
        public const string WatchSessions = "watchsessions.v1";
    }
}
