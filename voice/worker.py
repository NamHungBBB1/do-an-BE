"""Máy sinh giọng FinTeen (GitHub Actions, workflow voice-render.yml — Hưng chốt 24/10, làm 03/11).

Lấy việc PENDING từ BE → sinh bằng VieNeu-TTS (đúng engine / model / giọng của src/main/resources/voice/voices.json) → nộp mp3.
BE đã tính sẵn khoá (hash) và chữ đọc; worker KHÔNG tự chuẩn hoá lại, chỉ sinh đúng readText được giao. Câu trùng khoá
không bao giờ tới đây (BE dùng lại tệp cũ).

Máy TRỰC SẴN (03/11, học từ clawd-tts của Hưng): dựng + nạp model MỘT lần, rồi hỏi việc mỗi 2 s trong VOICE_IDLE_MIN phút
(mặc định 20) kể từ việc cuối → câu bấm sau chỉ tốn thời gian sinh (~4 s). Trước khi tắt báo BE (/leaving) để lần bấm sau
dựng máy mới; còn việc thì ở lại. mp3 mã hoá bằng lameenc (pip) — không apt ffmpeg.

Biến môi trường: FINTEEN_API (vd https://kidzeconomy.com.vn/finteen), VOICE_WORKER_KEY (GitHub Secret), VOICE_IDLE_MIN.
Log công khai (repo public): chỉ in khoá + giọng + thời lượng, không in khoá bí mật.
Chạy tay (dự phòng, máy có vieneu + ffmpeg): FINTEEN_API=... VOICE_WORKER_KEY=... python voice/worker.py
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG = HERE.parent / "src/main/resources/voice/voices.json"
API = os.environ["FINTEEN_API"].rstrip("/")
KEY = os.environ["VOICE_WORKER_KEY"].strip()
UA = "finteen-voice-worker/1"   # Cloudflare chặn User-Agent mặc định của urllib (403)


def call(method: str, path: str, body: bytes | None = None, ctype: str | None = None) -> bytes:
    req = urllib.request.Request(API + path, data=body, method=method,
                                 headers={"X-Voice-Worker-Key": KEY, "User-Agent": UA, **({"Content-Type": ctype} if ctype else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


IDLE_SEC = float(os.environ.get("VOICE_IDLE_MIN", "20")) * 60
POLL_SEC = 2


def jobs() -> list[dict]:
    return json.loads(call("GET", "/api/voice-worker/jobs"))["result"]


def submit(key: str, mp3: Path, seconds: float) -> None:
    b = uuid.uuid4().hex
    parts = [f"--{b}\r\nContent-Disposition: form-data; name=\"seconds\"\r\n\r\n{seconds:.2f}\r\n".encode(),
             f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{key}.mp3\"\r\n"
             f"Content-Type: audio/mpeg\r\n\r\n".encode() + mp3.read_bytes() + b"\r\n",
             f"--{b}--\r\n".encode()]
    call("POST", f"/api/voice-worker/clips/{key}", b"".join(parts), f"multipart/form-data; boundary={b}")


def fail(key: str, error: str) -> None:
    try:
        call("POST", f"/api/voice-worker/clips/{key}/failed", json.dumps({"error": error[:250]}).encode(), "application/json")
    except urllib.error.URLError as e:
        print(f"  không báo lỗi được cho {key}: {e}", flush=True)


class Engine:
    """VieNeu nạp một lần; giọng nhân bản tải tệp mẫu từ BE (không nằm trong repo công khai)."""

    def __init__(self, cfg: dict, need: set[str]):
        from vieneu import Vieneu
        t0 = time.time()
        self.tts = Vieneu()
        self.names = {}
        self.tmp = Path(tempfile.mkdtemp())
        for pid in need:
            p = cfg["profiles"][pid]
            if p["kind"] == "clone":
                ref = self.tmp / f"{pid}.wav"
                ref.write_bytes(call("GET", f"/api/voice-worker/refs/{pid}"))
                self.tts.add_voice(pid, str(ref))
                self.names[pid] = pid
            else:
                self.names[pid] = p["preset"]
        print(f"nạp model + {len(need)} giọng: {time.time() - t0:.0f}s", flush=True)

    def render(self, text: str, pid: str, out_mp3: Path) -> float:
        wav = self.tmp / "clip.wav"
        self.tts.save(self.tts.infer(text=text, voice=self.names[pid], temperature=0.8), str(wav))
        with wave.open(str(wav)) as w:
            rate, channels, width, frames = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.readframes(w.getnframes())
        if width != 2:
            raise ValueError(f"wav {width * 8}-bit, cần 16-bit")
        import lameenc
        enc = lameenc.Encoder()
        enc.set_bit_rate(64)
        enc.set_in_sample_rate(rate)
        enc.set_channels(channels)
        enc.set_quality(2)
        out_mp3.write_bytes(enc.encode(frames) + enc.flush())
        return len(frames) / (rate * channels * width)


def main() -> int:
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    engine = Engine(cfg, set(cfg["profiles"]))     # nạp trước, kể cả khi chưa có việc → câu đầu cũng nhanh
    done = failed = fails_in_row = 0
    last_work = time.time()
    while True:
        try:
            todo = [j for j in jobs() if j["profile"] in cfg["profiles"]]
        except (urllib.error.URLError, TimeoutError) as e:   # BE chập chờn → thử lại, không tắt máy
            print(f"  hỏi việc lỗi: {e}", flush=True)
            todo = []
        if not todo:
            if time.time() - last_work >= IDLE_SEC:
                left = json.loads(call("POST", "/api/voice-worker/leaving", b"", "application/json"))["result"]
                if not left:
                    break
                last_work = time.time()               # việc vừa được xếp lúc định tắt → ở lại
            time.sleep(POLL_SEC)
            continue
        print(f"{len(todo)} câu cần sinh", flush=True)
        for j in todo:
            out = engine.tmp / f"{j['key']}.mp3"
            t0 = time.time()
            try:
                sec = engine.render(j["readText"], j["profile"], out)
                submit(j["key"], out, sec)
                done += 1
                fails_in_row = 0
                print(f"  {j['key']} {j['profile']:<12} {sec:5.1f}s audio, {time.time() - t0:4.1f}s", flush=True)
            except Exception as e:           # một câu hỏng → báo FAILED, đi tiếp; 3 câu liền hỏng = hệ thống hỏng → dừng
                failed += 1
                fails_in_row += 1
                print(f"  LỖI {j['key']}: {type(e).__name__}: {e}", flush=True)
                fail(j["key"], f"{type(e).__name__}: {e}")
                if fails_in_row >= 3:
                    call("POST", "/api/voice-worker/leaving", b"", "application/json")
                    print("Dừng: 3 câu lỗi liên tiếp.")
                    return 1
        last_work = time.time()
    print(f"hết giờ trực: sinh {done}, lỗi {failed}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
