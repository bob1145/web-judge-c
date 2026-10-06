from probe_common import load_config


def main():
    config = load_config()
    import linux_stage_executor as executor
    executor.close_existing_stage_admission(config["stageJournal"], config["attempt"], config["ownerUid"])
    print("CLOSED")


if __name__ == "__main__":
    main()
