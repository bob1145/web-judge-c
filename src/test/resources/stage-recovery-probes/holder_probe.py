import fcntl
import os
import sys

from probe_common import load_config


def main():
    config = load_config()
    descriptor = os.open(config["stageJournal"],
                         os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        print('READY', flush=True)
        sys.stdin.buffer.read(1)
    finally:
        os.close(descriptor)


if __name__ == "__main__":
    main()
