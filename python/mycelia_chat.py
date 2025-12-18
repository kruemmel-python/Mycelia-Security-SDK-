"""Tkinter chat client using Mycelia GPU encryption."""
import socket
import sys
import threading
import tkinter as tk
from tkinter import messagebox, scrolledtext

from mycelia_chat_engine import MyceliaChatEngine

SERVER_IP = "127.0.0.1"
PORT = 5555


class ChatClient:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Mycelia Encrypted Chat (GPU)")
        self.root.configure(bg="#121212")

        try:
            self.engine = MyceliaChatEngine(0)
        except Exception as exc:
            messagebox.showerror("GPU Error", str(exc))
            sys.exit(1)

        self.txt_area = scrolledtext.ScrolledText(
            root,
            bg="#1e1e1e",
            fg="#00ff99",
            font=("Consolas", 10),
        )
        self.txt_area.pack(padx=10, pady=10, fill="both", expand=True)
        self.txt_area.config(state="disabled")

        self.entry_msg = tk.Entry(root, bg="#333", fg="#fff", font=("Segoe UI", 12))
        self.entry_msg.pack(padx=10, pady=10, fill="x")
        self.entry_msg.bind("<Return>", self.send_msg)

        self.client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            self.client.connect((SERVER_IP, PORT))
            threading.Thread(target=self.receive_msg, daemon=True).start()
            self.log_ui("[System] Verbunden mit dem Mycelium.")
        except OSError:
            self.log_ui("[System] Server nicht gefunden.")

    def log_ui(self, text: str) -> None:
        self.txt_area.config(state="normal")
        self.txt_area.insert("end", text + "\n")
        self.txt_area.yview("end")
        self.txt_area.config(state="disabled")

    def send_msg(self, event=None) -> None:
        msg = self.entry_msg.get()
        if not msg:
            return

        self.log_ui(f"Me: {msg}")
        self.entry_msg.delete(0, "end")

        encrypted_packet = self.engine.encrypt_text(msg)

        try:
            self.client.send(encrypted_packet)
        except OSError:
            self.log_ui("[System] Senden fehlgeschlagen.")

    def receive_msg(self) -> None:
        while True:
            try:
                packet = self.client.recv(4096)
                if not packet:
                    break

                clear_text = self.engine.decrypt_packet(packet)
                self.log_ui(f"Peer: {clear_text}")
            except OSError:
                break
            except Exception as exc:  # pragma: no cover - UI logging path
                print(exc)
                break


if __name__ == "__main__":
    root = tk.Tk()
    app = ChatClient(root)
    root.mainloop()

