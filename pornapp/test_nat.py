#!/usr/bin/env python3
"""Test nat proxy routes and examine their content."""

import json
import time
import gzip
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"
PROXY_BASE = "https://porn-app.com"
UA_WEBVIEW = "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
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

    # Login first
    login_body = json.dumps({"username": "", "password": "", "android_id": "a1b2c3d4e5f6a7b8"})
    s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

    # Test nat1/nat2 routes for various sites
    print("=" * 70)
    print("NAT PROXY ROUTE TESTING")
    print("=" * 70)

    sites_to_test = [
        ("beegcom", "nat1"),
        ("pornhubcom", "nat1"),
        ("xvideoscom", "nat1"),
        ("xhamstercom", "nat1"),
        ("epornercom", "nat1"),
        ("hqpornercom", "nat1"),
        ("spankbangcom", "nat2"),
        ("yespornvip", "nat1"),
        ("pornonecom", "nat2"),
        ("redtubecom", "nat1"),
        ("youporncom", "nat1"),
        ("xnxxcom", "nat1"),
    ]

    for tag, nat in sites_to_test:
        # Get info first
        info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": tag})
        r = s.post(f"{BASE}/v9/sites/{tag}/info", data=gz(info_body),
            headers={"hash": h, "Content-Encoding": "gzip",
                     "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
        info = r.json() if r.status_code == 200 else {}
        ds = info.get("dataSelector", "?")

        # Try nat1 and nat2
        for route in ["nat1", "nat2"]:
            r = s.get(f"{PROXY_BASE}/{route}/{tag}/new",
                headers={
                    "User-Agent": UA_WEBVIEW,
                    "X-Requested-With": "com.streamdev.aiostreamer",
                    "Accept": "text/html,application/xhtml+xml",
                })
            if r.status_code == 200 and len(r.text) > 100:
                # Look for video links in the HTML
                html = r.text
                # Count video links (typical patterns)
                import re
                # Look for common video link patterns
                video_links = re.findall(r'href=["\']([^"\']*(?:video|watch|view|play)[^"\']*)["\']', html, re.I)
                thumbnails = re.findall(r'(?:poster|thumbnail|thumb|img)[^"\']*["\']([^"\']+\.(?:jpg|jpeg|png|webp)[^"\']*)["\']', html, re.I)
                print(f"  [{tag}] {route} OK len={len(html)} ds={ds} videos~{len(video_links)} thumbs~{len(thumbnails)}")
                if video_links:
                    print(f"    Video links: {video_links[:3]}")
                break  # Only show first working route
            else:
                if route == "nat2":
                    print(f"  [{tag}] both nat routes failed (nat1={r.status_code}) ds={ds}")

    # Examine nat1/beegcom HTML in detail
    print(f"\n{'='*70}")
    print("DETAILED NAT1/BEEGCOM ANALYSIS")
    print(f"{'='*70}")

    r = s.get(f"{PROXY_BASE}/nat1/beegcom/new",
        headers={"User-Agent": UA_WEBVIEW, "X-Requested-With": "com.streamdev.aiostreamer"})
    html = r.text
    print(f"HTML length: {len(html)}")
    print(f"First 2000 chars:\n{html[:2000]}")
    print(f"\n...\nLast 1000 chars:\n{html[-1000:]}")

    # Look for video data patterns
    import re
    # Find all script tags content
    scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.S)
    for i, script in enumerate(scripts):
        if len(script) > 50 and ('video' in script.lower() or 'json' in script.lower() or 'data' in script.lower()):
            print(f"\n--- Script #{i} (len={len(script)}) ---")
            print(script[:500])

    # Find all JSON-like data in the page
    json_blocks = re.findall(r'(\{[^{}]*"(?:title|url|thumb|video|src)"[^{}]*\})', html)
    if json_blocks:
        print(f"\n--- JSON-like blocks ({len(json_blocks)}) ---")
        for block in json_blocks[:5]:
            print(f"  {block[:200]}")

    # Test different viewer types
    print(f"\n{'='*70}")
    print("TESTING DIFFERENT VIEWER TYPES")
    print(f"{'='*70}")

    for viewer in ["new", "hot", "views"]:
        r = s.get(f"{PROXY_BASE}/nat1/beegcom/{viewer}",
            headers={"User-Agent": UA_WEBVIEW, "X-Requested-With": "com.streamdev.aiostreamer"})
        print(f"  [beegcom/{viewer}] {r.status_code} len={len(r.text)}")

    # Test pagination
    print(f"\n--- Pagination ---")
    for page in [1, 2, 3]:
        r = s.get(f"{PROXY_BASE}/nat1/beegcom/new/{page}",
            headers={"User-Agent": UA_WEBVIEW, "X-Requested-With": "com.streamdev.aiostreamer"})
        print(f"  [beegcom/new/{page}] {r.status_code} len={len(r.text)}")

    # Test category route
    print(f"\n--- Category ---")
    r = s.get(f"{PROXY_BASE}/nat1/beegcom/category/EvaElfie",
        headers={"User-Agent": UA_WEBVIEW, "X-Requested-With": "com.streamdev.aiostreamer"})
    print(f"  [beegcom/category/EvaElfie] {r.status_code} len={len(r.text)}")

    # Test search
    print(f"\n--- Search ---")
    r = s.get(f"{PROXY_BASE}/nat1/beegcom/search/test",
        headers={"User-Agent": UA_WEBVIEW, "X-Requested-With": "com.streamdev.aiostreamer"})
    print(f"  [beegcom/search/test] {r.status_code} len={len(r.text)}")

    # Test nat2 for peekvidscom (mentioned in traffic logs)
    print(f"\n{'='*70}")
    print("TESTING nat2/peekvidscom (from traffic logs)")
    print(f"{'='*70}")
    r = s.get(f"{PROXY_BASE}/nat2/peekvidscom/new",
        headers={"User-Agent": UA_WEBVIEW, "X-Requested-With": "com.streamdev.aiostreamer"})
    print(f"  [nat2/peekvidscom/new] {r.status_code} len={len(r.text)}")
    if r.status_code == 200:
        print(f"  HTML preview: {r.text[:1000]}")

    # Test without X-Requested-With header
    print(f"\n--- Without X-Requested-With ---")
    r = s.get(f"{PROXY_BASE}/nat1/beegcom/new",
        headers={"User-Agent": UA_WEBVIEW})
    print(f"  [no-xrw] {r.status_code} len={len(r.text)}")

if __name__ == "__main__":
    main()
