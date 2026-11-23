using Jellyfin.Data;
using Jellyfin.Data.Queries;
using Jellyfin.Database.Implementations.Entities.Security;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;

namespace Nitrate.Companion.Sso;

/// <summary>Jellyfin's users and devices, for single sign-on.</summary>
public class JellyfinSsoAccounts : ISsoAccounts
{
    private readonly IUserManager _users;
    private readonly IDeviceManager _devices;
    private readonly ISessionManager _sessions;

    /// <summary>Initializes a new instance of the <see cref="JellyfinSsoAccounts"/> class.</summary>
    public JellyfinSsoAccounts(IUserManager users, IDeviceManager devices, ISessionManager sessions)
    {
        _users = users;
        _devices = devices;
        _sessions = sessions;
    }

    /// <inheritdoc />
    public (Guid Id, string Name)? FindUser(string name)
    {
        var user = _users.GetUserByName(name.Trim())
            ?? _users.GetUsers().FirstOrDefault(u => string.Equals(u.Username, name.Trim(), StringComparison.OrdinalIgnoreCase));
        return user is null || user.HasPermission(PermissionKind.IsDisabled) ? null : (user.Id, user.Username);
    }

    /// <inheritdoc />
    public async Task<string> SignIn(Guid userId, SsoDevice device)
    {
        var user = _users.GetUserById(userId) ?? throw new InvalidOperationException("That account no longer exists.");
        if (user.HasPermission(PermissionKind.IsDisabled))
        {
            throw new InvalidOperationException("That account is disabled.");
        }

        // As a password sign-in does: this device's earlier sign-in for the same user ends.
        foreach (var old in _devices.GetDevices(new DeviceQuery { DeviceId = device.DeviceId, UserId = userId }).Items)
        {
            try
            {
                await _sessions.Logout(old.AccessToken).ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Already gone.
            }
        }

        var created = await _devices.CreateDevice(new Device(userId, device.App, device.AppVersion, device.DeviceName, device.DeviceId)).ConfigureAwait(false);
        user.LastLoginDate = DateTime.UtcNow;
        await _users.UpdateUserAsync(user).ConfigureAwait(false);
        return created.AccessToken;
    }
}
