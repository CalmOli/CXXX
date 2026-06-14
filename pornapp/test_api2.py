#!/usr/bin/env python3
"""Test nat proxy routes, different data selectors, and more sites."""

import json
import time
import gzip
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"
UA_APP = "okhttp/5.3.2"
UA_WEBVIEW = "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"

def load_pubkey():
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    from cryptography.hazmat.primitives import serialization
    with open("pubkey.pem", "rb") as f:
        pem_data = f.read()
    lines = pem_data.decode().strip().split('\n')
    if not lines[0].startswith('-----'):
        der_b64 = ''.join(lines)
        der_bytes = base64.b64decode(der_b64)
        return serialization.load_der_public_key(der_bytes)
    return serialization.load_pem_public_key(pem_data)

def gen_hash(pub_key, android_id="a1b2c3d4e5f6a7b8"):
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    ts = int(time.time())
    hash_info = OrderedDict([
        ("id", android_id),
        ("isTV", False),
        ("loginStatus", OrderedDict([
            ("pro", 0), ("status", 0), ("token", ""),
            ("unixtime", ts), ("user_id", 0)
        ])),
        ("packageName", "com.streamdev.aiostreamer"),
        ("signatures", ["VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw="]),
        ("time", ts),
        ("version", 6643)
    ])
    json_str = json.dumps(hash_info, separators=(',', ':'))
    encrypted = pub_key.encrypt(json_str.encode('utf-8'), asym_padding.PKCS1v15())
    return base64.b64encode(encrypted).decode('ascii')

def gzip_body(data):
    if isinstance(data, str):
        data = data.encode('utf-8')
    return gzip.compress(data)

def main():
    pub_key = load_pubkey()
    session = requests.Session()
    h = gen_hash(pub_key)

    # Login first
    login_body = json.dumps({"username": "", "password": "", "android_id": "a1b2c3d4e5f6a7b8"})
    r = session.post(f"{BASE}/v9/login",
        data=gzip_body(login_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    print(f"[login] {r.status_code}: {r.text[:200]}")

    # Get all sites info from our saved data
    with open("sites.json") as f:
        all_sites = json.load(f)

    # Test info for sites across all categories
    print("\n" + "=" * 60)
    print("TEST INFO ENDPOINTS (sample from each category)")
    print("=" * 60)

    tested = []
    for category, sites in all_sites.items():
        print(f"\n--- {category} ---")
        for site_info in sites[:3]:  # test first 3 from each category
            tag = site_info.get("tag", "")
            name = site_info.get("name", "")

            info_body = json.dumps({
                "filter": {"viewer": "new", "page": 1},
                "globalSearch": False,
                "porntabs": False,
                "site": tag
            })
            r = session.post(f"{BASE}/v9/sites/{tag}/info",
                data=gzip_body(info_body),
                headers={"hash": h, "Content-Encoding": "gzip",
                         "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

            if r.status_code == 200:
                info = r.json()
                ds = info.get("dataSelector", "?")
                base_url = info.get("base", "?")
                new_url = info.get("newUrl", "")[:80]
                print(f"  [{tag}] OK dataSelector={ds} base={base_url}")
                tested.append((tag, info))
            else:
                print(f"  [{tag}] {r.status_code}: {r.text[:100]}")

    # Now test /data for sites that had successful /info
    print("\n" + "=" * 60)
    print("TEST /data FOR ALL SUCCESSFUL INFO SITES")
    print("=" * 60)

    working_sites = []
    for tag, info in tested:
        ds = info.get("dataSelector", "")
        base = info.get("base", "")
        new_url = info.get("newUrl", "")

        # Try /data with empty payload
        data_body = json.dumps({"payload": ""})
        r = session.post(f"{BASE}/v9/sites/{tag}/data?isTV=false",
            data=gzip_body(data_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

        if r.status_code == 200:
            result = r.json()
            count = len(result) if isinstance(result, list) else "N/A"
            if count and count != 0 and count != "N/A":
                working_sites.append(tag)
                print(f"  [{tag}] dataSelector={ds} => {count} videos!")
                if count > 0 and isinstance(result, list) and len(result) > 0:
                    print(f"    Sample: {json.dumps(result[0], indent=2)[:300]}")
            else:
                print(f"  [{tag}] dataSelector={ds} => {count} (empty)")
        else:
            err = r.text[:150]
            print(f"  [{tag}] dataSelector={ds} => {r.status_code}: {err}")

    # Test nat1/nat2 routes
    print("\n" + "=" * 60)
    print("TEST NAT PROXY ROUTES")
    print("=" * 60)
    nat_sites = [tag for tag, info in tested if info.get("dataSelector") in ["nat1", "nat2"]]
    print(f"Sites using nat proxy: {nat_sites}")

    for tag in nat_sites[:5]:
        info = dict(tested)[tag] if isinstance(tested, dict) else next(i for t, i in tested if t == tag)
        ds = info.get("dataSelector", "")

        # Try nat route
        r = session.get(f"{BASE}/{ds}/{tag}/new",
            headers={
                "User-Agent": UA_WEBVIEW,
                "hash": h,
                "X-Requested-With": "com.streamdev.aiostreamer"
            })
        print(f"  [{tag}] nat GET {r.status_code}: {r.text[:200]}")

    # Test starter endpoint
    print("\n" + "=" * 60)
    print("TEST /starter ENDPOINT")
    print("=" * 60)
    for tag, info in tested[:5]:
        r = session.get(f"{BASE}/v9/starter?site={tag}",
            headers={"hash": h, "User-Agent": UA_APP})
        print(f"  [{tag}] starter {r.status_code}: {r.text[:300]}")

    # Test link endpoint
    print("\n" + "=" * 60)
    print("TEST /link ENDPOINT (beegcom)")
    print("=" * 60)
    link_body = json.dumps({
        "filter": {"viewer": "new", "page": 1},
        "globalSearch": False,
        "porntabs": False,
        "site": "beegcom"
    })
    r = session.post(f"{BASE}/v9/sites/beegcom/link",
        data=gzip_body(link_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    print(f"[link] {r.status_code}: {r.text[:500]}")

    # Try with URL-based payload for beegcom (which uses externulls API)
    print("\n" + "=" * 60)
    print("TEST /data WITH URL PAYLOAD (beegcom)")
    print("=" * 60)
    # The info returned newUrl for beegcom: https://store.externulls.com/facts/tag?id=27173&limit=48&offset=%d
    # Maybe the payload should be the URL?
    url_payloads = [
        ("externulls_url", "https://store.externulls.com/facts/tag?id=27173&limit=48&offset=0"),
        ("offset_0", "0"),
        ("page_1", "1"),
        ("page_1_str", "page=1"),
        ("new_viewer", "new"),
    ]
    for name, payload in url_payloads:
        data_body = json.dumps({"payload": payload})
        r = session.post(f"{BASE}/v9/sites/beegcom/data?isTV=false",
            data=gzip_body(data_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
        print(f"  [{name}] {r.status_code}: {r.text[:200]}")

    if working_sites:
        print(f"\n\nWORKING SITES: {working_sites}")

if __name__ == "__main__":
    main()
