"""打包 CameraMorphoExpiryBypass（KernelSU 模块）。

用法（在本目录执行）：
    python build_module.py

要求：ksu-module/ 下已有
    module.prop
    post-fs-data.sh
    uninstall.sh
    system/vendor/lib64/libmorpho_video_stabilizer.so   （apply_patch.py 生成的补丁库）
"""
import hashlib
import os
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(ROOT, "..", "release", "CameraMorphoExpiryBypass-v1.2.zip"))

FILES = [
    ("module.prop", 0o100644),
    ("post-fs-data.sh", 0o100755),
    ("uninstall.sh", 0o100755),
    ("system/vendor/lib64/libmorpho_video_stabilizer.so", 0o100644),
]


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for name, mode in FILES:
            path = os.path.join(ROOT, name)
            if not os.path.isfile(path):
                raise SystemExit("missing: " + path)
            info = zipfile.ZipInfo(name)
            info.external_attr = mode << 16
            with open(path, "rb") as f:
                z.writestr(info, f.read())

    data = open(OUT, "rb").read()
    print("zip:", OUT)
    print("size:", len(data))
    print("sha256:", hashlib.sha256(data).hexdigest())
    with zipfile.ZipFile(OUT) as z:
        for i in z.infolist():
            print(i.filename, i.file_size, oct(i.external_attr >> 16))
        print("embedded so sha256:",
              hashlib.sha256(z.read("system/vendor/lib64/libmorpho_video_stabilizer.so")).hexdigest())


if __name__ == "__main__":
    main()
