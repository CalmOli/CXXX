import json
import collections
import time
import subprocess
import base64
import requests
import gzip
import cloudscraper

BASE_URL = "https://porn-app.com/api/v9"
PUBKEY_PATH = "/tmp/pubkey.pem"
SITE_TO_TEST = "xxxtube"

bearer_token = ""

def generate_hash():
    ts = int(time.time())
    payload = collections.OrderedDict([
        ('id', 'a1b2c3d4e5f6a7b8'), ('isTV', False),
        ('loginStatus', collections.OrderedDict([
            ('pro', 0), ('status', 0), ('token', bearer_token), ('unixtime', ts), ('user_id', 0)
        ])),
        ('packageName', 'com.streamdev.aiostreamer'),
        ('signatures', ['VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw=']),
        ('time', ts), ('version', 6643)
    ])
    json_str = json.dumps(payload, separators=(',', ':'))
    cmd = ['openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', PUBKEY_PATH, '-pkeyopt', 'rsa_padding_mode:pkcs1']
    try:
        p1 = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        enc_out, err = p1.communicate(input=json_str.encode())
        if p1.returncode != 0: return ""
        return base64.b64encode(enc_out).decode('utf-8').strip()
    except Exception:
        return ""

def make_request(endpoint, method="GET", payload=None, gzip_body=False):
    url = f"{BASE_URL}{endpoint}"
    headers = {
        "User-Agent": "okhttp/5.3.2",
        "Authorization": f"Bearer {bearer_token}",
        "Accept-Encoding": "gzip",
        "hash": generate_hash()
    }
    print(f"\n[>] {method} {url}")
    if method == "GET":
        resp = requests.get(url, headers=headers)
    else:
        if gzip_body:
            headers["Content-Encoding"] = "gzip"
            headers["Content-Type"] = "application/json; charset=UTF-8"
            resp = requests.post(url, headers=headers, data=gzip.compress(json.dumps(payload).encode()))
        else:
            headers["Content-Type"] = "application/json; charset=UTF-8"
            resp = requests.post(url, headers=headers, json=payload)
    print(f"[<] Status: {resp.status_code}")
    if resp.status_code != 200:
        try: print(f"    Body: {resp.text[:300]}")
        except: pass
    return resp

def test_lifecycle():
    global bearer_token

    login_resp = make_request("/login", method="POST", payload={}, gzip_body=True)
    if login_resp and login_resp.status_code == 200:
        bearer_token = login_resp.json().get("token", "")

    # 1. Use /data with listing page HTML to find a video
    scraper = cloudscraper.create_scraper()
    print(f"[*] Fetching {SITE_TO_TEST} listing HTML via cloudscraper...")
    listing_html = scraper.get("https://x-x-x.tube/videos/").text
    print(f"    Got {len(listing_html)} bytes of HTML")

    data_payload = {"payload": listing_html}
    data_resp = make_request(f"/sites/{SITE_TO_TEST}/data?isTV=false", method="POST", payload=data_payload)

    target_video = None
    if data_resp and data_resp.status_code == 200:
        parsed_data = data_resp.json()
        valid_videos = [v for v in parsed_data if v.get("link") != "NATIVE_AD" and "video_id" in v]
        if valid_videos:
            target_video = valid_videos[0]
            print(f"[+] Found Video ID: {target_video['video_id']}")
            print(f"[+] Title: {target_video.get('title')}")
            print(f"[+] URL: {target_video.get('link')}")

    if not target_video:
        print("[-] No video found")
        return

    # 2. Fetch the video page HTML
    vid_url = target_video["link"]
    print(f"[*] Fetching video page HTML from {vid_url}...")
    video_html = scraper.get(vid_url).text
    print(f"    Got {len(video_html)} bytes of HTML")

    # 3. Call /stream with video page HTML as payload + videoObject (matching actual app behavior)
    stream_payload = {
        "payload": video_html,
        "videoObject": {
            "video_id": target_video["video_id"],
            "sourceLink": vid_url,
            "streamLink": "",
            "title": target_video.get("title", ""),
            "image": target_video.get("img", ""),
            "duration": target_video.get("duration", "0:00"),
            "quality": target_video.get("quality", 0),
            "webm": target_video.get("webm", ""),
            "site": SITE_TO_TEST,
            "seconds": 0,
            "embedLink": "",
            "hosterLink": "",
            "hosterSite": "",
            "download": False,
            "test": False
        }
    }
    stream_resp = make_request(f"/sites/{SITE_TO_TEST}/stream?isTV=false", method="POST", payload=stream_payload, gzip_body=True)
    if stream_resp and stream_resp.status_code == 200:
        print("\n[SUCCESS] /stream returned video URLs:")
        print(json.dumps(stream_resp.json(), indent=2))
    else:
        print(f"[-] /stream failed")

if __name__ == "__main__":
    test_lifecycle()
