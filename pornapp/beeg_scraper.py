#!/usr/bin/env python3
"""
Working beegcom scraper - fetches directly from externulls.com CDN.
Produces playable m3u8 and mp4 URLs with full metadata.
"""

import json
import time
import gzip
import base64
import requests
from collections import OrderedDict

BASE = "https://porn-app.com/api"
UA_APP = "okhttp/5.3.2"
UA_BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
CDN_BASE = "https://video.beeg.com/"

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

def fetch_beeg_videos(page=0, viewer="new"):
    """Fetch videos from beeg.com via externulls.com CDN."""
    pub_key = load_pubkey()
    s = requests.Session()
    h = gen_hash(pub_key)

    # Get site info (for URL template)
    info_body = json.dumps({"filter": {"viewer": viewer, "page": page + 1}, "globalSearch": False, "porntabs": False, "site": "beegcom"})
    r = s.post(f"{BASE}/v9/sites/beegcom/info", data=gz(info_body),
        headers={"hash": h, "Content-Encoding": "gzip",
                 "Content-Type": "application/json; charset=utf-8", "User-Agent": UA_APP})
    info = r.json()

    # Build URL from template
    url_template = info.get("newUrl", "")  # or hotUrl, mvUrl based on viewer
    if viewer == "hot" and info.get("hotUrl"):
        url_template = info["hotUrl"]
    elif viewer == "views" and info.get("mvUrl"):
        url_template = info["mvUrl"]

    url = url_template.replace("%d", str(page * 48))

    # Fetch from externulls.com directly
    r = requests.get(url, headers={"User-Agent": UA_BROWSER, "Referer": "https://beeg.com/"})
    r.raise_for_status()
    data = r.json()

    videos = []
    for item in data:
        file_info = item.get("file", {})
        file_data = file_info.get("data", [])
        facts = item.get("fc_facts", [])

        # Title
        title = ""
        file_id = None
        for d in file_data:
            if d.get("cd_column") == "sf_name":
                title = d.get("cd_value", "").strip("'\"")
                file_id = d.get("cd_file")
                break

        # Duration and resolution
        duration = file_info.get("fl_duration", 0)
        width = file_info.get("fl_width", 0)
        height = file_info.get("fl_height", 0)

        # HLS m3u8 URL
        hls = file_info.get("hls_resources", {})
        hls_raw = hls.get("fl_cdn_multi", "")
        m3u8_url = CDN_BASE + hls_raw if hls_raw else ""

        # Fallback MP4 URL
        fallback = file_info.get("fallback", "")
        mp4_url = CDN_BASE + fallback if fallback else ""

        # Analytics
        views = 0
        thumb_times = []
        reactions = 0
        if facts:
            views = facts[0].get("fc_st_views", 0)
            thumb_times = facts[0].get("fc_thumbs", [])
            reactions = facts[0].get("reactions_count", 0)

        # Thumbnail
        thumb_url = ""
        if file_id and thumb_times:
            thumb_url = f"https://img.beeg.com/{file_id}/thumb_{thumb_times[0]}.jpg"

        # Parse quality details
        qualities = []
        if facts and facts[0].get("qualities", {}).get("h264"):
            for q in facts[0]["qualities"]["h264"]:
                qualities.append({
                    "resolution": q.get("quality"),
                    "video_codec": q.get("video_codec"),
                    "audio_codec": q.get("audio_codec"),
                })

        # Tags
        actors = []
        categories = []
        for tag in item.get("tags", []):
            tg_name = tag.get("tg_name", "")
            tg_type = tag.get("tg_type", "")
            if tg_type in ("pornstar", "model"):
                actors.append(tg_name)
            elif tg_type in ("category", "tag", "Intro"):
                categories.append(tg_name)

        # URL expiry
        expiry = 0
        if hls_raw and "end=" in hls_raw:
            try:
                expiry = int(hls_raw.split("end=")[1].split(",")[0])
            except:
                pass

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
            "reactions": reactions,
            "actors": actors,
            "categories": categories,
            "qualities": qualities,
            "url_expires": expiry,
            "source_link": f"https://beeg.com/{file_id}" if file_id else "",
        })

    return videos

def main():
    print("Fetching beeg.com videos (page 1, newest)...")
    videos = fetch_beeg_videos(page=0, viewer="new")
    print(f"Found {len(videos)} videos\n")

    for i, v in enumerate(videos[:10]):
        print(f"{i+1:2d}. {v['title'][:70]}")
        print(f"    {v['duration_str']} | {v['quality']}p | {v['views']:,} views | {v['reactions']} likes")
        if v['actors']:
            print(f"    Actors: {', '.join(v['actors'][:3])}")
        print(f"    m3u8: {v['m3u8_url'][:120]}...")
        print(f"    mp4:  {v['mp4_url'][:120]}...")
        print(f"    thumb: {v['thumb_url']}")
        print(f"    expires: {time.strftime('%Y-%m-%d %H:%M', time.gmtime(v['url_expires']))} UTC")
        print()

    # Save full data
    output_file = "beeg_videos.json"
    with open(output_file, "w") as f:
        json.dump(videos, f, indent=2)
    print(f"Full data saved to {output_file}")

    # Test that an m3u8 URL actually works
    if videos:
        print(f"\n--- Testing m3u8 URL ---")
        test_url = videos[0]["m3u8_url"]
        r = requests.get(test_url, headers={"User-Agent": UA_BROWSER, "Referer": "https://beeg.com/"}, stream=True)
        print(f"Status: {r.status_code}")
        if r.status_code == 200:
            content = r.text[:500]
            print(f"m3u8 content:\n{content}")
        else:
            print(f"Error: {r.text[:200]}")

        print(f"\n--- Testing mp4 URL ---")
        test_url = videos[0]["mp4_url"]
        r = requests.head(test_url, headers={"User-Agent": UA_BROWSER, "Referer": "https://beeg.com/"})
        print(f"Status: {r.status_code}")
        print(f"Content-Type: {r.headers.get('content-type', '?')}")
        print(f"Content-Length: {r.headers.get('content-length', '?')}")

if __name__ == "__main__":
    main()
