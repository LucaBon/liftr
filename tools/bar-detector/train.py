"""Train the barbell endcap detector (single class) and evaluate on leakage-free test sets."""
import os
import sys
from pathlib import Path

from ultralytics import YOLO

DATA = Path(os.environ.get("LIFTR_BAR_DATA", Path.home() / "datasets/liftr-bar"))


def write_yaml(name: str, train: str, val: str) -> Path:
    y = DATA / f"{name}.yaml"
    y.write_text(f"path: {DATA}\ntrain: {train}\nval: {val}\nnames:\n  0: endcap\n")
    return y


def main(epochs: int) -> None:
    data = write_yaml("train", "unified/train/images", "unified/valid/images")
    model = YOLO("yolo26n.pt")
    model.train(
        data=str(data), imgsz=640, epochs=epochs, batch=16, patience=15,
        project=str(DATA / "runs"), name="endcap26n", exist_ok=True,
        flipud=0.0, fliplr=0.5, mosaic=1.0, close_mosaic=10, workers=8, plots=True,
    )
    evaluate(DATA / "runs/endcap26n/weights/best.pt")


def evaluate(weights: Path) -> None:
    model = YOLO(str(weights))
    for name, lst in [("unified_test_clean", "unified_test_clean.txt"), ("olympic_test", "olympic_test_clean.txt")]:
        y = write_yaml(f"eval_{name}", "unified/train/images", lst)
        m = model.val(data=str(y), imgsz=640, split="val", project=str(DATA / "runs"), name=f"val_{weights.parent.parent.name}_{name}", exist_ok=True)
        print(f"RESULT {name}: mAP50={m.box.map50:.3f} mAP50-95={m.box.map:.3f} P={m.box.mp:.3f} R={m.box.mr:.3f}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "eval":
        evaluate(Path(sys.argv[2]))
    else:
        main(int(sys.argv[1]) if len(sys.argv) > 1 else 60)
