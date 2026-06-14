#!/usr/bin/env python3
"""Debug script for porn-app.com API - tests hash generation and full request flow."""

import json
import time
import gzip
import sys
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"

def load_pubkey():
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    from cryptography.hazmat.primitives import hashes, serialization
    with open("pubkey.pem", "rb") as f:
        pem_data = f.read()
    # pubkey.pem contains base64 DER, convert to proper PEM
    lines = pem_data.decode().strip().split('\n')
    if not lines[0].startswith('-----'):
        der_b64 = ''.join(lines)
        der_bytes = base64.b64decode(der_b64)
        pub_key = serialization.load_der_public_key(der_bytes)
    else:
        pub_key = serialization.load_pem_public_key(pem_data)
    return pub_key

def gen_hash(pub_key, android_id="a1b2c3d4e5f6a7b8", login_status=None, ts=None):
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    from cryptography.hazmat.primitives import hashes

    if ts is None:
        ts = int(time.time())

    if login_status is None:
        login_status = OrderedDict([
            ("pro", 0),
            ("status", 0),
            ("token", ""),
            ("unixtime", ts),
            ("user_id", 0)
        ])

    hash_info = OrderedDict([
        ("id", android_id),
        ("isTV", False),
        ("loginStatus", login_status),
        ("packageName", "com.streamdev.aiostreamer"),
        ("signatures", ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="]),
        ("time", ts),
        ("version", 6643)
    ])

    json_str = json.dumps(hash_info, separators=(',', ':'))
    print(f"[HASH JSON] {json_str}")

    encrypted = pub_key.encrypt(
        json_str.encode('utf-8'),
        asym_padding.PKCS1v15()
    )
    return base64.b64encode(encrypted).decode('ascii').replace('\n', '').replace('\r', '')

def gen_hash_variants(pub_key):
    """Generate multiple hash variants to test which one the server accepts."""
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    from cryptography.hazmat.primitives import hashes

    ts = int(time.time())

    variants = {
        "v1_all_fields": OrderedDict([
            ("id", "a1b2c3d4e5f6a7b8"),
            ("isTV", False),
            ("loginStatus", OrderedDict([
                ("pro", 0), ("status", 0), ("token", ""),
                ("unixtime", ts), ("user_id", 0)
            ])),
            ("packageName", "com.streamdev.aiostreamer"),
            ("signatures", ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="]),
            ("time", ts),
            ("version", 6643)
        ]),
        "v2_no_isTV": OrderedDict([
            ("id", "a1b2c3d4e5f6a7b8"),
            ("loginStatus", OrderedDict([
                ("pro", 0), ("status", 0), ("token", ""),
                ("unixtime", ts), ("user_id", 0)
            ])),
            ("packageName", "com.streamdev.aiostreamer"),
            ("signatures", ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="]),
            ("time", ts),
            ("version", 6643)
        ]),
        "v3_no_zero_login": OrderedDict([
            ("id", "a1b2c3d4e5f6a7b8"),
            ("isTV", False),
            ("loginStatus", OrderedDict([
                ("token", ""), ("unixtime", ts)
            ])),
            ("packageName", "com.streamdev.aiostreamer"),
            ("signatures", ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="]),
            ("time", ts),
            ("version", 6643)
        ]),
        "v4_alpha_sorted": dict(sorted({
            "id": "a1b2c3d4e5f6a7b8",
            "isTV": False,
            "loginStatus": {"pro": 0, "status": 0, "token": "", "unixtime": ts, "user_id": 0},
            "packageName": "com.streamdev.aiostreamer",
            "signatures": ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="],
            "time": ts,
            "version": 6643
        }.items())),
    }

    results = {}
    for name, payload in variants.items():
        json_str = json.dumps(payload, separators=(',', ':'))
        encrypted = pub_key.encrypt(
            json_str.encode('utf-8'),
            asym_padding.PKCS1v15()
        )
        h = base64.b64encode(encrypted).decode('ascii').replace('\n', '').replace('\r', '')
        results[name] = (h, json_str)
    return results

def gzip_body(data):
    """Gzip compress data for request body."""
    if isinstance(data, str):
        data = data.encode('utf-8')
    return gzip.compress(data)

UA_APP = "okhttp/5.3.2"

def main():
    pub_key = load_pubkey()
    session = requests.Session()

    print("=" * 60)
    print("STEP 1: Generate hash")
    print("=" * 60)
    h = gen_hash(pub_key)
    print(f"[HASH] {h[:80]}...")

    print("\n" + "=" * 60)
    print("STEP 2: Test hash with checkInfo (GET)")
    print("=" * 60)
    r = session.get(f"{BASE}/v9/checkInfo", headers={"hash": h, "User-Agent": UA_APP})
    print(f"[checkInfo] {r.status_code}: {r.text[:500]}")

    print("\n" + "=" * 60)
    print("STEP 3: Login (POST, gzip JSON)")
    print("=" * 60)
    login_body = json.dumps({"username": "", "password": "", "android_id": "a1b2c3d4e5f6a7b8"})
    login_gz = gzip_body(login_body)
    r = session.post(f"{BASE}/v9/login",
        data=login_gz,
        headers={
            "hash": h,
            "Authorization": "Bearer ",
            "Content-Encoding": "gzip",
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": UA_APP
        })
    print(f"[login] {r.status_code}: {r.text[:500]}")
    login_resp = r.json() if r.status_code == 200 else {}

    print("\n" + "=" * 60)
    print("STEP 4: Register device (POST, form-urlencoded)")
    print("=" * 60)
    r = session.post(f"{BASE}/v9/device",
        data={"android_id": "a1b2c3d4e5f6a7b8"},
        headers={
            "hash": h,
            "Authorization": "Bearer ",
            "User-Agent": UA_APP
        })
    print(f"[device] {r.status_code}: {r.text[:500]}")

    print("\n" + "=" * 60)
    print("STEP 5: unixTime sync (POST, form-urlencoded)")
    print("=" * 60)
    ts = int(time.time())
    r = session.post(f"{BASE}/v9/unixTime",
        data={"deviceUnixTime": str(ts), "version": "6643"},
        headers={
            "hash": h,
            "User-Agent": UA_APP
        })
    print(f"[unixTime] {r.status_code}: {r.text[:500]}")

    print("\n" + "=" * 60)
    print("STEP 6: Get sites list")
    print("=" * 60)
    r = session.get(f"{BASE}/v9/sites", headers={"hash": h, "User-Agent": UA_APP})
    print(f"[sites] {r.status_code}: (length={len(r.text)})")

    print("\n" + "=" * 60)
    print("STEP 7: Get site info for beegcom")
    print("=" * 60)
    info_body = json.dumps({
        "filter": {"viewer": "new", "page": 1},
        "globalSearch": False,
        "porntabs": False,
        "site": "beegcom"
    })
    info_gz = gzip_body(info_body)
    r = session.post(f"{BASE}/v9/sites/beegcom/info",
        data=info_gz,
        headers={
            "hash": h,
            "Content-Encoding": "gzip",
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": UA_APP
        })
    print(f"[info] {r.status_code}: {r.text[:800]}")
    info_resp = r.json() if r.status_code == 200 else {}

    print("\n" + "=" * 60)
    print("STEP 8: Get categories for beegcom")
    print("=" * 60)
    r = session.get(f"{BASE}/v9/categories?site=beegcom",
        headers={"hash": h, "User-Agent": UA_APP})
    print(f"[categories] {r.status_code}: {r.text[:500]}")

    print("\n" + "=" * 60)
    print("STEP 9: Call login again (app does this before data)")
    print("=" * 60)
    r = session.post(f"{BASE}/v9/login",
        data=login_gz,
        headers={
            "hash": h,
            "Authorization": "Bearer ",
            "Content-Encoding": "gzip",
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": UA_APP
        })
    print(f"[login2] {r.status_code}: {r.text[:500]}")

    # Try different payload formats for /data
    payloads = [
        ("empty_string", ""),
        ("filter_json", json.dumps({"viewer": "new", "page": 1, "category": False})),
        ("filter_json_gson", json.dumps({"viewer": "new", "page": 1})),
        ("site_info_filter", info_resp.get("filter", "") if info_resp else ""),
    ]

    print("\n" + "=" * 60)
    print("STEP 10: Call /data with various payloads")
    print("=" * 60)
    for name, payload in payloads:
        data_body = json.dumps({"payload": payload})
        data_gz = gzip_body(data_body)

        r = session.post(f"{BASE}/v9/sites/beegcom/data?isTV=false",
            data=data_gz,
            headers={
                "hash": h,
                "Authorization": "Bearer ",
                "Content-Encoding": "gzip",
                "Content-Type": "application/json; charset=utf-8",
                "User-Agent": UA_APP
            })
        resp_text = r.text[:200]
        print(f"  [{name}] {r.status_code}: {resp_text}")

    # Also try without gzip
    print("\n--- Without gzip ---")
    for name, payload in payloads:
        data_body = json.dumps({"payload": payload})
        r = session.post(f"{BASE}/v9/sites/beegcom/data?isTV=false",
            data=data_body,
            headers={
                "hash": h,
                "Authorization": "Bearer ",
                "Content-Type": "application/json; charset=utf-8",
                "User-Agent": UA_APP
            })
        resp_text = r.text[:200]
        print(f"  [{name}] {r.status_code}: {resp_text}")

    # Try different sites
    print("\n" + "=" * 60)
    print("STEP 11: Try /data for multiple sites")
    print("=" * 60)
    test_sites = ["beegcom", "neporn", "yespornvip", "pornhub", "xvideos", "xhamster", "spankbang", "redtube"]
    for site in test_sites:
        # Get info first
        info_body = json.dumps({
            "filter": {"viewer": "new", "page": 1},
            "globalSearch": False,
            "porntabs": False,
            "site": site
        })
        info_gz = gzip_body(info_body)
        r = session.post(f"{BASE}/v9/sites/{site}/info",
            data=info_gz,
            headers={
                "hash": h,
                "Content-Encoding": "gzip",
                "Content-Type": "application/json; charset=utf-8",
                "User-Agent": UA_APP
            })
        info_ok = r.status_code == 200

        # Call data with empty payload
        data_body = json.dumps({"payload": ""})
        data_gz = gzip_body(data_body)
        r = session.post(f"{BASE}/v9/sites/{site}/data?isTV=false",
            data=data_gz,
            headers={
                "hash": h,
                "Authorization": "Bearer ",
                "Content-Encoding": "gzip",
                "Content-Type": "application/json; charset=utf-8",
                "User-Agent": UA_APP
            })
        result = r.json() if r.status_code == 200 else r.text[:100]
        count = len(result) if isinstance(result, list) else "N/A"
        print(f"  [{site}] info={info_ok} data={r.status_code} count={count}")

    print("\n" + "=" * 60)
    print("STEP 12: Test hash variants against unixTime")
    print("=" * 60)
    variants = gen_hash_variants(pub_key)
    for name, (vh, vjson) in variants.items():
        ts = int(time.time())
        r = session.post(f"{BASE}/v9/unixTime",
            data={"deviceUnixTime": str(ts), "version": "6643"},
            headers={
                "hash": vh,
                "User-Agent": UA_APP
            })
        print(f"  [{name}] {r.status_code}: {r.text[:200]}")
        print(f"    JSON: {vjson}")

if __name__ == "__main__":
    main()
