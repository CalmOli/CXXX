#!/usr/bin/env python3
"""Test exact app lifecycle flow with server time sync."""

import json
import time
import gzip
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"
UA_APP = "okhttp/5.3.2"
UA_DALVIK = "Dalvik/2.1.0 (Linux; U; Android 9; SM-G960F Build/PPR1.180610.011)"

def load_pubkey():
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    from cryptography.hazmat.primitives import serialization
    with open("pubkey.pem", "rb") as f:
        pem_data = f.read()
    lines = pem_data.decode().strip().split('\n')
    if not lines[0].startswith('-----'):
        return serialization.load_der_public_key(base64.b64decode(''.join(lines)))
    return serialization.load_pem_public_key(pem_data)

def gen_hash(pub_key, ts=None):
    from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
    if ts is None:
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

    print("=" * 70)
    print("PHASE 1: Initial setup - no hash needed")
    print("=" * 70)

    # Step 1: checkInfo (no hash)
    r = s.get(f"{BASE}/v9/checkInfo", headers={"User-Agent": UA_APP})
    print(f"[checkInfo no-hash] {r.status_code}: {r.text[:300]}")

    # Step 2: update check (no hash)
    r = s.get(f"{BASE}/update", headers={"User-Agent": UA_APP})
    print(f"[update] {r.status_code}: {r.text[:300]}")

    # Step 3: unixTime - first try WITHOUT hash to get server time
    print(f"\n--- unixTime without hash ---")
    ts = int(time.time())
    r = s.post(f"{BASE}/v9/unixTime",
        data={"deviceUnixTime": str(ts), "version": "6643"},
        headers={"User-Agent": UA_APP})
    print(f"[unixTime no-hash] {r.status_code}: {r.text[:300]}")

    # Generate hash using our time
    h = gen_hash(pub_key)
    print(f"\n--- Generating hash with ts={ts} ---")

    # Step 4: checkInfo WITH hash
    r = s.get(f"{BASE}/v9/checkInfo", headers={"hash": h, "User-Agent": UA_APP})
    check_info = r.json() if r.status_code == 200 else {}
    print(f"[checkInfo] {r.status_code}: {json.dumps(check_info)[:300]}")

    # Extract server time if available
    server_time = check_info.get("unixtime")
    if server_time:
        print(f"  Server time from checkInfo: {server_time}")
        print(f"  Our time: {ts}")
        print(f"  Diff: {server_time - ts}s")

    # Step 5: unixTime WITH hash
    ts2 = int(time.time())
    h2 = gen_hash(pub_key, ts2)
    r = s.post(f"{BASE}/v9/unixTime",
        data={"deviceUnixTime": str(ts2), "version": "6643"},
        headers={"hash": h2, "User-Agent": UA_APP})
    print(f"[unixTime] {r.status_code}: {r.text[:300]}")

    # Step 6: Device registration
    r = s.post(f"{BASE}/v9/device",
        data={"android_id": "a1b2c3d4e5f6a7b8"},
        headers={"hash": h2, "Authorization": "Bearer ", "User-Agent": UA_APP})
    print(f"[device] {r.status_code}: {r.text[:300]}")

    # Step 7: Login
    login_body = json.dumps({"username": "", "password": "", "android_id": "a1b2c3d4e5f6a7b8"})
    r = s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h2, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    login_resp = r.json() if r.status_code == 200 else {}
    print(f"[login] {r.status_code}: {r.text[:300]}")

    # Check if login gives us a token or session cookie
    print(f"\n  Session cookies: {dict(s.cookies)}")
    print(f"  Response headers: {dict(r.headers)}")

    # Step 8: Get sites
    r = s.get(f"{BASE}/v9/sites", headers={"hash": h2, "User-Agent": UA_APP})
    print(f"[sites] {r.status_code}: len={len(r.text)}")

    # Step 9: Test beegcom full flow
    print(f"\n{'='*70}")
    print("PHASE 5: Browsing beegcom - full flow")
    print(f"{'='*70}")

    # Refresh hash
    ts3 = int(time.time())
    h3 = gen_hash(pub_key, ts3)

    # 9a: Login again (app does this)
    r = s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h3, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    print(f"[login-refresh] {r.status_code}: {r.text[:200]}")
    login_resp = r.json() if r.status_code == 200 else {}

    # 9b: Get info
    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "beegcom"})
    r = s.post(f"{BASE}/v9/sites/beegcom/info", data=gz(info_body),
        headers={"hash": h3, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    info = r.json() if r.status_code == 200 else {}
    print(f"[info] {r.status_code}: dataSelector={info.get('dataSelector')} base={info.get('base')}")

    # 9c: Get categories
    r = s.get(f"{BASE}/v9/categories?site=beegcom", headers={"hash": h3, "User-Agent": UA_APP})
    cats = r.json() if r.status_code == 200 else {}
    print(f"[categories] {r.status_code}: {str(cats)[:200]}")

    # 9d: Try /link endpoint
    link_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "beegcom"})
    r = s.post(f"{BASE}/v9/sites/beegcom/link", data=gz(link_body),
        headers={"hash": h3, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    print(f"[link] {r.status_code}: {r.text[:300]}")

    # 9e: Try /data endpoint with empty payload
    data_body = json.dumps({"payload": ""})
    r = s.post(f"{BASE}/v9/sites/beegcom/data?isTV=false", data=gz(data_body),
        headers={"hash": h3, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    print(f"[data] {r.status_code}: {r.text[:300]}")

    # 9f: Try the nat1 proxy route
    print(f"\n--- NAT proxy routes ---")
    r = s.get(f"https://porn-app.com/nat1/beegcom/new",
        headers={"User-Agent": "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
                 "X-Requested-With": "com.streamdev.aiostreamer"})
    print(f"[nat1/beegcom] {r.status_code}: {r.text[:300]}")

    # 9g: Try starter
    r = s.get(f"{BASE}/v9/starter?site=beegcom", headers={"hash": h3, "User-Agent": UA_APP})
    print(f"[starter] {r.status_code}: {r.text[:500]}")

    # Test multiple sites with full flow
    print(f"\n{'='*70}")
    print("Testing more sites with full login+info+data flow")
    print(f"{'='*70}")

    test_sites = [
        "pornhubcom", "xvideoscom", "xhamstercom", "spankbangcom",
        "epornercom", "hqpornercom", "pornonecom", "yespornvip",
        "xnxxcom", "redtubecom", "youporncom", "tube8com",
        "chaturbatecom", "stripchatcom", "supjavcom", "hentaimamaio",
        "boundhubcom", "familyporntv", "ashemaletubecom", "trannyone"
    ]

    for tag in test_sites:
        ts = int(time.time())
        h = gen_hash(pub_key, ts)

        # Login
        s.post(f"{BASE}/v9/login", data=gz(login_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

        # Info
        info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": tag})
        r = s.post(f"{BASE}/v9/sites/{tag}/info", data=gz(info_body),
            headers={"hash": h, "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
        info = r.json() if r.status_code == 200 else {}
        ds = info.get("dataSelector", "?")

        # Data
        data_body = json.dumps({"payload": ""})
        r = s.post(f"{BASE}/v9/sites/{tag}/data?isTV=false", data=gz(data_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

        if r.status_code == 200:
            result = r.json()
            count = len(result) if isinstance(result, list) else "?"
            if count and count > 0:
                print(f"  {tag:<25} ds={ds:<12} => {count} videos!")
                if isinstance(result, list) and len(result) > 0:
                    print(f"    {json.dumps(result[0])[:300]}")
            else:
                print(f"  {tag:<25} ds={ds:<12} => empty")
        else:
            print(f"  {tag:<25} ds={ds:<12} => {r.status_code}: {r.text[:80]}")

    # Test with different User-Agents
    print(f"\n{'='*70}")
    print("Testing with different User-Agents for /data")
    print(f"{'='*70}")

    uas = {
        "okhttp": UA_APP,
        "chrome_mobile": "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
        "dalvik": UA_DALVIK,
        "desktop": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    }

    ts = int(time.time())
    h = gen_hash(pub_key, ts)
    s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

    for ua_name, ua in uas.items():
        data_body = json.dumps({"payload": ""})
        r = s.post(f"{BASE}/v9/sites/beegcom/data?isTV=false", data=gz(data_body),
            headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": ua})
        print(f"  [{ua_name}] {r.status_code}: {r.text[:150]}")

if __name__ == "__main__":
    main()
