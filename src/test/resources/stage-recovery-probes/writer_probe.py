import json
import os
import sys

from probe_common import load_config


def main():
    config = load_config()
    root = config["stageJournal"]
    attempt = config["attempt"]
    volume = config["volume"]
    for spec in json.loads(sys.stdin.read()):
        stage = spec["stage"]
        labels = {"io.webjudge.attempt": attempt, "io.webjudge.stage": stage,
                  "io.webjudge.role": "USER", "io.webjudge.managed": "stage"}
        record = {"journalVersion": 1, "creationState": spec["creationState"], "stage": stage,
                  "attempt": attempt, "role": "USER", "phase": "execute",
                  "name": "wjc-stage-" + stage, "labels": labels, "volume": volume,
                  "state": spec["state"]}
        if spec.get("containerId"):
            record["containerId"] = spec["containerId"]
        if spec.get("outcome") is not None:
            record["outcome"] = spec["outcome"]
        payload = json.dumps(record, separators=(",", ":")).encode("utf-8")
        descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            target = os.open(stage + ".json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=descriptor)
            try:
                os.write(target, payload)
                os.fchmod(target, 0o600)
            finally:
                os.close(target)
        finally:
            os.close(descriptor)
    print("WRITTEN")


if __name__ == "__main__":
    main()
