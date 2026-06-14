#!/usr/bin/env python3
"""
Direct video scraper - bypasses broken /data endpoint.
Fetches from external sources using URL templates from /info.
"""

import json
import time
import gzip
import base64
import re
import requests
from collections import OrderedDict
from html.parser import HTMLParser

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

def parse_externulls(data, base_url):
    """Parse externulls.com JSON response (beegcom format)."""
    videos = []
    for item in data:
        file_info = item.get("file", {})
        file_data = file_info.get("data", [])
        facts = item.get("fc_facts", [])

        title = ""
        for d in file_data:
            if d.get("cd_column") == "sf_name":
                title = d.get("cd_value", "").strip("'\"")
                break

        duration = file_info.get("fl_duration", 0)
        width = file_info.get("fl_width", 0)
        height = file_info.get("fl_height", 0)

        hls = file_info.get("hls_resources", {})
        hls_url = hls.get("fl_cdn_multi", "")
        fallback = file_info.get("fallback", "")

        # Build full m3u8 URL
        m3u8_url = ""
        if hls_url:
            # Replace _TPL_ with CDN path
            m3u8_url = hls_url.replace("_TPL_", "https://video.beeg.com")

        # Build fallback MP4 URL
        mp4_url = ""
        if fallback:
            mp4_url = f"https://video.beeg.com/{fallback}"

        # Views and thumbnails
        views = 0
        thumb_times = []
        if facts:
            views = facts[0].get("fc_st_views", 0)
            thumb_times = facts[0].get("fc_thumbs", [])

        # Tags (actors, categories)
        actors = []
        categories = []
        for tag in item.get("tags", []):
            tg_name = tag.get("tg_name", "")
            tg_type = tag.get("tg_type", "")
            if tg_type in ("pornstar", "model"):
                actors.append(tg_name)
            elif tg_type in ("category", "tag"):
                categories.append(tg_name)

        # Thumbnail URL - use first thumb time
        thumb_url = ""
        file_id = file_data[0].get("cd_file", "") if file_data else ""
        if file_id and thumb_times:
            thumb_url = f"https://img.beeg.com/{file_id}/thumb_{thumb_times[0]}.jpg"

        videos.append({
            "title": title,
            "duration": duration,
            "duration_str": f"{duration // 60}:{duration % 60:02d}",
            "width": width,
            "height": height,
            "quality": height,
            "m3u8_url": m3u8_url,
            "mp4_url": mp4_url,
            "thumb_url": thumb_url,
            "views": views,
            "actors": actors,
            "categories": categories,
            "source_link": f"{base_url}video/{file_id}",
        })
    return videos

def parse_html_videos(html, site_tag, info):
    """Generic HTML video parser for sites with listDiv/videoDiv config."""
    videos = []
    list_div = info.get("listDiv", "")
    video_div = info.get("videoDiv", "")
    base = info.get("base", "")

    # Find video blocks using common patterns
    # Look for thumbnail images and associated links
    pattern = r'<a[^>]*href=["\']([^"\']+)["\'][^>]*>.*?<img[^>]*(?:src|data-src)=["\']([^"\']+)["\'][^>]*/?>.*?(?:<span[^>]*>(\d+:\d+)</span>)?'
    matches = re.findall(pattern, html, re.S | re.I)

    for link, thumb, duration in matches:
        if not link.startswith("http"):
            link = base.rstrip("/") + "/" + link.lstrip("/")
        title = link.split("/")[-1].replace("-", " ").replace("_", " ").title()
        videos.append({
            "title": title[:80],
            "duration_str": duration or "",
            "thumb_url": thumb if thumb.startswith("http") else base.rstrip("/") + thumb,
            "source_link": link,
            "site": site_tag,
        })

    return videos

def main():
    pub_key = load_pubkey()
    s = requests.Session()
    h = gen_hash(pub_key)

    # Login
    login_body = json.dumps({"username": "", "password": "", "android_id": "a1b2c3d4e5f6a7b8"})
    s.post(f"{BASE}/v9/login", data=gz(login_body),
        headers={"hash": h, "Authorization": "Bearer ", "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})

    # === BEEGCOM (JSON API via externulls.com) ===
    print("=" * 70)
    print("BEEGCOM - Direct externulls.com JSON API")
    print("=" * 70)

    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "beegcom"})
    r = s.post(f"{BASE}/v9/sites/beegcom/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    info = r.json()

    url = info["newUrl"].replace("%d", "0")
    r = requests.get(url, headers={"User-Agent": UA_BROWSER, "Referer": "https://beeg.com/"})
    data = r.json()
    videos = parse_externulls(data, "https://beeg.com/")

    print(f"Found {len(videos)} videos from beeg.com\n")
    for i, v in enumerate(videos[:5]):
        print(f"  {i+1}. {v['title']}")
        print(f"     Duration: {v['duration_str']} | Quality: {v['quality']}p | Views: {v['views']}")
        print(f"     Actors: {', '.join(v['actors'][:3])}")
        print(f"     m3u8: {v['m3u8_url'][:100]}...")
        print(f"     mp4:  {v['mp4_url'][:100]}...")
        print()

    # === EPORNER (HTML scraping) ===
    print("=" * 70)
    print("EPORNER - HTML scraping")
    print("=" * 70)

    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "epornercom"})
    r = s.post(f"{BASE}/v9/sites/epornercom/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    ep_info = r.json()
    url = ep_info["newUrl"].replace("%d", "1")
    r = requests.get(url, headers={"User-Agent": UA_BROWSER})
    if r.status_code == 200:
        videos = parse_html_videos(r.text, "epornercom", ep_info)
        print(f"Found {len(videos)} videos from eporner.com")
        for v in videos[:5]:
            print(f"  - {v['title'][:60]} [{v['duration_str']}]")
            print(f"    {v['source_link']}")

    # === YESPORN (async JSON API) ===
    print(f"\n{'='*70}")
    print("YESPORN.VIP - Async JSON API")
    print("=" * 70)

    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "yespornvip"})
    r = s.post(f"{BASE}/v9/sites/yespornvip/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    yp_info = r.json()
    url = yp_info["newUrl"].replace("%d", "1")
    r = requests.get(url, headers={"User-Agent": UA_BROWSER, "Referer": "https://yesporn.vip/", "X-Requested-With": "XMLHttpRequest"})
    print(f"Status: {r.status_code}")
    if r.status_code == 200:
        ct = r.headers.get("content-type", "")
        if "json" in ct:
            data = r.json()
            print(f"JSON keys: {list(data.keys())[:10]}")
            if "html" in data:
                videos = parse_html_videos(data["html"], "yespornvip", yp_info)
                print(f"Found {len(videos)} videos")
        else:
            videos = parse_html_videos(r.text, "yespornvip", yp_info)
            print(f"HTML response, found {len(videos)} videos")
            for v in videos[:5]:
                print(f"  - {v['title'][:60]} [{v['duration_str']}]")
                print(f"    {v['source_link']}")

    # === BOUNDHUB (async block) ===
    print(f"\n{'='*70}")
    print("BOUNDHUB - Async block API")
    print("=" * 70)

    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "boundhubcom"})
    r = s.post(f"{BASE}/v9/sites/boundhubcom/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    bh_info = r.json()
    url = bh_info["newUrl"].replace("%d", "0")
    r = requests.get(url, headers={"User-Agent": UA_BROWSER, "Referer": "https://www.boundhub.com/", "X-Requested-With": "XMLHttpRequest"})
    print(f"Status: {r.status_code}")
    if r.status_code == 200:
        ct = r.headers.get("content-type", "")
        if "json" in ct:
            data = r.json()
            if "html" in data:
                videos = parse_html_videos(data["html"], "boundhubcom", bh_info)
                print(f"Found {len(videos)} videos")
                for v in videos[:5]:
                    print(f"  - {v['title'][:60]} [{v['duration_str']}]")
                    print(f"    {v['source_link']}")
        else:
            videos = parse_html_videos(r.text, "boundhubcom", bh_info)
            print(f"Found {len(videos)} videos")
            for v in videos[:5]:
                print(f"  - {v['title'][:60]} [{v['duration_str']}]")
                print(f"    {v['source_link']}")

    # === CHATURBATE (public API) ===
    print(f"\n{'='*70}")
    print("CHATURBATE - Public API")
    print("=" * 70)

    info_body = json.dumps({"filter": {"viewer": "new", "page": 1}, "globalSearch": False, "porntabs": False, "site": "chaturbatecom"})
    r = s.post(f"{BASE}/v9/sites/chaturbatecom/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    cb_info = r.json()
    # Fix the URL - it needs a real IP
    url = cb_info["newUrl"].replace("%d", "").replace("%s", "").replace("%t", "").replace("127.0.0.1", "8.8.8.8")
    print(f"URL: {url}")
    r = requests.get(url, headers={"User-Agent": UA_BROWSER})
    print(f"Status: {r.status_code}")
    if r.status_code == 200:
        try:
            data = r.json()
            rooms = data.get("rooms", [])
            print(f"Found {len(rooms)} live rooms")
            for room in rooms[:5]:
                print(f"  - {room.get('username', '?')} ({room.get('num_users', 0)} viewers)")
                print(f"    Image: {room.get('imgroompage', '')[:80]}")
        except:
            print(f"Response: {r.text[:200]}")

    # === SUMMARY ===
    print(f"\n\n{'='*70}")
    print("SUMMARY: WORKING DIRECT SCRAPING")
    print("=" * 70)
    print("""
The /data endpoint returns 404 because the porn-app.com server cannot fetch
external URLs. However, we CAN fetch directly:

1. beegcom → externulls.com JSON API (48 videos per page, full metadata + HLS URLs)
2. epornercom → HTML scraping (works, needs client-side hash computation for streams)
3. yespornvip → Async HTML block API
4. boundhubcom → Async HTML block API
5. spankbangcom → HTML scraping (works)
6. hentaimamaio → HTML scraping (works)
7. xnxxcom → HTML scraping (works)

Sites that block direct access (need Cloudflare bypass or HtmlUnit):
- pornhubcom, xhamstercom, redtubecom, youporncom → SSL/connection issues
- pornonecom, supjavcom → Cloudflare protection
- chaturbatecom → needs valid client_ip parameter

The key insight: the app's server normally fetches these URLs and parses them
server-side. Since the server's scraping is broken, we can replicate the same
logic client-side by fetching the URLs directly and parsing ourselves.
""")

if __name__ == "__main__":
    main()
