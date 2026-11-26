"""Independent PostgreSQL/media oracle for disposable connected amendment."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

FORM = "81000000-0000-4000-8000-000000000002"
V2 = "81000000-0000-4000-8000-000000000004"
TARGET = "81000000-0000-4000-8000-000000000021"

def query(sql):
    return subprocess.check_output(
        ["psql", "-tAX", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1",
         "-U", "odk_user", "-d", "odk_platform", "-c", sql], text=True).strip()

def rows(sql):
    return json.loads(query("SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (" + sql + ") t"))

def sha(data):
    return hashlib.sha256(data).hexdigest()

def check(condition, phase):
    if not condition:
        raise SystemExit("Amendment postflight failed: " + phase)

def source_id():
    result = rows(f"SELECT s.id FROM submissions s JOIN record_links l ON l.child_submission_id=s.id "
                  f"WHERE s.form_version_id='{V2}' AND l.link_type='FOLLOW_UP'")
    check(len(result) == 1, "unique amendment source")
    return result[0]["id"]

def snapshot(source):
    result = {table: sha(query(f"SELECT COALESCE(json_agg(row_to_json(t) ORDER BY t.id)::text, '[]') "
                            f"FROM (SELECT * FROM {table} WHERE {key}='{source}') t").encode())
              for table, key in [("submissions", "id"), ("submission_answers", "submission_id"),
                                 ("submission_attachments", "submission_id")]}
    media = rows(f"SELECT id,storage_path,content_hash,file_size FROM submission_attachments WHERE submission_id='{source}'")
    result["original_media"] = {}
    for item in media:
        data = pathlib.Path(item["storage_path"]).read_bytes()
        check(len(data) == item["file_size"] and sha(data) == item["content_hash"], "original media integrity")
        result["original_media"][item["id"]] = sha(data)
    return result

def main():
    mode, history = sys.argv[1:3]
    history = pathlib.Path(history)
    source = source_id()
    if mode == "before":
        check(query(f"SELECT count(*) FROM submission_versions WHERE submission_id='{source}'") == "0", "source current")
        history.write_text(json.dumps({"source": source, "snapshot": snapshot(source)}))
        return
    check(mode == "after", "mode")
    expected = json.loads(pathlib.Path(sys.argv[3]).read_text())
    for key in ("work", "source", "child", "target"):
        check(re.fullmatch(r"[0-9a-f-]{36}", expected[key]) is not None, "identifier")
    check(re.fullmatch(r"uuid:[0-9a-f-]{36}", expected["reserved"]) is not None, "instance")
    check(expected["source"] == source and expected["target"] == TARGET and "visit" in expected and expected["visit"] is None, "binding")
    check(json.loads(history.read_text()) == {"source": source, "snapshot": snapshot(source)}, "source preservation")
    child, work = expected["child"], expected["work"]
    original = rows(f"SELECT instance_id,xml_content,form_version_id,submitted_by_id FROM submissions WHERE id='{source}'")[0]
    archive = rows(f"SELECT instance_id,xml_content,source_form_version_id,submitted_by_id,deprecated_id,version_number FROM submission_versions WHERE submission_id='{source}'")
    check(archive == [{"instance_id": original["instance_id"], "xml_content": original["xml_content"],
                       "source_form_version_id": original["form_version_id"], "submitted_by_id": original["submitted_by_id"],
                       "deprecated_id": original["instance_id"], "version_number": 1}], "exact single archive")
    record = rows(f"SELECT instance_id,xml_content,form_version_id,form_id,submitted_by_id FROM submissions WHERE id='{child}'")
    check(len(record) == 1, "single child")
    r = record[0]
    check(r["instance_id"] == expected["reserved"] and r["form_version_id"] == TARGET and r["form_id"] == FORM
          and r["submitted_by_id"] == original["submitted_by_id"], "child identity")
    raw = r["xml_content"].encode()
    check(len(raw) == expected["xmlBytes"] and sha(raw) == expected["xmlSha256"], "native XML")
    tree = ET.fromstring(raw)
    for node in tree.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    check(tree.findtext("meta/instanceID") == expected["reserved"] and
          tree.findtext("meta/deprecatedID") == original["instance_id"], "amendment metadata")
    check(tree.findtext("household_name") == "Synthetic reentered amendment", "explicit reentry")
    members = tree.findall("members")
    check(len(members) == 2, "repeat count")
    answers = {"household_name": tree.findtext("household_name"), "photo": tree.findtext("photo")}
    for i, member in enumerate(members):
        check(member.findtext("name") == "Reentered member " + str(i), "repeat reentry")
        for field in ("name", "photo"):
            answers[f"members[{i}]/{field}"] = member.findtext(field)
    actual = rows(f"SELECT path,value_text FROM submission_answers WHERE submission_id='{child}'")
    for path,value in answers.items():
        check([a["value_text"] for a in actual if a["path"] == path] == [value], "normalized answer")
    links = rows(f"SELECT l.source_submission_id,l.child_submission_id,l.link_type,w.state,w.visit_key "
                 f"FROM record_links l JOIN record_work_items w ON w.id=l.work_item_id WHERE w.id='{work}'")
    check(links == [{"source_submission_id":source,"child_submission_id":child,
                     "link_type":"AMENDMENT","state":"COMPLETED","visit_key":None}], "amendment link")
    check(query(f"SELECT count(*) FROM protected_submission_attempts WHERE work_item_id='{work}' "
                f"AND accepted_submission_id='{child}'") == "1", "accepted attempt")
    check(query(f"SELECT count(*) FROM submissions WHERE instance_id='{expected['reserved']}'") == "1", "instance unique")
    media = rows(f"SELECT sa.file_name,sa.content_type,sa.file_size,sa.content_hash,sa.storage_path,a.path "
                 f"FROM submission_attachments sa JOIN submission_answers a ON a.id=sa.answer_id WHERE sa.submission_id='{child}'")
    parts = {a["filename"]:a for a in expected["attachments"]}
    paths = {value:path for path,value in answers.items() if path.endswith("photo")}
    check(len(media) == len(parts) == len(paths) == 3, "media count")
    check({m["file_name"] for m in media} == set(parts) == set(paths), "media bijection")
    for m in media:
        p = parts[m["file_name"]]
        check(m["path"] == paths[m["file_name"]] and m["content_type"] == p["mime"] and
              m["file_size"] == p["bytes"] and m["content_hash"] == p["sha256"], "media metadata")
        data = pathlib.Path(m["storage_path"]).read_bytes()
        check(len(data) == p["bytes"] and sha(data) == p["sha256"], "media bytes")
    print("U08 amendment oracle PASS: exact source/archive, child XML, indexed answers, linked media and single accepted attempt.")

if __name__ == "__main__":
    main()
