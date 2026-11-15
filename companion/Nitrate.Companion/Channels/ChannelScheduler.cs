namespace Nitrate.Companion.Channels;

/// <summary>
/// Makes a channel's schedule: titles back to back from its lineup (or a time slot's), each
/// from its start, optionally starting on a boundary (:00/:30...). The schedule is made ahead and
/// kept, like a printed TV guide; nothing plays unless someone tunes in.
/// </summary>
public static class ChannelScheduler
{
    /// <summary>How long programs are kept after they end.</summary>
    public static readonly TimeSpan KeepPast = TimeSpan.FromDays(1);

    private const int MaxPrograms = 5000;

    /// <summary>Makes the schedule up to <paramref name="until"/>; returns whether anything changed.</summary>
    public static bool Extend(StoredChannel ch, Func<ChannelSource, IReadOnlyList<PlayItem>> expand, DateTimeOffset now, DateTimeOffset until, TimeZoneInfo tz)
    {
        var p = ch.Playout;
        var changed = p.Programs.RemoveAll(x => x.End < now - KeepPast) > 0;
        var cache = new Dictionary<string, IReadOnlyList<PlayItem>>();
        IReadOnlyList<PlayItem> Items(ChannelSource s)
        {
            if (!cache.TryGetValue(s.Key, out var list))
            {
                list = expand(s).Where(i => i.RuntimeMs > 0).ToList();
                cache[s.Key] = list;
            }

            return list;
        }

        var t = p.Programs.Count > 0 ? p.Programs[^1].End : Floor(now, ch.AlignMinutes > 0 ? ch.AlignMinutes : 30, tz);
        var guard = 0;
        while (t < until && guard++ < MaxPrograms)
        {
            var local = TimeZoneInfo.ConvertTime(t, tz);
            var (index, block) = ActiveBlock(ch.Blocks, local);
            var lineup = block?.Sources ?? ch.Sources;
            var mix = block?.Mix ?? ch.Mix;
            var key = block is null ? "main" : "b" + index;
            var picked = Next(p, key, lineup, mix, Items);
            if (picked is not { } found)
            {
                // Nothing to play here: off air until the next time slot starts or ends.
                var next = NextChange(ch.Blocks, t, tz);
                if (next is null)
                {
                    break;
                }

                t = next.Value;
                continue;
            }

            var program = found.Program;
            program.Start = t;
            program.End = t + TimeSpan.FromMilliseconds(found.RuntimeMs);
            program.Block = block?.Name;
            p.Programs.Add(program);
            changed = true;
            t = ch.AlignMinutes > 0 ? Ceil(program.End, ch.AlignMinutes, tz) : program.End;
        }

        return changed;
    }

    /// <summary>
    /// Drops what's scheduled after <paramref name="now"/> (the program on now stays), putting each
    /// source back where it was, so a changed lineup carries on from the same place.
    /// </summary>
    public static void DropFuture(StoredChannel ch, DateTimeOffset now)
    {
        var p = ch.Playout;
        for (var i = p.Programs.Count - 1; i >= 0 && p.Programs[i].Start > now; i--)
        {
            var x = p.Programs[i];
            if (x.CursorBefore is null)
            {
                p.Cursors.Remove(x.Cursor);
            }
            else
            {
                p.Cursors[x.Cursor] = x.CursorBefore;
            }

            p.Turns[x.Lineup] = x.TurnBefore;
            p.Programs.RemoveAt(i);
        }
    }

    /// <summary>The time slot running at <paramref name="local"/>, if any (the first listed wins).</summary>
    public static (int Index, ChannelBlock? Block) ActiveBlock(IReadOnlyList<ChannelBlock> blocks, DateTimeOffset local)
    {
        var day = (int)local.DayOfWeek;
        var minute = (local.Hour * 60) + local.Minute;
        for (var i = 0; i < blocks.Count; i++)
        {
            var b = blocks[i];
            var covers = b.EndMinute > b.StartMinute
                ? b.Days.Contains(day) && minute >= b.StartMinute && minute < b.EndMinute
                : (b.Days.Contains(day) && minute >= b.StartMinute) || (b.Days.Contains((day + 6) % 7) && minute < b.EndMinute);
            if (covers && b.Sources.Count > 0)
            {
                return (i, b);
            }
        }

        return (-1, null);
    }

    /// <summary>The next time after <paramref name="t"/> a time slot starts or ends (within a week).</summary>
    public static DateTimeOffset? NextChange(IReadOnlyList<ChannelBlock> blocks, DateTimeOffset t, TimeZoneInfo tz)
    {
        DateTimeOffset? best = null;
        var today = TimeZoneInfo.ConvertTime(t, tz).Date;
        for (var d = -1; d <= 8; d++)
        {
            var date = today.AddDays(d);
            foreach (var b in blocks.Where(b => b.Sources.Count > 0 && b.Days.Contains((int)date.DayOfWeek)))
            {
                var end = b.EndMinute > b.StartMinute ? b.EndMinute : b.EndMinute + (24 * 60);
                foreach (var m in new[] { b.StartMinute, end })
                {
                    var at = ToUtc(date.AddMinutes(m), tz);
                    if (at > t && (best is null || at < best))
                    {
                        best = at;
                    }
                }
            }
        }

        return best;
    }

    private static (ScheduledProgram Program, long RuntimeMs)? Next(Playout p, string lineup, IReadOnlyList<ChannelSource> sources, ChannelMix mix, Func<ChannelSource, IReadOnlyList<PlayItem>> items)
    {
        var usable = sources.Where(s => items(s).Count > 0).ToList();
        if (usable.Count == 0)
        {
            return null;
        }

        var turn = p.Turns.GetValueOrDefault(lineup);
        var source = mix == ChannelMix.Shuffle
            ? usable[new Random(unchecked(p.Seed + (int)Hash(lineup) + turn)).Next(usable.Count)]
            : usable[turn % usable.Count];
        var list = items(source);
        var cursorKey = lineup + "|" + source.Key;
        var before = p.Cursors.TryGetValue(cursorKey, out var c) ? c.Copy() : null;
        var cursor = c ?? new SourceCursor();

        var order = Order(list, source, cursor.Cycle, p.Seed, cursorKey);
        var pos = cursor.LastId is { } last && order.FindIndex(i => i.Id == last) is var found and >= 0 ? found + 1 : cursor.Index;
        if (pos >= order.Count)
        {
            pos = 0;
            cursor.Cycle++;
            order = Order(list, source, cursor.Cycle, p.Seed, cursorKey);
        }

        var item = order[pos];
        cursor.LastId = item.Id;
        cursor.Index = pos + 1;
        p.Cursors[cursorKey] = cursor;
        var finished = pos + 1 >= order.Count;
        p.Turns[lineup] = mix == ChannelMix.Sequential && !finished ? turn : turn + 1;

        return (new ScheduledProgram
        {
            ItemId = item.Id,
            Lineup = lineup,
            Cursor = cursorKey,
            CursorBefore = before,
            TurnBefore = turn,
        }, item.RuntimeMs);
    }

    private static List<PlayItem> Order(IReadOnlyList<PlayItem> list, ChannelSource source, int cycle, int seed, string key)
    {
        var order = list.ToList();
        if (source.Shuffle)
        {
            var random = new Random(unchecked(seed ^ (int)Hash(key) ^ (cycle * 7919)));
            for (var i = order.Count - 1; i > 0; i--)
            {
                var j = random.Next(i + 1);
                (order[i], order[j]) = (order[j], order[i]);
            }
        }

        return order;
    }

    /// <summary>A hash that's the same on every run (string.GetHashCode isn't).</summary>
    private static uint Hash(string s)
    {
        var h = 2166136261u;
        foreach (var ch in s)
        {
            h = unchecked((h ^ ch) * 16777619u);
        }

        return h;
    }

    private static DateTimeOffset Floor(DateTimeOffset t, int minutes, TimeZoneInfo tz)
    {
        var local = TimeZoneInfo.ConvertTime(t, tz);
        var midnight = local.Date;
        var into = (int)(local.DateTime - midnight).TotalMinutes;
        return ToUtc(midnight.AddMinutes(into / minutes * minutes), tz);
    }

    private static DateTimeOffset Ceil(DateTimeOffset t, int minutes, TimeZoneInfo tz)
    {
        var floor = Floor(t, minutes, tz);
        return floor == t || (t - floor) < TimeSpan.FromSeconds(1) ? floor : Floor(t + TimeSpan.FromMinutes(minutes), minutes, tz);
    }

    private static DateTimeOffset ToUtc(DateTime local, TimeZoneInfo tz)
    {
        var unspecified = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);
        while (tz.IsInvalidTime(unspecified))
        {
            unspecified = unspecified.AddMinutes(30);
        }

        return new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(unspecified, tz), TimeSpan.Zero);
    }
}
