#!/usr/bin/env python3
"""A Sendspin server for the speaker's end-to-end test (aiosendspin 10.2.0, as Music Assistant 2.10.7).

It waits for one client to connect to ws://127.0.0.1:PORT/sendspin, lets it in the way Music
Assistant does (approval of an unpaired client, or pairing with its token), plays it a counter
signal (every sample is its frame number, so the client can check each one) and runs the steps
asked for: player commands, a clear, the end of the stream. Each line on stdout is one event,
"EVENT key=value ..." or "EVENT {json}"; aiosendspin's own log goes to stderr.

    python3 -m venv venv && venv/bin/pip install 'aiosendspin[server]==10.2.0'
    venv/bin/python -I ci/sendspin_server.py --port 0 --play-seconds 3 --volume-command 30 --end

A few private methods of aiosendspin are wrapped to report what the client says, so the
script belongs to that one version.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import signal
import sys
import time
from importlib.metadata import version

import numpy as np
from aiohttp import web
from aiosendspin.models.types import PairMethod
from aiosendspin.noise import EncryptedWebSocket, Identity, InMemoryServerPairingStore, decode_psk_token
from aiosendspin.noise.pairing import PairingAttempt
from aiosendspin.server import AudioFormat, ClientConnectedEvent, ClientDisconnectedEvent, SendspinServer
from aiosendspin.server.connection import SendspinConnection
from aiosendspin.server.roles.player.v1 import PlayerV1Role

AIOSENDSPIN = "10.2.0"
RATE, CHANNELS = 48_000, 2
SENT = {"chunks": 0}


def emit(event: str, *args: object, **fields: object) -> None:
    """One event per line: EVENT [json] key=value ..."""
    parts = [event, *(json.dumps(a, separators=(",", ":")) for a in args)]
    parts += [f"{k}={v}" for k, v in fields.items()]
    print(" ".join(parts), flush=True)


def report_client_state() -> None:
    """Every client/state the server decrypts, and every audio chunk it sends."""
    dispatch = EncryptedWebSocket._dispatch  # noqa: SLF001

    def tapped(self, plaintext: bytes):  # type: ignore[no-untyped-def]
        if plaintext[:1] == b"\x00":
            msg = json.loads(plaintext[1:])
            if msg.get("type") == "client/state":
                emit("CLIENT_STATE", msg.get("payload"))
        return dispatch(self, plaintext)

    EncryptedWebSocket._dispatch = tapped  # noqa: SLF001

    send_binary = SendspinConnection._send_binary_data  # noqa: SLF001

    async def counted(self, wsock, role, entry, buffer_tracker):  # type: ignore[no-untyped-def]
        await send_binary(self, wsock, role, entry, buffer_tracker)
        if entry.binary is not None and entry.binary.message_type == 4:
            SENT["chunks"] += 1

    SendspinConnection._send_binary_data = counted  # noqa: SLF001


def counter(start: int, count: int) -> bytes:
    """Stereo 16-bit PCM whose every sample is its frame number (modulo 65536)."""
    frames = np.arange(start, start + count, dtype=np.int64)
    return np.repeat((frames & 0xFFFF).astype("<u2"), CHANNELS).tobytes()


async def wait_for(predicate, timeout_s: float, what: str) -> None:  # type: ignore[no-untyped-def]
    deadline = time.monotonic() + timeout_s
    while not predicate():
        if time.monotonic() > deadline:
            raise TimeoutError(what)
        await asyncio.sleep(0.02)


def player(client) -> PlayerV1Role | None:  # type: ignore[no-untyped-def]
    role = client.role("player@v1")
    return role if isinstance(role, PlayerV1Role) else None


async def scenario(args: argparse.Namespace, server: SendspinServer, connected: asyncio.Future) -> None:
    client_id = await asyncio.wait_for(connected, args.timeout)
    client = server.get_client(client_id)
    if args.pair_token:
        token = decode_psk_token(args.pair_token)
        await server.initiate_pairing(client_id, PairingAttempt(
            PairMethod.PAIRING_PSK, client_id=token.client_id, pairing_psk=token.pairing_psk))
        emit("PAIRED", client_id=client_id)
    else:
        # What Music Assistant does for a client that offers unpaired access.
        await server.trust_unpaired(client_id)
        emit("APPROVED", client_id=client_id)
    await wait_for(lambda: client.is_connected and client.available and player(client) is not None
                   and not client.awaits_role_state("player"), args.timeout, "player ready")
    role = player(client)
    emit("PLAYER_READY", client_id=client_id, min_buffer_ms=role.min_buffer_ms,
         required_lead_time_ms=role.required_lead_time_ms, output_delay_ms=role.output_delay_ms)
    if args.play_seconds <= 0:
        return

    push = client.group.start_stream()
    emit("STREAM_STARTED")
    frame = 0
    t0 = time.monotonic()
    steps = [(args.command_at, "volume", args.volume_command),
             (args.command_at, "mute", True if args.mute_command else None),
             (args.command_at + 0.5, "unmute", True if args.mute_command else None),
             (args.command_at, "set_output_delay", args.output_delay_command),
             (args.clear_after, "clear", True if args.clear_after is not None else None)]
    steps = sorted((s for s in steps if s[0] is not None and s[2] is not None), key=lambda s: s[0])
    while time.monotonic() - t0 < args.play_seconds:
        while steps and steps[0][0] <= time.monotonic() - t0:
            _, name, value = steps.pop(0)
            if name == "clear":
                push.clear()
                emit("STREAM_CLEARED", next_frame=frame)
            elif name == "volume":
                role.set_volume(int(value))
                emit("COMMAND", command=name, value=value)
            elif name in ("mute", "unmute"):
                role.set_mute(name == "mute")
                emit("COMMAND", command="mute", value=str(name == "mute").lower())
            else:
                role.set_output_delay(int(value))
                emit("COMMAND", command=name, value=value)
        push.prepare_audio(counter(frame, RATE // 10), AudioFormat(RATE, 16, CHANNELS))
        frame += RATE // 10
        await push.commit_audio()
        await push.sleep_to_limit_buffer(1_000_000)
    if args.end:
        # Let the client play what it holds, then end the stream.
        end_us = max(push._channel_timing.values(), default=push.now_us())  # noqa: SLF001
        await asyncio.sleep(max(0, end_us - push.now_us()) / 1e6 + 0.2)
        client.group.stop_stream()
        emit("STREAM_ENDED")
    emit("SENT_CHUNKS", n=SENT["chunks"], frames=frame)


async def run(args: argparse.Namespace) -> int:
    loop = asyncio.get_running_loop()
    server = SendspinServer(loop, Identity.generate(), "Klangbild test server",
                            pairing_store=InMemoryServerPairingStore(), allow_unencrypted=False,
                            allow_noncompliant_clients=False)
    connected: asyncio.Future = loop.create_future()

    def on_event(_server, event) -> None:  # type: ignore[no-untyped-def]
        if isinstance(event, ClientConnectedEvent):
            emit("CLIENT_CONNECTED", client_id=event.client_id)
            if not connected.done():
                connected.set_result(event.client_id)
        elif isinstance(event, ClientDisconnectedEvent):
            reason = event.goodbye_reason.value if event.goodbye_reason else None
            emit("CLIENT_DISCONNECTED", goodbye_reason=reason)

    server.add_event_listener(on_event)
    # Its own listener without mDNS: start_server() would advertise the server on the network.
    app = web.Application()
    app.router.add_get(SendspinServer.API_PATH, server.on_client_connect)
    runner = web.AppRunner(app, handle_signals=False)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", args.port).start()
    emit("READY", port=runner.addresses[0][1], server_id=server.id)

    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    code = 0
    try:
        await asyncio.wait_for(scenario(args, server, connected), args.timeout + args.play_seconds + 30)
        # Keep the session (time sync, state) until the client leaves or the time is up.
        await asyncio.wait({asyncio.ensure_future(stop.wait())}, timeout=args.linger)
    except (TimeoutError, asyncio.TimeoutError) as exc:
        emit("ERROR", kind="timeout", detail=json.dumps(str(exc)))
        code = 2
    except Exception as exc:  # noqa: BLE001
        emit("ERROR", kind=type(exc).__name__, detail=json.dumps(str(exc)))
        code = 3
    await server.close()
    await runner.cleanup()
    emit("DONE", exit_code=code)
    return code


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--port", type=int, default=0, help="0 picks a free port (see READY)")
    p.add_argument("--pair-token", help="pair the client with its token (SP:0...) instead of approving it")
    p.add_argument("--timeout", type=float, default=30.0, help="seconds to wait for the client")
    p.add_argument("--play-seconds", type=float, default=0.0, help="seconds of audio to produce")
    p.add_argument("--command-at", type=float, default=1.0, help="seconds after the start")
    p.add_argument("--volume-command", type=int)
    p.add_argument("--mute-command", action="store_true", help="mute, and unmute 0.5 s later")
    p.add_argument("--output-delay-command", type=int, help="set_output_delay in ms")
    p.add_argument("--clear-after", type=float, help="stream/clear this many seconds after the start")
    p.add_argument("--end", action="store_true", help="stream/end once the client has played everything")
    p.add_argument("--linger", type=float, default=30.0, help="seconds to keep the session afterwards")
    args = p.parse_args()
    logging.basicConfig(level=logging.WARNING, stream=sys.stderr)
    if version("aiosendspin") != AIOSENDSPIN:
        emit("ERROR", kind="version", detail=json.dumps(f"needs aiosendspin {AIOSENDSPIN}"))
        return 3
    report_client_state()
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
