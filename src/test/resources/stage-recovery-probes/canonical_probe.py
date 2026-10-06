import json
import sys

from probe_common import load_config


def main():
    config = load_config()
    sys.stdout.write(json.dumps({"admissionVersion": 1, "attemptId": config["attempt"],
                                 "state": "CLOSED"}, separators=(",", ":")))


if __name__ == "__main__":
    main()
