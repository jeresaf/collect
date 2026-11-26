"""Synthetic connected CI only: prove exact pending media survive SIGKILL."""
import json
import pathlib
import re
import sys

root = pathlib.Path("/tmp/u06-t04a-data/submissions")
state = pathlib.Path("/tmp/u08-crash-journal-evidence.json")
mode = sys.argv[1]
markers = sorted(root.glob(".attachment-journal/*/*.pending"))
if mode == "before-kill":
    assert len(markers) == 3, "expected three pending native attachments"
    pairs = []
    for marker in markers:
        assert marker.is_file() and not marker.is_symlink() and marker.stat().st_size == 0
        submission, file_id = marker.stem.split("_")
        namespace = marker.parent.name
        assert all(re.fullmatch(r"[0-9a-f-]{36}", x) for x in (submission, file_id, namespace))
        payload = root / submission / f"u08-{namespace}-{file_id}.media"
        assert payload.is_file() and not payload.is_symlink() and payload.stat().st_size > 0
        pairs.append([str(marker), str(payload)])
    assert len({pathlib.Path(p).parent for _, p in pairs}) == 1
    state.write_text(json.dumps(pairs))
elif mode == "after-kill":
    pairs = json.loads(state.read_text())
    assert all(pathlib.Path(p).is_file() for pair in pairs for p in pair), "callbacks unexpectedly ran"
elif mode == "recovered":
    pairs = json.loads(state.read_text())
    if any(pathlib.Path(p).exists() for pair in pairs for p in pair):
        sys.exit(1)
    assert not markers, "unexpected remaining journal marker"
else:
    raise SystemExit("unknown evidence phase")
