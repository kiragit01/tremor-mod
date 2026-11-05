"""A TCP proxy that delays everything both ways by the same time (tools/lag-test.sh: a client playing over a slow
network, the user's friends on a LAN). Usage: lag-proxy.py <listen port> <target host> <target port> <delay ms>"""
import asyncio
import sys
import time

LISTEN, HOST, PORT, DELAY = int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), float(sys.argv[4]) / 1000


async def pipe(reader, writer):
    """Passes what comes from reader to writer DELAY seconds later, in order."""
    queue = asyncio.Queue()

    async def feed():
        while True:
            data = await reader.read(65536)
            await queue.put((time.monotonic() + DELAY, data))
            if not data:
                return

    async def drain():
        while True:
            due, data = await queue.get()
            if due > time.monotonic():
                await asyncio.sleep(due - time.monotonic())
            if not data:
                writer.close()
                return
            writer.write(data)
            await writer.drain()

    await asyncio.gather(feed(), drain())


async def handle(client_reader, client_writer):
    server_reader, server_writer = await asyncio.open_connection(HOST, PORT)
    await asyncio.gather(pipe(client_reader, server_writer), pipe(server_reader, client_writer),
                         return_exceptions=True)


async def main():
    server = await asyncio.start_server(handle, '127.0.0.1', LISTEN)
    print('lag proxy: %d -> %s:%d, %d ms each way' % (LISTEN, HOST, PORT, DELAY * 1000), flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
