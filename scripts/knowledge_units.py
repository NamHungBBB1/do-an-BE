"""Từ điển mã kiến thức cho báo cáo người lớn (khung kiến thức v3, mục 4) — src/main/resources/content/knowledge-units.json.

    python scripts/knowledge_units.py <khung-kien-thuc-v3.md>            # kiểm JSON khớp md (id, name, objective, level, domain)
    python scripts/knowledge_units.py <khung-kien-thuc-v3.md> --write    # ghi lại các trường lấy từ md, GIỮ nameVi đã có

nameVi (tên ngắn cho phụ huynh) không có trong md: soạn tay trong JSON (nháp chờ người duyệt nội dung). Mã mới mà tên
trong md đã là tiếng Việt thì nameVi = tên bỏ "(Bổ sung)"; tên tiếng Anh thì để trống và script báo lỗi để soạn.
"""
import json
import re
import sys
from pathlib import Path

JSON_PATH = Path(__file__).resolve().parent.parent / "src/main/resources/content/knowledge-units.json"
ROW = re.compile(r"^\|\s*(D\d{2}\.\d{2})\s*\|([^|]*)\|([^|]*)\|([^|]*)\|")
VI = re.compile(r"[ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ]", re.I)


def clean(s):
    return re.sub(r"\\([^\\])", r"\1", s).replace("**", "").strip()


def parse(md):
    out = []
    for line in Path(md).read_text(encoding="utf-8").splitlines():
        m = ROW.match(line)
        if m:
            uid = m.group(1)
            out.append({"id": uid, "domain": uid[:3], "name": clean(m.group(2)),
                        "objective": clean(m.group(3)), "level": clean(m.group(4))})
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    units = parse(sys.argv[1])
    old = {u["id"]: u for u in json.loads(JSON_PATH.read_text(encoding="utf-8"))} if JSON_PATH.exists() else {}
    ids = [u["id"] for u in units]
    problems = []
    if len(ids) != 65 or len(set(ids)) != 65:
        problems.append(f"md có {len(ids)} mã ({len(set(ids))} khác nhau), cần 65")
    for u in units:
        prev = old.get(u["id"], {})
        u["nameVi"] = prev.get("nameVi") or (re.sub(r"\s*\(Bổ sung\)", "", u["name"]) if VI.search(u["name"]) else "")
        if not u["nameVi"]:
            problems.append(f"{u['id']}: thiếu nameVi")
        for k in ("name", "objective", "level", "domain"):
            if prev.get(k) != u[k]:
                problems.append(f"{u['id']}.{k}: JSON '{prev.get(k)}' khác md '{u[k]}'")
    for gone in set(old) - set(ids):
        problems.append(f"{gone}: có trong JSON, không có trong md")
    if "--write" in sys.argv:
        JSON_PATH.write_text(json.dumps(units, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"đã ghi {len(units)} mã → {JSON_PATH}")
        problems = [p for p in problems if "thiếu nameVi" in p or "cần 65" in p]
    for p in problems:
        print(p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
