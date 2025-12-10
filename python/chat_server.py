"""Minimal TCP relay server for Mycelia Chat packets."""
import socket
import threading

HOST = "0.0.0.0"
PORT = 5555

clients = []


def broadcast(message: bytes, source_client: socket.socket) -> None:
    for client in list(clients):
        if client is source_client:
            continue
        try:
            client.send(message)
        except OSError:
            try:
                clients.remove(client)
            except ValueError:
                pass


def handle_client(client: socket.socket) -> None:
    while True:
        try:
            message = client.recv(4096)
            if not message:
                break
            broadcast(message, client)
        except OSError:
            break
    try:
        clients.remove(client)
    except ValueError:
        pass
    client.close()


def start_server() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen()
    print(f"[Server] Listening on {HOST}:{PORT}")

    while True:
        client, addr = server.accept()
        print(f"[Server] Connected with {addr}")
        clients.append(client)
        thread = threading.Thread(target=handle_client, args=(client,), daemon=True)
        thread.start()


if __name__ == "__main__":
    start_server()

