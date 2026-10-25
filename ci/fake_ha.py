#!/usr/bin/env python3
"""A small stand-in for Home Assistant for the emulator smoke test (standard library only).

Speaks just enough of Home Assistant's documented APIs for Klangbild:
  WebSocket: auth, subscribe_trigger with a state trigger (the speaker, sun.sun and, in a
    second subscription, the other speakers of its group), unsubscribe_events, call_service
    (music_assistant.get_queue and the media keys), get_config (its time zone and units),
    config/entity_registry/list_for_display (the speakers that belong to Music Assistant) and
    ping.
  REST: GET /api/states/<entity_id> with the access token.
It serves the cover as a PNG. 40 seconds after the app subscribed the next track starts, so
the smoke test also runs through a track change. The emulator reaches the host at 10.0.2.2.

    python3 ci/fake_ha.py [port]
"""
import asyncio
import base64
import datetime
import hashlib
import json
import struct
import sys
import time
import zlib

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8123
TOKEN = "smoke"
SPEAKER = "media_player.living_room"
OTHERS = ["media_player.kitchen", "media_player.bath", "media_player.office", "media_player.hall", "media_player.terrace"]
NAMES = {SPEAKER: "Living room", "media_player.kitchen": "Kitchen", "media_player.bath": "Bath",
         "media_player.office": "Office", "media_player.hall": "Hall", "media_player.terrace": "Terrace"}
TRACKS = [
    ("Neonlicht über der A40", "Lumen Drive", "Nachtfahrt", 251, (40, 20, 90), (240, 90, 120)),
    ("Spiegel im Spiegel", "Arvo Pärt", "Alina", 562, (20, 40, 60), (200, 210, 220)),
]
# Pause, seek, volume (set and step), previous, next, play, shuffle.
FEATURES = 1 | 2 | 4 | 16 | 32 | 1024 | 16384 | 32768
started = time.time()
first_subscription = None
SWITCH_AFTER = 40


def png(size, top, bottom):
    """A vertical two-color gradient as a PNG."""
    rows = bytearray()
    for y in range(size):
        t = y / (size - 1)
        r, g, b = (int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        rows += b"\x00" + bytes((r, g, b)) * size

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    head = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head) + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b"")


def current():
    if first_subscription is None:
        return 0
    return 0 if time.time() - first_subscription < SWITCH_AFTER else 1


def iso(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat()


def state(entity_id, value, attributes, t):
    """A state object as the REST API and state triggers carry it."""
    return {"entity_id": entity_id, "state": value, "attributes": attributes,
            "last_changed": iso(t), "last_updated": iso(t),
            "context": {"id": f"ctx-{entity_id}-{int(t)}", "parent_id": None, "user_id": None}}


def speaker_state():
    i = current()
    title, artist, album, duration, _, _ = TRACKS[i]
    base = first_subscription or started
    t0 = base if i == 0 else base + SWITCH_AFTER
    return state(SPEAKER, "playing", {
        "friendly_name": NAMES[SPEAKER],
        "media_title": title, "media_artist": artist, "media_album_name": album,
        "media_duration": duration, "media_position": 0, "media_position_updated_at": iso(t0),
        "entity_picture": f"/local/cover{i}.png",
        "app_id": "music_assistant", "mass_player_type": "player", "active_queue": "living_room",
        "group_members": [SPEAKER] + OTHERS, "volume_level": 0.35, "is_volume_muted": False,
        "supported_features": FEATURES,
    }, t0)


def states():
    """Every entity this Home Assistant has."""
    out = {SPEAKER: speaker_state(),
           "sun.sun": state("sun.sun", "above_horizon", {"elevation": 32.0, "friendly_name": "Sun"}, started)}
    for e in OTHERS:
        out[e] = state(e, "playing", {"friendly_name": NAMES[e], "mass_player_type": "player",
                                      "supported_features": FEATURES}, started)
    return out


def queue():
    i = current()
    nxt = TRACKS[(i + 1) % len(TRACKS)]
    item = lambda tr: {"name": tr[0], "duration": tr[3], "media_item": {
        "media_type": "track", "name": tr[0], "artists": [{"name": tr[1]}], "album": {"name": tr[2]}}}
    return {SPEAKER: {"queue_id": "living_room", "active": True, "name": "Living room", "items": 2,
                      "current_item": item(TRACKS[i]), "next_item": item(nxt)}}


def registry():
    """The entity registry as listed for display: the speakers belong to Music Assistant."""
    entities = [{"ei": e, "pl": "music_assistant", "di": f"device-{e}"} for e in [SPEAKER] + OTHERS]
    entities += [{"ei": "sun.sun", "pl": "sun"}]
    return {"entity_categories": {}, "entities": entities}


async def send(writer, text):
    data = text.encode()
    n = len(data)
    if n < 126:
        header = struct.pack("!BB", 0x81, n)
    elif n < 65536:
        header = struct.pack("!BBH", 0x81, 126, n)
    else:
        header = struct.pack("!BBQ", 0x81, 127, n)
    writer.write(header + data)
    await writer.drain()


async def receive(reader):
    while True:
        b1, b2 = await reader.readexactly(2)
        opcode, n = b1 & 0x0F, b2 & 0x7F
        if n == 126:
            n = struct.unpack("!H", await reader.readexactly(2))[0]
        elif n == 127:
            n = struct.unpack("!Q", await reader.readexactly(8))[0]
        mask = await reader.readexactly(4) if b2 & 0x80 else b"\0\0\0\0"
        payload = bytes(c ^ mask[i % 4] for i, c in enumerate(await reader.readexactly(n)))
        if opcode == 8:
            return None
        if opcode == 1:
            return payload.decode()


def result(mid, value=None):
    return json.dumps({"id": mid, "type": "result", "success": True, "result": value})


async def session(reader, writer):
    await send(writer, json.dumps({"type": "auth_required", "ha_version": "2026.10.0"}))
    main_sub = None
    shown = current()

    async def changes():
        nonlocal shown
        while True:
            await asyncio.sleep(1)
            if main_sub is not None and current() != shown:
                shown = current()
                trigger = {"platform": "state", "entity_id": SPEAKER, "from_state": None, "to_state": speaker_state()}
                await send(writer, json.dumps({"id": main_sub, "type": "event",
                                               "event": {"variables": {"trigger": trigger}, "context": None}}))

    ticker = asyncio.create_task(changes())
    try:
        while True:
            text = await receive(reader)
            if text is None:
                return
            msg = json.loads(text)
            kind, mid = msg.get("type"), msg.get("id")
            print(kind, msg.get("service") or (msg.get("trigger") or {}).get("entity_id") or "", flush=True)
            if kind == "auth":
                if msg.get("access_token") == TOKEN:
                    await send(writer, json.dumps({"type": "auth_ok", "ha_version": "2026.10.0"}))
                else:
                    await send(writer, json.dumps({"type": "auth_invalid", "message": "Invalid access token or password"}))
                    return
            elif kind == "subscribe_trigger":
                ids = (msg.get("trigger") or {}).get("entity_id", [])
                await send(writer, result(mid))
                if SPEAKER in ids:
                    global first_subscription
                    first_subscription = first_subscription or time.time()
                    main_sub = mid
            elif kind == "call_service":
                value = {"context": {"id": "smoke"}}
                if msg.get("service") == "get_queue":
                    value["response"] = queue()
                await send(writer, result(mid, value))
            elif kind == "get_config":
                await send(writer, result(mid, {"time_zone": "Europe/Berlin", "unit_system": {"temperature": "°C"}}))
            elif kind == "config/entity_registry/list_for_display":
                await send(writer, result(mid, registry()))
            elif kind == "ping":
                await send(writer, json.dumps({"id": mid, "type": "pong"}))
            elif mid is not None:
                await send(writer, result(mid))
    finally:
        ticker.cancel()


async def respond(writer, status, body=b"", content_type="application/json"):
    writer.write(b"HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n"
                 % (status.encode(), content_type.encode(), len(body)) + body)
    await writer.drain()


async def handle(reader, writer):
    try:
        request = (await reader.readuntil(b"\r\n\r\n")).decode("latin-1")
        line, *headers = request.split("\r\n")
        path = line.split(" ")[1] if " " in line else "/"
        fields = {h.split(":", 1)[0].strip().lower(): h.split(":", 1)[1].strip() for h in headers if ":" in h}
        if fields.get("upgrade", "").lower() == "websocket":
            key = fields["sec-websocket-key"]
            accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            writer.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
            await writer.drain()
            await session(reader, writer)
        elif path.startswith("/api/states/"):
            print("rest", path, flush=True)
            if fields.get("authorization") != f"Bearer {TOKEN}":
                await respond(writer, "401 Unauthorized", b'{"message":"Unauthorized"}')
            else:
                found = states().get(path[len("/api/states/"):])
                if found is None:
                    await respond(writer, "404 Not Found", b'{"message":"Entity not found."}')
                else:
                    await respond(writer, "200 OK", json.dumps(found).encode())
        elif path.startswith("/local/cover"):
            i = 1 if "cover1" in path else 0
            await respond(writer, "200 OK", png(640, TRACKS[i][4], TRACKS[i][5]), "image/png")
        else:
            await respond(writer, "404 Not Found")
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    finally:
        writer.close()


async def main():
    server = await asyncio.start_server(handle, "0.0.0.0", PORT)
    print(f"fake Home Assistant on :{PORT}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
