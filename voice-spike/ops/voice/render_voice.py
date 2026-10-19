"""Sinh giọng đọc cho các cảnh thoại của chương FinTeen bằng VieNeu-TTS (phương án A, Hưng chốt 15/10/2026).

Luật (build/thiet-ke-giong-doc-2026-10-17.md):
- Cảnh có `text` và `speakerId` thì đọc; `voice: "off"` là tắt; `voice: "<profile>"` là ghi đè giọng của cảnh đó.
- Giọng gắn theo nhân vật (voices.json → characters), dùng chung mọi chương.
- Tệp đặt tên theo hash(engine | modelVersion | profile | chữ đã chuẩn hoá) → build/finteen-assets-dist/v/<hash>.mp3,
  không bao giờ ghi đè; chỉ dòng đổi chữ / đổi giọng mới sinh lại. Bản phát hành cũ vẫn trỏ tệp cũ.
- Script ghi `voiceUrl: "{{ASSET_BASE}}/v/<hash>.mp3"` vào JSON chương (build/content/CHxx.json); BE thay tiền tố lúc trả,
  không cần sửa BE. Dòng sinh lỗi thì không có voiceUrl, FE hiện chữ.

Chạy bằng Python có gói `vieneu` (máy Hưng: D:/Talk/VieNeu-TTS/.venv/Scripts/python.exe) và ffmpeg trong PATH:
    render_voice.py plan   [--only CH01]              # liệt kê dòng cần đọc, dòng còn thiếu, không nạp model
    render_voice.py render [--only CH01] [--limit N]  # sinh dòng còn thiếu, ghi voiceUrl vào JSON
    render_voice.py sample --speaker AN --profiles xuan-vinh,adam   # nghe thử một dòng dài nhất của nhân vật
                                                      # → build/voice-samples/*.mp3 + nghe-thu.html
Sau render: deploy build/finteen-assets-dist lên Cloudflare Pages rồi publish_content.py (xem thiết kế).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]                      # D:/Đồ Án
CONTENT = ROOT / "build" / "content"
DIST = ROOT / "build" / "finteen-assets-dist"
SAMPLES = ROOT / "build" / "voice-samples"
VOICES = HERE / "voices.json"
CLIPS = HERE / "clips.json"                 # sổ các tệp đã sinh: hash → nguồn gốc (hạt giống cho bảng VoiceClip sau này)
PLACEHOLDER = "{{ASSET_BASE}}"
MP3_BITRATE = "64k"

EMOJI = re.compile("[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF\u2728\u2B50\uFE0F]+")


def clean(text: str) -> str:
    """Chữ đưa cho máy đọc: bỏ emoji, dấu gạch dài / ba chấm lạ, '250.000đ' → '250.000 đồng', gộp khoảng trắng."""
    t = EMOJI.sub(" ", text)
    t = t.replace("—", ",").replace("–", ",").replace("…", "...")
    t = re.sub(r"(\d)\s*đ\b", r"\1 đồng", t)
    return re.sub(r"\s+", " ", t).strip()


def load_config() -> dict:
    cfg = json.loads(VOICES.read_text(encoding="utf-8"))
    for pid, p in cfg["profiles"].items():
        if p["kind"] == "clone":
            ref = HERE / p["ref"]
            if not ref.exists():
                sys.exit(f"profile {pid}: thiếu tệp mẫu {ref}")
    for spk, pid in cfg["characters"].items():
        if pid not in cfg["profiles"]:
            sys.exit(f"characters.{spk} trỏ profile không có: {pid}")
    if cfg.get("defaultProfile") and cfg["defaultProfile"] not in cfg["profiles"]:
        sys.exit(f"defaultProfile trỏ profile không có: {cfg['defaultProfile']}")
    return cfg


def unmapped(cfg: dict, chapter: dict) -> dict:
    """Nhân vật có thoại mà chưa gán giọng (defaultProfile null): bỏ qua, không đoán giọng — tránh nam đọc giọng nữ."""
    out: dict[str, int] = {}
    for s in chapter["scenes"]:
        if s.get("text") and s.get("speakerId") and not s.get("voice") \
                and s["speakerId"] not in cfg["characters"] and not cfg.get("defaultProfile"):
            out[s["speakerId"]] = out.get(s["speakerId"], 0) + 1
    return out


def clip_hash(cfg: dict, profile_id: str, text: str) -> str:
    raw = "|".join([cfg["engine"], cfg["modelVersion"], profile_id, text])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:20]


def profile_for(cfg: dict, scene: dict) -> str | None:
    """None = không đọc. Ghi đè theo cảnh > giọng nhân vật > giọng mặc định."""
    v = scene.get("voice")
    if v == "off":
        return None
    if v:
        if v not in cfg["profiles"]:
            sys.exit(f"{scene['id']}: voice '{v}' không có trong voices.json")
        return v
    return cfg["characters"].get(scene.get("speakerId")) or cfg.get("defaultProfile")


def spoken(cfg: dict, chapter: dict):
    for s in chapter["scenes"]:
        if s.get("text") and s.get("speakerId"):
            pid = profile_for(cfg, s)
            if pid:
                yield s, pid, clean(s["text"])


def chapters(only: str | None):
    for f in sorted(CONTENT.glob("CH*.json")):
        if only and f.stem.upper() != only.upper():
            continue
        yield f, json.loads(f.read_text(encoding="utf-8"))


def ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if not exe:
        sys.exit("không thấy ffmpeg trong PATH")
    return exe


def to_mp3(wav: Path, mp3: Path) -> None:
    mp3.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-b:a", MP3_BITRATE, str(mp3)],
                   check=True)


def wav_seconds(path: Path) -> float:
    with wave.open(str(path)) as w:
        return w.getnframes() / w.getframerate()


class Engine:
    """VieNeu nạp một lần; giọng clone đăng ký từ tệp mẫu, giọng preset gọi theo tên."""

    def __init__(self, cfg: dict):
        from vieneu import Vieneu   # import muộn: `plan` không cần model
        t0 = time.time()
        self.tts = Vieneu()
        self.cfg = cfg
        self.names = {}
        for pid, p in cfg["profiles"].items():
            if p["kind"] == "clone":
                self.tts.add_voice(pid, str(HERE / p["ref"]))
                self.names[pid] = pid
            else:
                self.names[pid] = p["preset"]
        print(f"nạp model + giọng: {time.time() - t0:.0f}s", flush=True)

    def synth(self, text: str, profile_id: str, wav: Path) -> float:
        audio = self.tts.infer(text=text, voice=self.names[profile_id], temperature=0.8)
        self.tts.save(audio, str(wav))
        return wav_seconds(wav)


def write_headers() -> None:
    """Cache 1 năm + CORS cho /v/* như /a/* (Cloudflare Pages đọc tệp _headers)."""
    h = DIST / "_headers"
    text = h.read_text(encoding="utf-8") if h.exists() else ""
    if "/v/*" not in text:
        text = text.rstrip("\n") + "\n/v/*\n  Cache-Control: public, max-age=31536000, immutable\n  Access-Control-Allow-Origin: *\n"
        h.write_text(text, encoding="utf-8")


def cmd_plan(args, cfg) -> int:
    total = missing = chars = 0
    for f, ch in chapters(args.only):
        print(f"== {f.stem} ({ch.get('title')})")
        for s, pid, text in spoken(cfg, ch):
            h = clip_hash(cfg, pid, text)
            have = (DIST / "v" / f"{h}.mp3").exists()
            total += 1; chars += len(text); missing += not have
            print(f"  {'có ' if have else 'THIẾU'} {s['id']:<22} {s['speakerId']:<8} {pid:<12} {h}  {len(text):>4} ký tự")
        for spk, n in unmapped(cfg, ch).items():
            print(f"  CHƯA GÁN GIỌNG: {spk} ({n} dòng) → thêm vào characters trong voices.json")
    print(f"\n{total} dòng, {chars} ký tự, còn thiếu {missing}")
    return 0


def cmd_render(args, cfg) -> int:
    clips = json.loads(CLIPS.read_text(encoding="utf-8")) if CLIPS.exists() else {}
    engine = None
    done = fails_in_row = 0
    for f, ch in chapters(args.only):
        changed = False
        for spk, n in unmapped(cfg, ch).items():
            print(f"  {f.stem}: bỏ qua {n} dòng của {spk} vì chưa gán giọng")
        for s, pid, text in spoken(cfg, ch):
            h = clip_hash(cfg, pid, text)
            mp3 = DIST / "v" / f"{h}.mp3"
            url = f"{PLACEHOLDER}/v/{h}.mp3"
            if not mp3.exists():
                if args.limit and done >= args.limit:
                    continue
                engine = engine or Engine(cfg)
                with tempfile.TemporaryDirectory() as tmp:
                    wav = Path(tmp) / "clip.wav"
                    t0 = time.time()
                    try:
                        sec = engine.synth(text, pid, wav)
                        to_mp3(wav, mp3)
                    except Exception as exc:   # dòng lỗi: bỏ voiceUrl, đi tiếp; hai lỗi liền là hỏng hệ thống → dừng
                        print(f"  LỖI {f.stem} {s['id']} {pid}: {type(exc).__name__}: {exc}", flush=True)
                        fails_in_row += 1
                        if s.pop("voiceUrl", None) is not None:
                            changed = True
                        if fails_in_row >= 2:
                            print("Dừng: 2 dòng lỗi liên tiếp.")
                            break
                        continue
                fails_in_row = 0
                done += 1
                clips[h] = {"chapter": f.stem, "sceneId": s["id"], "speakerId": s["speakerId"], "profile": pid,
                            "modelVersion": cfg["modelVersion"], "text": text, "sec": round(sec, 2),
                            "renderedAt": time.strftime("%Y-%m-%dT%H:%M:%S")}
                CLIPS.write_text(json.dumps(clips, ensure_ascii=False, indent=1), encoding="utf-8")
                print(f"  {f.stem} {s['id']:<22} {pid:<12} {sec:5.1f}s audio, {time.time() - t0:4.0f}s render", flush=True)
            if s.get("voiceUrl") != url:
                s["voiceUrl"] = url
                changed = True
        # cảnh tắt giọng / không còn text thì gỡ voiceUrl cũ
        for s in ch["scenes"]:
            if "voiceUrl" in s and profile_for(cfg, s) is None:
                del s["voiceUrl"]; changed = True
        if changed:
            f.write_text(json.dumps(ch, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
            print(f"  ghi {f}")
        if fails_in_row >= 2:
            return 1
    write_headers()
    print(f"\nsinh mới {done} tệp; tổng tệp trong {DIST / 'v'}: {len(list((DIST / 'v').glob('*.mp3')))}")
    return 0


def cmd_sample(args, cfg) -> int:
    """Một dòng dài nhất của nhân vật, đọc bằng từng profile để Hưng nghe chọn. Không đụng JSON chương."""
    if args.text:
        line = {"id": "custom", "speakerId": args.speaker or "?", "text": args.text}
    else:
        cands = [s for _, ch in chapters(None) for s in ch["scenes"]
                 if s.get("text") and s.get("speakerId") == args.speaker]
        if not cands:
            sys.exit(f"không có dòng nào của {args.speaker}")
        line = max(cands, key=lambda s: len(s["text"]))
    text = clean(line["text"])
    profiles = [p.strip() for p in args.profiles.split(",") if p.strip()]
    for p in profiles:
        if p not in cfg["profiles"]:
            sys.exit(f"profile không có: {p}")
    SAMPLES.mkdir(parents=True, exist_ok=True)
    engine = Engine(cfg)
    index_path = SAMPLES / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else []
    for p in profiles:
        name = f"{line['speakerId']}--{p}--{line['id']}.mp3"
        with tempfile.TemporaryDirectory() as tmp:
            wav = Path(tmp) / "s.wav"
            t0 = time.time()
            sec = engine.synth(text, p, wav)
            to_mp3(wav, SAMPLES / name)
        print(f"  {line['speakerId']} {p:<12} {sec:5.1f}s audio, {time.time() - t0:4.0f}s render → {name}", flush=True)
        index = [i for i in index if i["file"] != name]
        index.append({"speaker": line["speakerId"], "profile": p, "label": cfg["profiles"][p]["label"],
                      "sceneId": line["id"], "text": text, "sec": round(sec, 1), "file": name,
                      "isDefault": cfg["characters"].get(line["speakerId"]) == p})
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    write_listen_page(index)
    return 0


def write_listen_page(index: list) -> None:
    """Trang nghe thử tại chỗ (build/, mở bằng trình duyệt; không xuất bản đâu cả)."""
    by_spk: dict[str, list] = {}
    for i in index:
        by_spk.setdefault(i["speaker"], []).append(i)
    parts = ["<!doctype html><html lang='vi'><meta charset='utf-8'><title>Nghe thử giọng FinTeen</title>",
             "<style>body{font-family:Segoe UI,Arial;margin:24px;max-width:900px}h2{margin-top:28px}"
             ".line{color:#444;margin:4px 0 10px}.row{display:flex;gap:12px;align-items:center;margin:6px 0}"
             ".row b{min-width:150px}.def{color:#0a7}</style>",
             "<h1>Nghe thử giọng đọc (VieNeu-TTS v3 Turbo)</h1>",
             "<p>Mỗi nhân vật một dòng dài nhất, đọc bằng vài giọng; dòng đánh dấu ✔ là giọng đang gán mặc định trong ops/voice/voices.json.</p>"]
    for spk, items in by_spk.items():
        parts.append(f"<h2>{spk}</h2><div class='line'>“{items[0]['text']}”</div>")
        for i in items:
            mark = " <span class='def'>✔ mặc định</span>" if i["isDefault"] else ""
            parts.append(f"<div class='row'><b>{i['profile']}</b><span>{i['label']} · {i['sec']}s{mark}</span>"
                         f"<audio controls preload='none' src='{i['file']}'></audio></div>")
    (SAMPLES / "nghe-thu.html").write_text("\n".join(parts), encoding="utf-8")


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan"); p.add_argument("--only")
    p = sub.add_parser("render"); p.add_argument("--only"); p.add_argument("--limit", type=int, default=0)
    p = sub.add_parser("sample"); p.add_argument("--speaker"); p.add_argument("--text")
    p.add_argument("--profiles", required=True, help="danh sách profile, phẩy ngăn cách")
    args = ap.parse_args()
    cfg = load_config()
    return {"plan": cmd_plan, "render": cmd_render, "sample": cmd_sample}[args.cmd](args, cfg)


if __name__ == "__main__":
    raise SystemExit(main())
