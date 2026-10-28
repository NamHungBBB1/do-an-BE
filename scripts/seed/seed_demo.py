"""Seed demo data into a FRESH FinTeen BE through the public HTTP API only (no direct DB writes).

Usage:
    python scripts/seed/seed_demo.py --base http://localhost:8080 --log be.log

See scripts/seed/README.md. Runs only against a fresh DB (H2 in-memory: restart the BE to reseed);
refuses production and any non-localhost host unless --allow-host names it.
"""
import argparse
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHAPTERS_DIR = REPO / "src" / "test" / "resources" / "content"   # CH01–CH03, same files ContentSchemaTest validates
DOMAIN = "demo.test"   # RFC 2606 reserved: mail can never reach a real person
STAFF = [("admin", "Demo Admin", None), ("editor", "Demo Editor", "GAME_EDITOR"),
         ("reviewer", "Demo Reviewer", "GAME_REVIEWER"), ("manager", "Demo Manager", "GAME_MANAGER")]
DEMO_QUESTIONS = [  # upsert by externalId; pool DEMO keeps them apart from the real bank
    ("DEMO-Q1", "Khoản nào là NHU CẦU?", ["Vé xe buýt đi học", "Tai nghe đời mới", "Đồ trang trí bàn"], 0),
    ("DEMO-Q2", "Tiết kiệm 50.000đ mỗi tuần thì sau 4 tuần có bao nhiêu?", ["150.000đ", "200.000đ", "250.000đ"], 1),
    ("DEMO-Q3", "Trước khi mua một món đắt tiền, nên làm gì trước?", ["Mua ngay kẻo hết", "So sánh giá và đọc điều khoản"], 1),
]


def guard_host(base, allow_host):
    host = (urllib.parse.urlparse(base).hostname or "").lower()
    if "kidzeconomy" in base.lower():
        sys.exit("TỪ CHỐI: đây là production. Script này chỉ dành cho máy dev / staging.")
    if host not in ("localhost", "127.0.0.1", "::1") and host != (allow_host or "").lower():
        sys.exit(f"TỪ CHỐI: host '{host}' không phải localhost. Chủ đích chạy lên đó thì thêm --allow-host {host}")


def call(base, method, path, token=None, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "finteen-seed/1.0")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def must(step, status_res):
    status, res = status_res
    if status != 200 or res.get("code") != 0:
        sys.exit(f"[HỎNG] {step}: HTTP {status} {res}")
    return res.get("result")


def read_otp(log_path, email):
    """MAIL_HOST empty -> MailService prints the mail between '=== MAIL' and '=== hết'. No log -> ask (real inbox)."""
    if not log_path:
        return input(f"Nhập OTP 6 số vừa gửi tới {email}: ").strip()
    for _ in range(40):
        text = Path(log_path).read_text(encoding="utf-8", errors="replace")
        # Windows console may mangle Vietnamese letters, so match on the email and cut at '=== h' (not '=== hết').
        blocks = [b.split("=== h")[0] for b in text.split("=== MAIL")[1:] if email in b[:400]]
        found = re.findall(r"\b(\d{6})\b", blocks[-1]) if blocks else []
        if found:
            return found[-1]
        time.sleep(0.5)
    sys.exit(f"[HỎNG] không thấy OTP cho {email} trong {log_path} (BE có chạy với MAIL_HOST rỗng không?)")


def make_account(ctx, local, name):
    email = f"{local}@{DOMAIN}"
    must("đăng ký " + email, call(ctx.base, "POST", "/api/auth/register",
                                  body={"email": email, "password": ctx.password, "displayName": name}))
    tok = must("xác minh " + email, call(ctx.base, "POST", "/api/auth/verify",
                                         body={"email": email, "otp": read_otp(ctx.log, email)}))["accessToken"]
    me = must("auth/me " + email, call(ctx.base, "GET", "/api/auth/me", tok))
    return {"email": email, "token": tok, "id": me["id"], "roles": me.get("roles") or []}


def internal_login(ctx, email):
    return must("đăng nhập nội bộ " + email, call(ctx.base, "POST", "/api/auth/internal/login",
                                                  body={"email": email, "password": ctx.password}))["accessToken"]


def first_try_batch(chapter, number, version):
    """Plays the chapter the way a child would: first option of each choice group; mini-games answered right,
    except one Needs/Wants card deliberately wrong so the adult report shows a mistake."""
    choices, games, seen = [], [], set()
    for s in chapter["scenes"]:
        if s["type"] == "choice" and s.get("options"):
            group = s.get("choiceGroup") or s["id"]
            if group not in seen:
                seen.add(group)
                choices.append({"sceneId": s["id"], "optionId": s["options"][0]["id"]})
        game = s.get("game") or {}
        if s["type"] == "minigame" and game.get("items"):        # Needs / Wants cards
            for i, item in enumerate(game["items"]):
                wrong = i == len(game["items"]) - 1
                chosen = ("NEEDS" if item["category"] == "WANTS" else "WANTS") if wrong else item["category"]
                games.append({"gameId": game["id"], "itemId": item["id"], "chosen": chosen})
        elif s["type"] == "minigame" and game.get("dropZones"):  # budget envelopes
            by_value = {d["value"]: d["id"] for d in game.get("draggables", [])}
            for z in game["dropZones"]:
                if z.get("expectedValue") in by_value:
                    games.append({"gameId": game["id"], "itemId": z["id"], "chosen": by_value[z["expectedValue"]]})
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {"chapter": number, "attemptIndex": 1, "releaseVersion": version,
            "choices": choices, "miniGames": games, "finishedAt": now}


def pin():
    return f"{secrets.randbelow(10**6):06d}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080", help="BE base URL (gồm context path nếu có)")
    ap.add_argument("--log", help="log của BE (MAIL_HOST rỗng → OTP in ra đây). Bỏ trống = gõ OTP từ hộp thư")
    ap.add_argument("--allow-host", help="cho phép host khác localhost (staging), không bao giờ production")
    ap.add_argument("--password", help="mật khẩu chung cho tài khoản demo; mặc định đọc env SEED_PASSWORD, không có thì sinh ngẫu nhiên")
    ctx = ap.parse_args()
    ctx.base = ctx.base.rstrip("/")
    guard_host(ctx.base, ctx.allow_host)
    ctx.password = ctx.password or os.environ.get("SEED_PASSWORD") or "Demo-" + secrets.token_urlsafe(9)

    must("health", call(ctx.base, "GET", "/api/health"))
    status, res = call(ctx.base, "GET", "/api/content/current")
    if status == 200 and res.get("code") == 0 and res.get("result"):
        sys.exit("DỪNG: BE này đã có bản phát hành nội dung, tức DB không mới. Script chỉ chạy trên DB mới "
                 "(H2 bộ nhớ: tắt BE rồi bật lại). Chưa ghi gì.")

    # 1. Internal accounts. Admin gets ADMIN on verification because BE runs with ADMIN_EMAILS=admin@demo.test.
    acc = {local: make_account(ctx, local, name) for local, name, _ in STAFF}
    if "ADMIN" not in acc["admin"]["roles"]:
        sys.exit(f"[HỎNG] admin@{DOMAIN} không có vai ADMIN — chạy BE với ADMIN_EMAILS=admin@{DOMAIN}")
    admin = acc["admin"]["token"]
    for local, _, role in STAFF:
        if role:
            must("cấp " + role, call(ctx.base, "POST", "/api/admin/roles/grant", admin,
                                     {"subjectId": acc[local]["id"], "role": role, "reason": "demo seed"}))
    editor, reviewer, manager = (internal_login(ctx, f"{x}@{DOMAIN}") for x in ("editor", "reviewer", "manager"))

    # 2. Content: editor saves + submits, reviewer approves, manager publishes (the real PR C flow).
    chapter_files = sorted(CHAPTERS_DIR.glob("CH*.json"))
    chapters = {}
    for f in chapter_files:
        code = f.stem.upper()
        chapters[code] = json.loads(f.read_text(encoding="utf-8"))
        must("lưu nháp " + code, call(ctx.base, "PUT", f"/api/studio/chapters/{code}/draft", editor, chapters[code]))
        must("gửi duyệt " + code, call(ctx.base, "POST", f"/api/studio/chapters/{code}/submit", editor,
                                       {"changeClass": "MAJOR", "note": "chương mới (demo)"}))
        must("duyệt " + code, call(ctx.base, "POST", f"/api/studio/chapters/{code}/review", reviewer, {"pass": True}))
    rel = must("phát hành", call(ctx.base, "POST", "/api/studio/releases", manager, {"note": "bản demo (seed)"}))
    version = rel["version"]

    # 3. Parent: PARENT plan granted by admin (no payment), family group, 2 child slots.
    parent = make_account(ctx, "parent", "Demo Parent")
    must("cấp gói PARENT", call(ctx.base, "POST", "/api/entitlements/grant", admin,
                                {"accountId": parent["id"], "kind": "PARENT", "months": 3, "reason": "demo seed"}))
    family = must("mở nhóm gia đình", call(ctx.base, "POST", "/api/groups", parent["token"],
                                           {"name": "Nhà demo", "context": "FAMILY"}))
    kids = [("Bé An", pin()), ("Bé Bình", pin())]
    family_slots = must("mở 2 slot gia đình", call(ctx.base, "POST", "/api/slots/bulk", parent["token"], {"slots": [
        {"groupId": family["id"], "displayName": n, "pin": p} for n, p in kids]}))

    # 4. Teacher: TEACHER plan, CLASS group, consent BEFORE slots (BE enforces 5003), 3 student slots.
    teacher = make_account(ctx, "teacher", "Demo Teacher")
    must("cấp gói TEACHER", call(ctx.base, "POST", "/api/entitlements/grant", admin,
                                 {"accountId": teacher["id"], "kind": "TEACHER", "months": 3, "reason": "demo seed"}))
    klass = must("mở lớp", call(ctx.base, "POST", "/api/groups", teacher["token"], {"name": "Lớp 8A demo", "context": "CLASS"}))
    must("xác nhận đồng ý phụ huynh", call(ctx.base, "POST", f"/api/groups/{klass['id']}/consent", teacher["token"]))
    students = [("Minh", pin()), ("Lan", pin()), ("Huy", pin())]
    class_slots = must("mở 3 slot lớp", call(ctx.base, "POST", "/api/slots/bulk", teacher["token"], {"slots": [
        {"groupId": klass["id"], "displayName": n, "pin": p} for n, p in students]}))

    # 5. Bé An finishes CH01 + CH02 so the adult report has numbers.
    an = must("Bé An đăng nhập", call(ctx.base, "POST", "/api/slots/login",
                                      body={"code": family_slots[0]["code"], "pin": kids[0][1]}))["accessToken"]
    numbers = {c["code"]: c["chapterNumber"] for c in rel["chapters"]}
    for code in ("CH01", "CH02"):
        must("chơi " + code, call(ctx.base, "POST", "/api/play/chapter", an,
                                  first_try_batch(chapters[code], numbers[code], version)))

    # 6. One class quiz from 3 demo questions; student Minh submits it.
    must("nhập câu hỏi demo", call(ctx.base, "POST", "/api/admin/questions/import", admin, [
        {"externalId": x, "pool": "DEMO", "stem": s, "options": o, "correctIndex": c, "standard": None, "concept": None,
         "source": "FinTeen demo seed", "license": "FinTeen team", "chapter": None} for x, s, o, c in DEMO_QUESTIONS]))
    bank = must("kho câu hỏi", call(ctx.base, "GET", "/api/questions?pool=DEMO", teacher["token"]))
    qids = [q["id"] for q in sorted(bank, key=lambda q: q["externalId"])]
    due = (datetime.now(timezone.utc) + timedelta(days=14)).strftime("%Y-%m-%dT%H:%M:%SZ")
    quiz = must("tạo đề", call(ctx.base, "POST", "/api/quizzes", teacher["token"],
                               {"groupId": klass["id"], "title": "Kiểm tra nhanh: nhu cầu và tiết kiệm", "questionIds": qids, "dueAt": due}))
    must("phát đề", call(ctx.base, "POST", f"/api/quizzes/{quiz['id']}/publish", teacher["token"]))
    minh = must("Minh đăng nhập", call(ctx.base, "POST", "/api/slots/login",
                                       body={"code": class_slots[0]["code"], "pin": students[0][1]}))["accessToken"]
    must("Minh nộp bài", call(ctx.base, "POST", f"/api/learner/quizzes/{quiz['id']}/submit", minh,
                              {"answers": [{"questionId": q, "chosenIndex": 0} for q in qids]}))

    # 7. Verify through the app's own state, not through our log.
    cur = must("đọc bản hiện tại", call(ctx.base, "GET", "/api/content/current"))
    report = must("báo cáo Bé An", call(ctx.base, "GET", f"/api/slots/{family_slots[0]['id']}/report", parent["token"]))
    class_report = must("báo cáo lớp", call(ctx.base, "GET", f"/api/groups/{klass['id']}/report", teacher["token"]))
    problems = []
    if cur["version"] != version or len(cur["chapters"]) != len(chapter_files):
        problems.append(f"bản hiện tại {cur['version']} có {len(cur['chapters'])} chương")
    if report["progress"]["completedChapters"] != 2 or not report["indicators"]:
        problems.append(f"báo cáo Bé An: {report['progress']}")
    if class_report["summary"]["quizSubmitted"] != 1 or len(class_report["rows"]) != 3:
        problems.append(f"báo cáo lớp: {class_report['summary']}")
    if problems:
        sys.exit("[HỎNG] kiểm lại sau khi seed: " + "; ".join(problems))

    print(f"\n=== Seed xong trên {ctx.base} — chỉ dùng cho demo ===")
    print(f"Mật khẩu chung mọi tài khoản người lớn: {ctx.password}")
    for local, _, role in STAFF:
        print(f"  {local + '@' + DOMAIN:<20} {role or 'ADMIN'}")
    print(f"  {'parent@' + DOMAIN:<20} gói PARENT, nhóm '{family['name']}'")
    print(f"  {'teacher@' + DOMAIN:<20} gói TEACHER, lớp '{klass['name']}' (đã xác nhận đồng ý), đề '{quiz['title']}'")
    print(f"Bản nội dung {version}: {', '.join(c['code'] for c in cur['chapters'])}")
    print("Slot trẻ (mã / PIN):")
    for s, (_, p) in zip(family_slots + class_slots, kids + students):
        print(f"  {s['displayName']:<8} {s['code']}  {p}")
    print(f"Bé An: xong chương {report['progress']['chaptersDone']}, chỉ số {report['indicators']}")
    print(f"Lớp: {class_report['summary']['learners']} học sinh, {class_report['summary']['quizSubmitted']} bài nộp, "
          f"điểm TB {class_report['summary']['averageScore']}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    main()
