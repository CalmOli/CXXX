#!/usr/bin/env python3
"""Test info + data for known free sites."""

import json
import time
import gzip
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"
UA_APP = "okhttp/5.3.2"

def load_pubkey():
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    from cryptography.hazmat.primitives import serialization
    with open("pubkey.pem", "rb") as f:
        pem_data = f.read()
    lines = pem_data.decode().strip().split('\n')
    if not lines[0].startswith('-----'):
        return serialization.load_der_public_key(base64.b64decode(''.join(lines)))
    return serialization.load_pem_public_key(pem_data)

def gen_hash(pub_key):
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    ts = int(time.time())
    hash_info = OrderedDict([
        ("id", "a1b2c3d4e5f6a7b8"), ("isTV", False),
        ("loginStatus", OrderedDict([("pro", 0), ("status", 0), ("token", ""), ("unixtime", ts), ("user_id", 0)])),
        ("packageName", "com.streamdev.aiostreamer"),
        ("signatures", ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="]),
        ("time", ts), ("version", 6643)
    ])
    json_str = json.dumps(hash_info, separators=(',', ':'))
    encrypted = pub_key.encrypt(json_str.encode('utf-8'), asym_padding.PKCS1v15())
    return base64.b64encode(encrypted).decode('ascii')

def gz(data):
    if isinstance(data, str): data = data.encode('utf-8')
    return gzip.compress(data)

def main():
    pub_key = load_pubkey()
    s = requests.Session()
    h = gen_hash(pub_key)

    # Login
    login_body = json.dumps({"username": "", "password": "", "android_id": "a1b2c3d4e5f6a7b8"})
    r = s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    print(f"[login] {r.status_code}: {r.text[:100]}")

    # Test free sites that should work
    test_sites = [
        # Popular free sites
        "pornhubcom", "xvideoscom", "xhamstercom", "spankbangcom", "redtubecom",
        "youporncom", "tube8com", "beegcom", "hqpornercom", "epornercom",
        "pornonecom", "pornhatcom", "porn300com", "xnxxcom",
        # From the app's free tier
        "nsfwswipecom", "neporn", "yespornvip",
        "supjavcom", "javfinderai", "tktubecom",
        "ashemaletubecom", "trannyone",
        "pornhubcomgay", "redtubecomgay",
        "hentaimamaio", "hentaicloudcom",
        "boundhubcom",
        "familyporntv", "taboodaddycom",
        "latestpornvideocom", "latestleaksco",
        "hornysimpcom", "tnaflixcom",
        "chaturbatecom", "stripchatcom",
    ]

    print(f"\n{'SITE':<25} {'INFO':>6} {'dataSelector':>15} {'DATA':>6} {'VIDEOS':>8}")
    print("-" * 70)

    working = []
    for tag in test_sites:
        # Info
        info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": tag})
        r = s.post(f"{BASE}/v9/sites/{tag}/info", data=gz(info_body),
            headers={"hash": h, "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

        if r.status_code != 200:
            print(f"{tag:<25} {'FAIL':>6} {'---':>15} {'---':>6} {'---':>8}")
            continue

        info = r.json()
        ds = info.get("dataSelector", "?")

        # Data with empty payload
        data_body = json.dumps({"payload": ""})
        r2 = s.post(f"{BASE}/v9/sites/{tag}/data?isTV=false", data=gz(data_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

        if r2.status_code == 200:
            result = r2.json()
            count = len(result) if isinstance(result, list) else "?"
            if count and count > 0:
                working.append((tag, info, result))
                print(f"{tag:<25} {'OK':>6} {ds:>15} {r2.status_code:>6} {count:>8}")
            else:
                print(f"{tag:<25} {'OK':>6} {ds:>15} {r2.status_code:>6} {'empty':>8}")
        else:
            err_short = r2.text[:50]
            print(f"{tag:<25} {'OK':>6} {ds:>15} {r2.status_code:>6} {err_short:>8}")

    if working:
        print(f"\n\n{'='*60}")
        print(f"WORKING SITES ({len(working)}):")
        print(f"{'='*60}")
        for tag, info, result in working:
            print(f"\n--- {tag} ---")
            print(f"dataSelector: {info.get('dataSelector')}")
            print(f"base: {info.get('base')}")
            print(f"Videos: {len(result)}")
            if result:
                print(f"First video: {json.dumps(result[0], indent=2)[:500]}")

    # Also test what payload the data endpoint expects
    print(f"\n\n{'='*60}")
    print("PAYLOAD FORMAT TESTING (beegcom with info data)")
    print(f"{'='*60}")

    # Get info for beegcom
    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "beegcom"})
    r = s.post(f"{BASE}/v9/sites/beegcom/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    info = r.json()
    print(f"Info: {json.dumps(info, indent=2)}")

    # The newUrl is https://store.externulls.com/facts/tag?id=27173&limit=48&offset=%d
    # The server likely calls this URL with offset=0
    # Maybe the payload should tell the server WHICH URL to use (new, hot, search, category)
    payloads_to_test = [
        ("empty", ""),
        ("new", "new"),
        ("viewer_new", "new"),
        ("url_new", info.get("newUrl", "").replace("%d", "0")),
        ("json_offset", json.dumps({"offset": 0})),
        ("json_viewer", json.dumps({"viewer": "new"})),
        ("json_page", json.dumps({"page": 1})),
        ("json_viewer_page", json.dumps({"viewer": "new", "page": 1})),
        ("json_full", json.dumps({"viewer": "new", "page": 1, "category": False})),
    ]

    for name, payload in payloads_to_test:
        data_body = json.dumps({"payload": payload})
        r = s.post(f"{BASE}/v9/sites/beegcom/data?isTV=false", data=gz(data_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
        if r.status_code == 200:
            result = r.json()
            count = len(result) if isinstance(result, list) else "?"
            print(f"  [{name}] {r.status_code} count={count}")
            if count and count > 0 and isinstance(result, list):
                print(f"    FIRST: {json.dumps(result[0])[:300]}")
        else:
            print(f"  [{name}] {r.status_code}: {r.text[:100]}")

if __name__ == "__main__":
    main()
