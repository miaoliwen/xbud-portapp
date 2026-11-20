#!/usr/bin/env python3
"""把高德 key/securityJsCode 加密成 App 运行时读取的 assets/amap_secret.bin。

用法：
    python portapp/tools/gen_amap_secret.py amap_key.json portapp/app/src/main/assets/amap_secret.bin

格式：XBK1 + 12B nonce + AES-256-GCM 密文（JSON）。
密钥 = SHA-256("xbudport/amap/v1|包名|PEPPER")，AAD = 包名。
"""

import argparse
import hashlib
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PACKAGE = "com.yg.xbudport"
PEPPER = "6e13c1edd16e88ca603673478a3669b110a405d95b8bf13a"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", help="amap_key.json（key/securityJsCode/platform）")
    parser.add_argument("output", help="输出 amap_secret.bin")
    parser.add_argument(
        "--platform",
        choices=["android", "web"],
        help="覆盖 JSON 里的 platform；原生高德地图必须是 android",
    )
    args = parser.parse_args()

    data = json.loads(Path(args.source).read_text(encoding="utf-8"))
    platform = args.platform or data.get("platform", "web")
    if platform == "android":
        key = data.get("androidKey", "")
        if not key:
            raise SystemExit("amap_key.json 缺少 androidKey（高德控制台 Android 平台 key）")
    else:
        key = data["key"]

    payload = json.dumps(
        {
            "key": key,
            "securityJsCode": data.get("securityJsCode", "") if platform != "android" else "",
            "platform": platform,
        },
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")

    aes_key = hashlib.sha256(f"xbudport/amap/v1|{PACKAGE}|{PEPPER}".encode("utf-8")).digest()
    nonce = os.urandom(12)
    cipher_text = AESGCM(aes_key).encrypt(nonce, payload, PACKAGE.encode("utf-8"))
    blob = b"XBK1" + nonce + cipher_text

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(blob)
    print(f"wrote {out} ({len(blob)} B)")


if __name__ == "__main__":
    main()
