#!/usr/bin/env python3
"""Direct externulls.com fetcher - bypasses broken /data endpoint."""

import json
import time
import gzip
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"
UA_APP = "okhttp/5.3.2"
UA_BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
    s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

    # Get info for multiple sites to find their external URLs
    sites_to_check = [
        "beegcom", "pornhubcom", "xvideoscom", "xhamstercom",
        "hqpornercom", "epornercom", "pornonecom", "yespornvip",
        "xnxxcom", "redtubecom", "youporncom", "spankbangcom",
        "supjavcom", "hentaimamaio", "chaturbatecom", "boundhubcom"
    ]

    print("=" * 70)
    print("SITE INFO - EXTERNAL URL TEMPLATES")
    print("=" * 70)

    site_infos = {}
    for tag in sites_to_check:
        info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": tag})
        r = s.post(f"{BASE}/v9/sites/{tag}/info", data=gz(info_body),
            headers={"hash": h, "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
        if r.status_code == 200:
            info = r.json()
            site_infos[tag] = info
            ds = info.get("dataSelector", "?")
            base = info.get("base", "?")
            new_url = info.get("newUrl", "")
            hot_url = info.get("hotUrl", "")
            list_div = info.get("listDiv", "")
            print(f"\n[{tag}] ds={ds} base={base}")
            print(f"  newUrl: {new_url}")
            print(f"  hotUrl: {hot_url}")
            print(f"  listDiv: {list_div}")
        else:
            print(f"\n[{tag}] info failed: {r.status_code}")

    # Try fetching extern URLs directly
    print(f"\n{'='*70}")
    print("DIRECT FETCH FROM EXTERNAL URLS")
    print(f"{'='*70}")

    for tag, info in site_infos.items():
        new_url = info.get("newUrl", "")
        if not new_url:
            continue

        # Replace %d with 0 for first page
        url = new_url.replace("%d", "0").replace("%s", "")
        if "%d" in url:
            url = url.replace("%d", "0")

        try:
            r = requests.get(url, timeout=15, headers={
                "User-Agent": UA_BROWSER,
                "Accept": "application/json, text/plain, */*",
                "Referer": info.get("base", ""),
            })
            content_type = r.headers.get("content-type", "")
            if r.status_code == 200:
                # Try to parse as JSON
                try:
                    data = r.json()
                    if isinstance(data, list):
                        print(f"\n[{tag}] OK - {len(data)} items (list)")
                        if len(data) > 0:
                            first = data[0]
                            print(f"  First item keys: {list(first.keys())[:10]}")
                            # Check for externulls-style data
                            if "file" in first:
                                f_data = first.get("file", {}).get("data", [{}])
                                if f_data:
                                    print(f"  Title: {f_data[0].get('cd_value', '?')[:80]}")
                                    hls = first.get("file", {}).get("hls_resources", {})
                                    print(f"  HLS: {list(hls.keys())[:5]}")
                            elif "title" in first:
                                print(f"  Title: {first.get('title', '?')[:80]}")
                                print(f"  URL: {first.get('url', first.get('link', '?'))[:80]}")
                    elif isinstance(data, dict):
                        print(f"\n[{tag}] OK - dict with keys: {list(data.keys())[:10]}")
                        # Check for nested data arrays
                        for key in data:
                            if isinstance(data[key], list) and len(data[key]) > 0:
                                print(f"  {key}: list with {len(data[key])} items")
                                break
                    else:
                        print(f"\n[{tag}] OK - {type(data).__name__} len={len(str(data))}")
                except json.JSONDecodeError:
                    print(f"\n[{tag}] OK - not JSON ({content_type}) len={len(r.text)}")
                    print(f"  Preview: {r.text[:200]}")
            else:
                print(f"\n[{tag}] FAIL - {r.status_code}: {r.text[:100]}")
        except Exception as e:
            print(f"\n[{tag}] ERROR: {e}")

    # Deep dive into beegcom externulls data
    print(f"\n{'='*70}")
    print("DEEP DIVE: beegcom externulls.com data")
    print(f"{'='*70}")

    beeg_info = site_infos.get("beegcom", {})
    new_url = beeg_info.get("newUrl", "")
    if new_url:
        url = new_url.replace("%d", "0")
        print(f"Fetching: {url}")
        r = requests.get(url, timeout=15, headers={
            "User-Agent": UA_BROWSER,
            "Accept": "application/json",
            "Referer": "https://beeg.com/",
        })
        print(f"Status: {r.status_code}, Length: {len(r.text)}")

        if r.status_code == 200:
            try:
                data = r.json()
                print(f"Type: {type(data).__name__}")
                if isinstance(data, list):
                    print(f"Items: {len(data)}")
                    if len(data) > 0:
                        first = data[0]
                        print(f"\nFirst video full structure:")
                        print(json.dumps(first, indent=2)[:3000])
                elif isinstance(data, dict):
                    print(f"Keys: {list(data.keys())}")
                    print(json.dumps(data, indent=2)[:3000])
            except json.JSONDecodeError:
                print(f"Not JSON. Preview: {r.text[:500]}")

if __name__ == "__main__":
    main()
