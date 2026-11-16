using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Data.Sqlite;

namespace Nitrate.Companion.Lines;

/// <summary>One subtitle line of a title.</summary>
/// <param name="StartMs">When it's said.</param>
/// <param name="EndMs">When it ends.</param>
/// <param name="Text">What's said.</param>
public record LineCue(long StartMs, long EndMs, string Text);

/// <summary>A line found by a search, with the lines either side.</summary>
/// <param name="ItemId">The title it's in.</param>
/// <param name="StartMs">When it's said.</param>
/// <param name="EndMs">When it ends.</param>
/// <param name="Text">The line.</param>
/// <param name="Language">The subtitle's language.</param>
/// <param name="Before">The line before (or null).</param>
/// <param name="After">The line after (or null).</param>
public record LineHit(Guid ItemId, long StartMs, long EndMs, string Text, string? Language, string? Before, string? After);

/// <summary>
/// "Search what people say": every indexed title's subtitle lines in SQLite with full-text search
/// (FTS5), so a line ("we're gonna need a bigger") finds the titles and moments it's said in.
/// Kept in the server's data folder; rebuilt per title when its subtitles change.
/// </summary>
public sealed class LineIndex : IDisposable
{
    private readonly SqliteConnection _db;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="LineIndex"/> class at <paramref name="path"/> (":memory:" for tests).</summary>
    public LineIndex(string path)
    {
        if (path != ":memory:")
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        }

        _db = new SqliteConnection("Data Source=" + path);
        _db.Open();
        Exec("PRAGMA journal_mode=WAL");
        Exec("""
            CREATE TABLE IF NOT EXISTS items(item TEXT PRIMARY KEY, version TEXT NOT NULL, lines INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS cues(id INTEGER PRIMARY KEY, item TEXT NOT NULL, seq INTEGER NOT NULL, start INTEGER NOT NULL, finish INTEGER NOT NULL, lang TEXT, text TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS cues_item ON cues(item, seq);
            CREATE VIRTUAL TABLE IF NOT EXISTS cues_fts USING fts5(text, content='cues', content_rowid='id', tokenize='unicode61 remove_diacritics 2');
            CREATE TRIGGER IF NOT EXISTS cues_ai AFTER INSERT ON cues BEGIN INSERT INTO cues_fts(rowid, text) VALUES (new.id, new.text); END;
            CREATE TRIGGER IF NOT EXISTS cues_ad AFTER DELETE ON cues BEGIN INSERT INTO cues_fts(cues_fts, rowid, text) VALUES ('delete', old.id, old.text); END;
            """);
    }

    /// <summary>Whether <paramref name="itemId"/> needs (re)indexing: never indexed, or its subtitles changed (<paramref name="version"/>).</summary>
    public bool NeedsIndex(Guid itemId, string version)
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT version FROM items WHERE item = $i";
            cmd.Parameters.AddWithValue("$i", Key(itemId));
            return cmd.ExecuteScalar() as string != version;
        }
    }

    /// <summary>Replaces a title's lines (per language).</summary>
    public void Replace(Guid itemId, string version, IReadOnlyList<(string? Language, IReadOnlyList<LineCue> Cues)> tracks)
    {
        lock (_lock)
        {
            using var tx = _db.BeginTransaction();
            DeleteItem(itemId, tx);
            using var insert = _db.CreateCommand();
            insert.Transaction = tx;
            insert.CommandText = "INSERT INTO cues(item, seq, start, finish, lang, text) VALUES ($i, $s, $a, $b, $l, $t)";
            var pi = insert.Parameters.Add("$i", SqliteType.Text);
            var ps = insert.Parameters.Add("$s", SqliteType.Integer);
            var pa = insert.Parameters.Add("$a", SqliteType.Integer);
            var pb = insert.Parameters.Add("$b", SqliteType.Integer);
            var pl = insert.Parameters.Add("$l", SqliteType.Text);
            var pt = insert.Parameters.Add("$t", SqliteType.Text);
            var seq = 0;
            var count = 0;
            foreach (var (language, cues) in tracks)
            {
                // A gap in the numbering keeps one language's lines from being another's neighbours.
                seq += 10;
                foreach (var c in cues)
                {
                    pi.Value = Key(itemId);
                    ps.Value = seq++;
                    pa.Value = c.StartMs;
                    pb.Value = c.EndMs;
                    pl.Value = (object?)language ?? DBNull.Value;
                    pt.Value = c.Text;
                    insert.ExecuteNonQuery();
                    count++;
                }
            }

            using var item = _db.CreateCommand();
            item.Transaction = tx;
            item.CommandText = "INSERT OR REPLACE INTO items(item, version, lines) VALUES ($i, $v, $n)";
            item.Parameters.AddWithValue("$i", Key(itemId));
            item.Parameters.AddWithValue("$v", version);
            item.Parameters.AddWithValue("$n", count);
            item.ExecuteNonQuery();
            tx.Commit();
        }
    }

    /// <summary>Forgets a title (gone from the library).</summary>
    public void Remove(Guid itemId)
    {
        lock (_lock)
        {
            using var tx = _db.BeginTransaction();
            DeleteItem(itemId, tx);
            tx.Commit();
        }
    }

    /// <summary>Every indexed title.</summary>
    public IReadOnlyList<Guid> Items()
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT item FROM items";
            using var r = cmd.ExecuteReader();
            var list = new List<Guid>();
            while (r.Read())
            {
                list.Add(Guid.Parse(r.GetString(0)));
            }

            return list;
        }
    }

    /// <summary>How many titles and lines are indexed.</summary>
    public (int Titles, long Lines) Count()
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT COUNT(*), COALESCE(SUM(lines), 0) FROM items";
            using var r = cmd.ExecuteReader();
            r.Read();
            return (r.GetInt32(0), r.GetInt64(1));
        }
    }

    /// <summary>
    /// Lines with all of <paramref name="query"/>'s words in that order (the last one may be
    /// unfinished), best matches first; only titles the caller can see (<paramref name="visible"/>),
    /// at most one per moment.
    /// </summary>
    public IReadOnlyList<LineHit> Search(string query, int limit, Func<Guid, bool> visible)
    {
        var match = MatchExpression(query);
        if (match is null)
        {
            return Array.Empty<LineHit>();
        }

        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = """
                SELECT c.item, c.seq, c.start, c.finish, c.lang, c.text FROM cues_fts f JOIN cues c ON c.id = f.rowid
                WHERE cues_fts MATCH $q ORDER BY rank LIMIT $n
                """;
            cmd.Parameters.AddWithValue("$q", match);
            cmd.Parameters.AddWithValue("$n", Math.Clamp(limit, 1, 200) * 5);
            var found = new List<(Guid Item, long Seq, long Start, long End, string? Lang, string Text)>();
            using (var r = cmd.ExecuteReader())
            {
                while (r.Read())
                {
                    found.Add((Guid.Parse(r.GetString(0)), r.GetInt64(1), r.GetInt64(2), r.GetInt64(3), r.IsDBNull(4) ? null : r.GetString(4), r.GetString(5)));
                }
            }

            var seen = new Dictionary<Guid, bool>();
            var hits = new List<LineHit>();
            foreach (var f in found)
            {
                if (!seen.TryGetValue(f.Item, out var ok))
                {
                    ok = visible(f.Item);
                    seen[f.Item] = ok;
                }

                // The same moment in another language's subtitles, or a line split over two cues.
                if (!ok || hits.Any(h => h.ItemId == f.Item && Math.Abs(h.StartMs - f.Start) < 10_000))
                {
                    continue;
                }

                hits.Add(new LineHit(f.Item, f.Start, f.End, f.Text, f.Lang, Neighbour(f.Item, f.Seq - 1), Neighbour(f.Item, f.Seq + 1)));
                if (hits.Count >= limit)
                {
                    break;
                }
            }

            return hits;
        }
    }

    /// <summary>
    /// An FTS5 phrase for what was typed: its words in order, the last as a prefix ("bigger b" →
    /// "bigger" "b"*), with punctuation dropped. Null when nothing searchable is left.
    /// </summary>
    public static string? MatchExpression(string query)
    {
        var words = Regex.Matches(query.ToLowerInvariant(), @"[\p{L}\p{N}]+").Select(m => m.Value).ToList();
        if (words.Count == 0)
        {
            return null;
        }

        var sb = new StringBuilder();
        for (var i = 0; i < words.Count; i++)
        {
            if (i > 0)
            {
                sb.Append(" + ");
            }

            sb.Append('"').Append(words[i]).Append('"');
            if (i == words.Count - 1)
            {
                sb.Append('*');
            }
        }

        return sb.ToString();
    }

    /// <inheritdoc />
    public void Dispose() => _db.Dispose();

    private string? Neighbour(Guid item, long seq)
    {
        using var cmd = _db.CreateCommand();
        cmd.CommandText = "SELECT text FROM cues WHERE item = $i AND seq = $s";
        cmd.Parameters.AddWithValue("$i", Key(item));
        cmd.Parameters.AddWithValue("$s", seq);
        return cmd.ExecuteScalar() as string;
    }

    private void DeleteItem(Guid itemId, SqliteTransaction tx)
    {
        using var cmd = _db.CreateCommand();
        cmd.Transaction = tx;
        cmd.CommandText = "DELETE FROM cues WHERE item = $i; DELETE FROM items WHERE item = $i";
        cmd.Parameters.AddWithValue("$i", Key(itemId));
        cmd.ExecuteNonQuery();
    }

    private void Exec(string sql)
    {
        using var cmd = _db.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private static string Key(Guid id) => id.ToString("N", CultureInfo.InvariantCulture);
}
