"""Disposable CI oracle; never print row contents or respondent data."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

SOURCE = "81000000-0000-4000-8000-000000000007"
CORRECTED = "81000000-0000-4000-8000-000000000008"
TARGET = "81000000-0000-4000-8000-000000000021"
FORM = "81000000-0000-4000-8000-000000000002"

def query(sql):
    return subprocess.check_output(
        ["psql", "-tAX", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1",
         "-U", "odk_user", "-d", "odk_platform", "-c", sql],
        text=True).strip()

def rows(sql):
    return json.loads(query("SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (" + sql + ") t"))

def sha(data):
    return hashlib.sha256(data).hexdigest()

def check(condition, phase):
    if not condition:
        raise SystemExit("Native submission postflight failed: " + phase)

def historical():
    # Include both original and corrected record, archives, normalized answers,
    # and attachment metadata. Exact row content hashes, not just row counts.
    ids = f"('{SOURCE}','{CORRECTED}')"
    return {
        table: sha(query(f"SELECT COALESCE(json_agg(row_to_json(t) ORDER BY t.id)::text, '[]') "
                         f"FROM (SELECT * FROM {table} WHERE {where}) t").encode())
        for table, where in [
            ("submissions", f"id IN {ids}"),
            ("submission_versions", f"submission_id IN {ids}"),
            ("submission_answers", f"submission_id IN {ids}"),
            ("submission_attachments", f"submission_id IN {ids}"),
        ]
    }

def main():
    mode, history = sys.argv[1:3]
    history = pathlib.Path(history)
    if mode == "before":
        check(query(f"SELECT count(*) FROM submissions WHERE form_id='{FORM}'") == "3", "preflight submission count")
        check(query("SELECT count(*) FROM record_links") == "1", "preflight link count")
        history.write_text(json.dumps(historical()))
        return
    check(mode == "after", "mode")
    expected = json.loads(pathlib.Path(sys.argv[3]).read_text())
    for key in ("work", "source", "child", "target"):
        check(re.fullmatch(r"[0-9a-f-]{36}", expected[key]) is not None, "oracle identifier")
    check(re.fullmatch(r"uuid:[0-9a-f-]{36}", expected["reserved"]) is not None, "oracle instance")
    check(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", expected["visit"]) is not None, "oracle visit")
    check(expected["source"] == SOURCE and expected["target"] == TARGET, "oracle binding")
    child, work = expected["child"], expected["work"]
    check(child not in (SOURCE, CORRECTED), "independent child")
    check(historical() == json.loads(history.read_text()), "historical row preservation")
    check(query(f"SELECT count(*) FROM submissions WHERE form_id='{FORM}'") == "4", "submission count")
    check(query("SELECT count(*) FROM record_links") == "2", "link count")
    check(query(f"SELECT count(*) FROM submissions WHERE instance_id='{expected['reserved']}'") == "1",
          "reserved identity uniqueness")
    records = rows(f"SELECT instance_id, form_version_id, form_id, submitted_by_id, status, xml_content FROM submissions WHERE id='{child}'")
    check(len(records) == 1, "child exists")
    record = records[0]
    check(record["instance_id"] == expected["reserved"] and record["form_version_id"] == TARGET
          and record["form_id"] == FORM and record["submitted_by_id"] == "81000000-0000-4000-8000-000000000005"
          and record["status"] == "SUBMITTED", "child identity")
    raw = record["xml_content"].encode("utf-8")
    check(len(raw) == expected["xmlBytes"] and sha(raw) == expected["xmlSha256"], "exact native XML")
    tree = ET.fromstring(raw)
    # JavaRosa may retain the form namespace; normalized backend paths use local names.
    for node in tree.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    check(tree.attrib.get("version") == "5" and tree.findtext("meta/instanceID") == expected["reserved"]
          and tree.find("meta/deprecatedID") is None, "native XML identity")
    members = tree.findall("members")
    check(len(members) == 2, "native repeat count")
    expected_answers = {"household_name": tree.findtext("household_name"), "photo": tree.findtext("photo")}
    for i, member in enumerate(members):
        for name in ("name", "photo"):
            expected_answers[f"members[{i}]/{name}"] = member.findtext(name)
    check(all(expected_answers.values()), "native answer completeness")
    actual = rows(f"SELECT path, value_text FROM submission_answers WHERE submission_id='{child}'")
    for path, value in expected_answers.items():
        check([x["value_text"] for x in actual if x["path"] == path] == [value], "indexed answer " + path)
    linked = rows(f"SELECT l.source_submission_id,l.child_submission_id,l.link_type,w.state,"
                  f"w.work_type,w.visit_key,w.target_form_version_id "
                  f"FROM record_links l JOIN record_work_items w ON w.id=l.work_item_id "
                  f"WHERE w.id='{work}'")
    check(linked == [{"source_submission_id": SOURCE, "child_submission_id": child,
                      "link_type": "FOLLOW_UP", "state": "COMPLETED", "work_type": "FOLLOW_UP",
                      "visit_key": expected["visit"], "target_form_version_id": TARGET}], "exact completed link")
    media = rows(f"SELECT sa.file_name,sa.content_type,sa.file_size,sa.content_hash,sa.storage_path,a.path "
                 f"FROM submission_attachments sa LEFT JOIN submission_answers a ON a.id=sa.answer_id "
                 f"WHERE sa.submission_id='{child}'")
    oracle = {a["filename"]: a for a in expected["attachments"]}
    expected_paths = {value: path for path, value in expected_answers.items() if path.endswith("photo")}
    check(len(media) == len(oracle) == len(expected_paths) == 3, "attachment bijection")
    check({m["file_name"] for m in media} == set(oracle) == set(expected_paths), "attachment filenames")
    for row in media:
        part = oracle[row["file_name"]]
        check(row["path"] == expected_paths[row["file_name"]], "attachment answer link")
        check(row["content_type"] == part["mime"] and row["file_size"] == part["bytes"]
              and row["content_hash"] == part["sha256"], "attachment metadata")
        stored = pathlib.Path(row["storage_path"]).read_bytes()
        check(len(stored) == part["bytes"] and sha(stored) == part["sha256"], "attachment disk bytes")
    print("Native PostgreSQL postflight PASS: exact XML, 6 indexed answers, 3 byte-verified linked media, one completed independent visit, original and archive unchanged.")

if __name__ == "__main__":
    main()
