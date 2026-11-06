"""Find test images that are near-duplicates of training images (dHash, Hamming <= 6).

Writes <split>_clean.txt listing test images with no near-duplicate in train, so metrics
can be reported on frames the model has not effectively seen.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def dhash(path: Path) -> int:
    im = Image.open(path).convert("L").resize((17, 16))
    a = np.asarray(im, dtype=np.int16)
    bits = (a[:, 1:] > a[:, :-1]).flatten()
    return int("".join("1" if b else "0" for b in bits), 2)


def main(root: str, train_dirs: list[str], test_dir: str, out: str, thr: int = 6) -> None:
    root_p = Path(root)
    train = np.array([dhash(p) for d in train_dirs for p in (root_p / d).glob("*.jpg")], dtype=object)
    train_bits = np.array([[(h >> i) & 1 for i in range(240)] for h in train], dtype=np.uint8)
    clean = []
    tests = sorted((root_p / test_dir).glob("*.jpg"))
    for p in tests:
        h = dhash(p)
        bits = np.array([(h >> i) & 1 for i in range(240)], dtype=np.uint8)
        if (train_bits != bits).sum(axis=1).min() > thr:
            clean.append(str(p.resolve()))
    Path(out).write_text("\n".join(clean) + "\n")
    print(f"{test_dir}: {len(clean)}/{len(tests)} test images have no near-duplicate in train")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2].split(","), sys.argv[3], sys.argv[4])
