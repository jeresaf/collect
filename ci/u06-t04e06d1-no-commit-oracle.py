"""Disposable CI oracle: compare exact database rows and existing media bytes."""
import hashlib
import json
import pathlib
import subprocess
import sys

TABLES = ("submissions", "submission_versions", "submission_answers",
          "submission_attachments", "record_links", "record_work_items")

def query(sql):
    return subprocess.check_output(
        ["psql", "-tAX", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1",
         "-U", "odk_user", "-d", "odk_platform", "-c", sql],
        text=True).strip()

def sha(data):
    return hashlib.sha256(data).hexdigest()

def snapshot():
    rows = {t: sha(query(f"SELECT COALESCE(json_agg(row_to_json(t) ORDER BY t.id)::text, '[]') FROM {t} t").encode())
            for t in TABLES}
    media = json.loads(query("SELECT COALESCE(json_agg(storage_path ORDER BY id), '[]') FROM submission_attachments"))
    rows["media"] = [sha(pathlib.Path(p).read_bytes()) for p in media if p and pathlib.Path(p).is_file()]
    return rows

mode, path = sys.argv[1:]
file = pathlib.Path(path)
if mode == "before":
    file.write_text(json.dumps(snapshot()))
elif mode == "after":
    if snapshot() != json.loads(file.read_text()):
        raise SystemExit("Interrupted ingress postflight failed: durable rows or existing media changed")
    print("Interrupted ingress PostgreSQL exact-row preservation PASS")
else:
    raise SystemExit("Unknown oracle mode")
