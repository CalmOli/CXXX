#!/usr/bin/env python3
"""Test all 8 providers through the porn-app.com API lifecycle."""

import json, collections, time, subprocess, base64, sys, os
import requests
import gzip
import cloudscraper

BASE_URL = "https://porn-app.com/api/v9"
PUBKEY_PATH = "/tmp/pubkey.pem"

# Provider config: (sitetag, listing_url, label)
PROVIDERS = [
    ("xxxtube",         "https://x-x-x.tube/videos/",              "x-x-x.tube"),
    ("shyfapnet",       "https://shyfap.net/videos/",              "shyfap.net"),
    ("hardsexvidscom",  "https://hardsexvids.com/",                "hardsexvids.com"),
    ("neporncom",       "https://neporn.com/",                     "neporn.com"),
    ("xasiatcom",       "https://xasiat.com/",                     "xasiat.com"),
    ("yespornvip",      "https://yesporn.vip/",                    "yesporn.vip"),
    ("taboodudecom",    "https://taboodude.com/",                  "taboodude.com"),
    ("beegcom",         "https://beeg.com/",                       "beeg.com"),
]

scraper = cloudscraper.create_scraper()

def generate_hash():
    ts = int(time.time())
    payload = collections.OrderedDict([
        ('id', 'a1b2c3d4e5f6a7b8'), ('isTV', False),
        ('loginStatus', collections.OrderedDict([
            ('pro', 0), ('status', 0), ('token', ""), ('unixtime', ts), ('user_id', 0)
        ])),
        ('packageName', 'com.streamdev.aiostreamer'),
        ('signatures', ['VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw=']),
        ('time', ts), ('version', 6643)
    ])
    json_str = json.dumps(payload, separators=(',', ':'))
    cmd = ['openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', PUBKEY_PATH,
           '-pkeyopt', 'rsa_padding_mode:pkcs1']
    p1 = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    enc_out, err = p1.communicate(input=json_str.encode())
    if p1.returncode != 0:
        return ""
    return base64.b64encode(enc_out).decode('utf-8').strip()

def api_request(endpoint, method="GET", payload=None, gzip_body=True):
    url = f"{BASE_URL}{endpoint}"
    headers = {
        "User-Agent": "okhttp/5.3.2",
        "Authorization": "Bearer ",
        "Accept-Encoding": "gzip",
        "hash": generate_hash()
    }
    if method == "GET":
        resp = requests.get(url, headers=headers, timeout=30)
    else:
        if gzip_body:
            headers["Content-Encoding"] = "gzip"
            headers["Content-Type"] = "application/json; charset=UTF-8"
            resp = requests.post(url, headers=headers,
                                 data=gzip.compress(json.dumps(payload).encode()),
                                 timeout=30)
        else:
            headers["Content-Type"] = "application/json; charset=UTF-8"
            resp = requests.post(url, headers=headers, json=payload, timeout=30)
    return resp

def verify_stream_url(url, label=""):
    if not url:
        return "NO URL"
    try:
        resp = requests.get(url, headers={"User-Agent": "okhttp/5.3.2"},
                            stream=True, allow_redirects=True, timeout=15)
        ct = resp.headers.get("Content-Type", "")
        cl = resp.headers.get("Content-Length", "unknown")
        first_bytes = resp.raw.read(16)
        resp.close()

        if resp.status_code != 200:
            return f"HTTP {resp.status_code}"
        if "video/" in ct:
            return f"OK (video/{ct.split('/')[-1]}, {cl}B)"
        if first_bytes[:3] == b'\x00\x00\x00':
            return f"OK (MP4, {cl}B)"
        if b'GIF' in first_bytes[:6]:
            return "BLOCKED (tracking pixel)"
        return f"UNKNOWN (type={ct}, first={first_bytes[:8].hex()})"
    except Exception as e:
        return f"FETCH ERROR: {e}"

def test_provider(sitetag, listing_url, label):
    print(f"\n{'='*70}")
    print(f"TESTING: {sitetag} ({label})")
    print(f"{'='*70}")

    # Step 1: Fetch listing page
    print(f"\n[1/4] Fetching listing page...")
    try:
        listing_html = scraper.get(listing_url, timeout=30).text
    except Exception as e:
        # Try with requests directly as fallback
        try:
            listing_html = requests.get(listing_url, headers={"User-Agent": "Mozilla/5.0"}, timeout=30).text
        except Exception as e2:
            return f"LISTING FETCH FAIL: {e}"
    print(f"      Got {len(listing_html)} bytes")

    # Step 2: Call /data with listing HTML
    print(f"[2/4] Calling /data...")
    data_resp = api_request(f"/sites/{sitetag}/data?isTV=false",
                            method="POST",
                            payload={"payload": listing_html})
    if data_resp.status_code != 200:
        return f"/data returned {data_resp.status_code}: {data_resp.text[:200]}"

    data = data_resp.json()
    if isinstance(data, dict):
        # Check for nested list
        for key in ['videos', 'items', 'data', 'results']:
            if key in data and isinstance(data[key], list):
                data = data[key]
                break
        else:
            data = [data]

    # Filter out NATIVE_AD items
    videos = [v for v in data if isinstance(v, dict) and v.get("link") != "NATIVE_AD"]
    if not videos:
        # Dump first item for debugging
        if data:
            first = data[0]
            return f"No valid videos found. First item type={type(first).__name__}, keys={list(first.keys()) if isinstance(first,dict) else 'N/A'}"
        return "No videos found (empty list)"

    vid = videos[0]
    vid_id = vid.get("video_id", vid.get("id", "unknown"))
    vid_url = vid.get("link", vid.get("url", listing_url))
    vid_title = vid.get("title", "(no title)")
    print(f"      Found video: {vid_title[:80]}")
    print(f"      URL: {vid_url}")

    # Step 3: Fetch video page HTML
    print(f"[3/4] Fetching video page...")
    try:
        video_html = scraper.get(vid_url, timeout=30).text
    except Exception as e:
        return f"VIDEO FETCH FAIL: {e}"
    print(f"      Got {len(video_html)} bytes")

    # Step 4: Call /stream with video HTML + videoObject
    print(f"[4/4] Calling /stream...")
    stream_payload = {
        "payload": video_html,
        "videoObject": {
            "video_id": vid_id,
            "sourceLink": vid_url,
            "streamLink": "",
            "title": vid_title,
            "image": vid.get("img", vid.get("image", "")),
            "duration": vid.get("duration", "0:00"),
            "quality": vid.get("quality", 0),
            "webm": vid.get("webm", vid.get("preview", "")),
            "site": sitetag,
            "seconds": 0,
            "embedLink": "",
            "hosterLink": "",
            "hosterSite": "",
            "download": False,
            "test": False
        }
    }
    stream_resp = api_request(f"/sites/{sitetag}/stream?isTV=false",
                               method="POST",
                               payload=stream_payload)
    if stream_resp.status_code != 200:
        return f"/stream returned {stream_resp.status_code}: {stream_resp.text[:200]}"

    links = stream_resp.json()
    if not links:
        return "/stream returned empty array"

    print(f"      Got {len(links)} stream URLs:")
    for link in links:
        stream_url = link.get("stream", link.get("streamLink", ""))
        quality = link.get("quality", "?")
        link_type = link.get("type", "?")
        if stream_url:
            result = verify_stream_url(stream_url, label)
            print(f"        [{link_type}] {quality}: {result}")
        else:
            print(f"        [{link_type}] {quality}: no URL")

    working = sum(1 for l in links if verify_stream_url(l.get("stream", l.get("streamLink", "")), label).startswith("OK"))
    return f"SUCCESS: {len(links)} links, {working} playable"

if __name__ == "__main__":
    # Login first
    print("Logging in...")
    login_resp = api_request("/login", method="POST", payload={})
    if login_resp.status_code == 200:
        token = login_resp.json().get("token", "")
        print(f"  Token: {'(empty)' if not token else token[:20]+'...'}")
    else:
        print(f"  Login returned {login_resp.status_code}: {login_resp.text[:100]}")

    results = {}
    for sitetag, url, label in PROVIDERS:
        result = test_provider(sitetag, url, label)
        results[sitetag] = result
        print(f"\n  >>> {sitetag}: {result}")

    print(f"\n{'='*70}")
    print("SUMMARY")
    print(f"{'='*70}")
    for sitetag, result in results.items():
        status = "PASS" if result.startswith("SUCCESS") else "FAIL"
        print(f"  [{status}] {sitetag}: {result}")
