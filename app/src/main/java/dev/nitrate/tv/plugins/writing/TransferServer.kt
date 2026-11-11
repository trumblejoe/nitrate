package dev.nitrate.tv.plugins.writing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/** What the transfer page can do with documents; [WritingStore] in the app, a fake in tests. */
interface TransferDocs {
    fun list(): List<WritingDoc>
    suspend fun read(id: String): String?
    suspend fun create(text: String): String
}

data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray) {
    companion object {
        const val MAX_BODY = 5 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** Reads one HTTP/1.1 request. Returns null for anything malformed or too large. */
        fun read(input: InputStream): HttpRequest? {
            // Headers end at the first blank line.
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n") && !head.endsWith("\n\n")) {
                if (head.length >= MAX_HEADER_BYTES) return null
                val b = input.read()
                if (b < 0) return null
                head.append(b.toChar())
            }
            val lines = head.toString().split(Regex("\r?\n")).filter { it.isNotEmpty() }
            val (method, target) = lines.firstOrNull()?.split(' ')?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: return null
            val headers = lines.drop(1).mapNotNull { line ->
                line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            val body = input.readNBytesCompat(length) ?: return null
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            return HttpRequest(method.uppercase(), URLDecoder.decode(path, "UTF-8"), query, headers, body)
        }

        private fun InputStream.readNBytesCompat(n: Int): ByteArray? {
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = read(out, read, n - read)
                if (r < 0) return null
                read += r
            }
            return out
        }
    }
}

class HttpResponse(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    fun write(out: OutputStream) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        fun text(status: Int, message: String) = HttpResponse(status, "text/plain; charset=utf-8", message.toByteArray())
        fun json(body: String) = HttpResponse(200, "application/json; charset=utf-8", body.toByteArray())
    }
}

/**
 * The transfer page's routes. Every API call needs the PIN shown on the TV, as an
 * `X-Nitrate-Pin` header or a `pin` query parameter (for plain download links).
 */
class TransferHandler(private val docs: TransferDocs, private val pin: String, private val onBadPin: () -> Unit = {}) {

    suspend fun handle(req: HttpRequest): HttpResponse {
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            return HttpResponse(200, "text/html; charset=utf-8", PAGE.toByteArray())
        }
        if (!req.path.startsWith("/api/")) return HttpResponse.text(404, "Not found")
        val given = req.headers["x-nitrate-pin"] ?: req.query["pin"].orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), pin.toByteArray())) {
            onBadPin()
            return HttpResponse.text(403, "Wrong PIN")
        }

        val download = Regex("^/api/docs/([A-Za-z0-9-]+)\\.(fountain|fadein)$").find(req.path)
        return when {
            req.method == "GET" && req.path == "/api/docs" -> HttpResponse.json(
                docs.list().joinToString(",", "[", "]") { """{"id":"${it.id}","title":${jsonString(it.title)},"modified":${it.modifiedMs}}""" },
            )
            req.method == "GET" && download != null -> {
                val (id, ext) = download.destructured
                val text = docs.read(id) ?: return HttpResponse.text(404, "No such script")
                val format = if (ext == "fadein") ExportFormat.FADE_IN else ExportFormat.FOUNTAIN
                HttpResponse(
                    200, format.mime, format.bytes(text),
                    mapOf("Content-Disposition" to "attachment; filename=\"${fileName(text, format)}\""),
                )
            }
            req.method == "POST" && req.path == "/api/upload" -> {
                if (req.body.isEmpty()) return HttpResponse.text(400, "Empty file")
                val text = runCatching { importText(req.body) }.getOrElse { return HttpResponse.text(400, "Couldn't read that file: ${it.message}") }
                val id = docs.create(text)
                HttpResponse.json("""{"id":"$id","title":${jsonString(WritingStore.titleOf(text))}}""")
            }
            else -> HttpResponse.text(404, "Not found")
        }
    }

    private fun jsonString(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    companion object {
        /** Self-contained page: lists scripts with download links, and uploads dropped or chosen files. */
        val PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nitrate scripts</title>
<style>
:root{color-scheme:light dark;--bg:#0b0d12;--fg:#ece6dc;--muted:#b9b2a6;--accent:#e8a33d;--on-accent:#1e1404;--card:#161a22}
@media (prefers-color-scheme:light){:root{--bg:#faf8f4;--fg:#1b1a17;--muted:#5d574d;--card:#fff;--accent:#9a5d08;--on-accent:#fff}}
body{margin:0;font:16px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg)}
main{max-width:720px;margin:0 auto;padding:32px 16px}
h1{margin:0 0 4px;font-size:28px}p{color:var(--muted);margin:0 0 20px}
input[type=password]{font:inherit;padding:10px 12px;border-radius:8px;border:1px solid var(--muted);background:var(--card);color:var(--fg);width:10em;letter-spacing:.2em}
button,label.button{font:inherit;padding:10px 16px;border-radius:8px;border:0;background:var(--accent);color:var(--on-accent);font-weight:600;cursor:pointer}
ul{list-style:none;padding:0;margin:20px 0}li{background:var(--card);border-radius:10px;padding:14px 16px;margin-bottom:10px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
li span{flex:1;min-width:12em}li a{color:var(--accent);font-weight:600}
#drop{border:2px dashed var(--muted);border-radius:12px;padding:24px;text-align:center;margin-top:16px}
#drop.over{border-color:var(--accent)}#msg{min-height:1.5em;color:var(--accent)}
</style></head><body><main>
<h1>Nitrate scripts</h1><p>Download scripts from your TV, or send scripts to it. Enter the PIN shown on the TV.</p>
<form id="login"><input id="pin" type="password" inputmode="numeric" autocomplete="off" placeholder="PIN"> <button>Open</button></form>
<div id="app" hidden>
<ul id="list"></ul>
<div id="drop">Drop .fadein or .fountain files here, or <label class="button">choose files<input id="file" type="file" multiple accept=".fadein,.fountain,.txt" hidden></label></div>
<p id="msg"></p></div>
<script>
let pin=sessionStorage.getItem('pin')||'';
const byId=id=>document.getElementById(id);
async function api(path,opts={}){const r=await fetch(path,{...opts,headers:{'X-Nitrate-Pin':pin,...(opts.headers||{})}});if(r.status===403){sessionStorage.removeItem('pin');byId('app').hidden=true;byId('login').hidden=false;throw new Error('Wrong PIN');}if(!r.ok)throw new Error(await r.text());return r;}
async function load(){const docs=await (await api('/api/docs')).json();byId('login').hidden=true;byId('app').hidden=false;
const list=byId('list');list.textContent='';if(!docs.length){list.innerHTML='<li><span>No scripts on the TV yet.</span></li>';}
for(const d of docs){const li=document.createElement('li');const s=document.createElement('span');s.textContent=d.title+' — '+new Date(d.modified).toLocaleString();li.append(s);
for(const ext of ['fadein','fountain']){const a=document.createElement('a');a.href='/api/docs/'+d.id+'.'+ext+'?pin='+encodeURIComponent(pin);a.textContent='.'+ext;li.append(a);}list.append(li);}}
async function upload(files){for(const f of files){try{const r=await api('/api/upload',{method:'POST',body:f});const j=await r.json();byId('msg').textContent='Sent “'+j.title+'”';}catch(e){byId('msg').textContent=f.name+': '+e.message;}}load();}
byId('login').onsubmit=e=>{e.preventDefault();pin=byId('pin').value.trim();sessionStorage.setItem('pin',pin);load().catch(e=>byId('msg').textContent=e.message);};
byId('file').onchange=e=>upload(e.target.files);
const drop=byId('drop');drop.ondragover=e=>{e.preventDefault();drop.classList.add('over');};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');upload(e.dataTransfer.files);};
if(pin)load().catch(()=>{});
</script></main></body></html>
""".trimIndent()
    }
}

/**
 * A small web server on the local network, on only while the user has it switched on.
 * Turns itself off after [IDLE_MS] without requests or [MAX_BAD_PINS] wrong PINs.
 */
class TransferServer(private val docs: TransferDocs) {
    data class State(val running: Boolean = false, val address: String? = null, val pin: String? = null, val note: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    @Volatile private var lastRequest = 0L
    @Volatile private var badPins = 0

    fun start(hostAddress: String?) {
        if (_state.value.running) return
        val pin = (SecureRandom().nextInt(900_000) + 100_000).toString()
        val server = PORTS.firstNotNullOfOrNull { port -> runCatching { ServerSocket(port, 8, InetAddress.getByName("0.0.0.0")) }.getOrNull() }
        if (server == null) {
            _state.value = State(note = "Couldn't start: ports ${PORTS.first}–${PORTS.last} are in use")
            return
        }
        socket = server
        badPins = 0
        lastRequest = System.currentTimeMillis()
        val handler = TransferHandler(docs, pin) {
            if (++badPins >= MAX_BAD_PINS) stop("Turned off after $MAX_BAD_PINS wrong PINs")
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        _state.value = State(true, hostAddress?.let { "http://$it:${server.localPort}" }, pin)

        s.launch {
            while (isActive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { serve(client, handler) }
            }
        }
        s.launch {
            while (isActive) {
                delay(30_000)
                if (System.currentTimeMillis() - lastRequest > IDLE_MS) stop("Turned off after 15 minutes without use")
            }
        }
    }

    private suspend fun serve(client: Socket, handler: TransferHandler) = client.use {
        it.soTimeout = 20_000
        lastRequest = System.currentTimeMillis()
        val req = runCatching { HttpRequest.read(it.getInputStream()) }.getOrNull()
        val response = if (req == null) HttpResponse.text(413, "Request too large or malformed") else handler.handle(req)
        runCatching { response.write(it.getOutputStream()) }
    }

    fun stop(note: String? = null) {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
        _state.value = State(note = note)
    }

    companion object {
        val PORTS = 8765..8775
        const val IDLE_MS = 15 * 60 * 1000L
        const val MAX_BAD_PINS = 5
    }
}
