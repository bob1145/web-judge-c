import json
import os

from probe_common import load_config


def main():
    config = load_config()
    import linux_stage_executor as executor
    directory = executor._open_locked_stage_journal(config["stageJournal"], config["ownerUid"])
    try:
        closed = executor._admission_entry_exists(directory, executor._ADMISSION_CLOSED)
        pending = executor._admission_entry_exists(directory, executor._ADMISSION_PENDING)
        print("STATE", "REJECTED" if closed or pending else "OPEN")
        if closed:
            record = executor._read_closed_admission(directory, config["attempt"], config["ownerUid"])
            print("RECORD", json.dumps(record, separators=(",", ":")))
    finally:
        os.close(directory)


if __name__ == "__main__":
    main()
