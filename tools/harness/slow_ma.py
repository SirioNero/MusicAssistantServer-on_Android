# Un finto Music Assistant: risponde agli elenchi a pezzi lenti, come Spotify con una playlist grande.
import asyncio, json, sys
import websockets

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18095

async def handler(ws):
    await ws.send(json.dumps({"server_id": "finto", "server_version": "2.10.8", "schema_version": 63,
                              "min_supported_schema_version": 28, "base_url": f"http://127.0.0.1:{PORT}", "name": "Finto"}))
    async for text in ws:
        msg = json.loads(text)
        mid, cmd, args = msg["message_id"], msg["command"], msg.get("args", {})
        if cmd == "auth":
            await ws.send(json.dumps({"message_id": mid, "result": {"user": {"username": "prova", "role": "admin"}}}))
        elif cmd in ("slow/list", "slow/stall", "slow/abandon"):
            asyncio.create_task(slow(ws, mid, cmd, args))
        else:
            await ws.send(json.dumps({"message_id": mid, "error_code": 12, "details": "Invalid command"}))

async def slow(ws, mid, cmd, args):
    gap = float(args.get("gap", 0.8))
    parts = int(args.get("parts", 6))
    n = 0
    for p in range(parts):
        await asyncio.sleep(gap)
        chunk = [{"item_id": str(n + i)} for i in range(500)]
        n += 500
        await ws.send(json.dumps({"message_id": mid, "result": chunk, "partial": True}))
    if cmd == "slow/stall":
        return  # non finisce mai
    await asyncio.sleep(gap)
    await ws.send(json.dumps({"message_id": mid, "result": [{"item_id": str(n + i)} for i in range(100)]}))

async def main():
    async with websockets.serve(handler, "127.0.0.1", PORT, max_size=None):
        await asyncio.Future()

asyncio.run(main())
