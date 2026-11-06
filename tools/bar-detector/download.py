"""Download the barbell endcap datasets from Roboflow Universe (YOLO format).

Reads the private API key from ~/.config/roboflow/key. Data goes to $LIFTR_BAR_DATA
(default ~/datasets/liftr-bar), never into the repo.
"""
import os
from pathlib import Path

from roboflow import Roboflow

DATA = Path(os.environ.get("LIFTR_BAR_DATA", Path.home() / "datasets/liftr-bar"))
DATASETS = [
    # (workspace, project, version, folder)  — all CC BY 4.0
    ("bar-path", "bar-path-detection-unified-cyusm", 11, "unified"),
    ("bar-path", "olympic-weightlifting-tracking-jjni3", 8, "olympic"),
]


def main() -> None:
    key = (Path.home() / ".config/roboflow/key").read_text().strip()
    rf = Roboflow(api_key=key)
    for ws, project, version, folder in DATASETS:
        out = DATA / folder
        if (out / "data.yaml").exists():
            print(f"skip {folder}: already downloaded")
            continue
        rf.workspace(ws).project(project).version(version).download("yolov11", location=str(out))


if __name__ == "__main__":
    main()
