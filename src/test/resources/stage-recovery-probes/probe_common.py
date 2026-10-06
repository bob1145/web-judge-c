import json
import pathlib
import sys


def load_config():
    config = json.loads(pathlib.Path(__file__).with_name("probe-config.json").read_text(encoding="utf-8"))
    if config.get("pythonSources"):
        sys.path.insert(0, config["pythonSources"])
    return config
